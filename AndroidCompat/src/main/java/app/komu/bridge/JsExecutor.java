/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.bridge;

/**
 * Komu: JNI surface of the iOS host's off-screen WKWebView engine
 * ({@code Modules/Sources/JNIBridge/JsExecutor.m} in the Komu app).
 *
 * <p>Protocol (ABI {@link #ABI_VERSION}), designed so that neither side waits on
 * the other's critical thread:
 * <ul>
 *   <li>{@link #create()} only allocates a session handle; the native side creates
 *       the WKWebView lazily, on the iOS main queue, when the first command runs.</li>
 *   <li>{@link #submit} enqueues one command with {@code dispatch_async}; it never
 *       waits for the main queue.</li>
 *   <li>Everything the engine reports (page events, evaluation results, bridge calls,
 *       observed requests, cookies, process loss) is an event in one native queue
 *       that a single JVM thread drains with {@link #nextEvents}. The iOS main thread
 *       only appends to that queue and never calls into the JVM.</li>
 *   <li>Page messages — every script message, whatever its name: bridge calls, web
 *       messages and request observations (ABI 8, Recheck 3) — are credit-bounded per
 *       session: the engine admits each against the session's budget before encoding
 *       or holding it, each carries its cost, the JVM returns it with {@link #OP_ACK}
 *       once it has handled the message, and the engine drops messages over the
 *       budget (reported as {@link #EV_MESSAGES_DROPPED}; sustained flooding ends
 *       the session). Neither side holds more than the budget.</li>
 * </ul>
 *
 * <p>Command and event payloads are frames of UTF-8 fields; see
 * {@code xyz.nulldev.androidcompat.webkit.ios.WebEngineFrames}.
 */
public final class JsExecutor {
    public static final int ABI_VERSION = 8;

    // availability()
    public static final int AVAILABLE = 0;
    public static final int REQUIRES_FOREGROUND = 1;
    public static final int STOPPED = 2;
    /**
     * The host has no per-session egress proxy API (before iOS 17), so it cannot route
     * a session's sub-resources through its admission chokepoint and runs none.
     */
    public static final int REQUIRES_IOS_17 = 3;

    // submit() operations
    // Cookies travel as a JSON array of objects (WebCookies.kt `CookieWire`).
    public static final int OP_LOAD_URL = 1;          // url, headers, userAgent, cookies, flags, method, body, preload sequence
    public static final int OP_LOAD_DATA = 2;         // baseUrl, data, mimeType, encoding, userAgent, cookies, flags, historyUrl, preload sequence
    public static final int OP_EVALUATE = 3;          // id = callback id; script
    public static final int OP_ADD_INTERFACE = 4;     // name, methods (JSON array)
    public static final int OP_REMOVE_INTERFACE = 5;  // name
    public static final int OP_ADD_DOC_START = 6;     // id = script id; script, origin rules (JSON array)
    public static final int OP_REMOVE_DOC_START = 7;  // id = script id
    public static final int OP_ADD_LISTENER = 8;      // name, origin rules (JSON array)
    public static final int OP_REMOVE_LISTENER = 9;   // name
    public static final int OP_POST_REPLY = 10;       // id = reply id; message
    public static final int OP_SET_INTERCEPTION = 11; // "1" | "0"
    public static final int OP_DECIDE = 12;           // id = decision id; "1" allow | "0" block
    public static final int OP_STOP = 13;
    public static final int OP_RELOAD = 14;
    /** A load the session will run once it has a slot: admitted (and bound, ADR-0063) now, run later. */
    public static final int OP_BIND = 15;             // url ("about:blank" or a data load's base for a hostless one)
    /** The JVM finished with a page message (bridge call, web message, observation): returns its cost to the session's budget. */
    public static final int OP_ACK = 16;              // cost
    /**
     * Server changes for the session's cookie store (Recheck 2): set, then delete, in the
     * session's single cookie order (after every earlier preload, before every later snapshot).
     */
    public static final int OP_SET_COOKIES = 17;      // cookies to set (JSON array), cookies to delete (JSON array)

    // nextEvents() kinds
    public static final int EV_PAGE_STARTED = 1;   // url
    public static final int EV_PAGE_FINISHED = 2;  // url
    public static final int EV_LOAD_ERROR = 3;     // url, NSURLError code, description, isMainFrame
    public static final int EV_EVAL_RESULT = 4;    // id = callback id; JSON
    public static final int EV_BRIDGE_CALL = 5;    // interface, method, arguments (JSON array), cost
    public static final int EV_WEB_MESSAGE = 6;    // id = reply id; listener, message, origin, isMainFrame, cost
    public static final int EV_REQUEST = 7;        // id = decision id (0: observation); url, method, isMainFrame, isRedirect, headers[, cost: observations only]
    public static final int EV_COOKIES = 8;        // admitted target hosts (JSON array), the session's cookies (JSON array), last preload applied before the snapshot
    public static final int EV_PROCESS_GONE = 9;   // didCrash, reason
    public static final int EV_MESSAGES_DROPPED = 10; // count: page messages dropped over the session's budget

    private static final boolean LINKED;

    static {
        boolean linked;
        try {
            // Statically linked in the app (JNI_OnLoad_komubridge); a dylib on a desktop test host.
            System.loadLibrary("komubridge");
            linked = abiVersion() == ABI_VERSION;
        } catch (UnsatisfiedLinkError e) {
            linked = false;
        }
        LINKED = linked;
    }

    private JsExecutor() {}

    /** Whether this process links the engine with the expected ABI. */
    public static boolean isLinked() {
        return LINKED;
    }

    /** Live native sessions (diagnostics and tests); -1 when the engine is not linked. */
    public static int liveSessionCount() {
        return LINKED ? nativeLiveSessionCount() : -1;
    }

    public static native int abiVersion();

    /** {@link #AVAILABLE}, {@link #REQUIRES_FOREGROUND}, {@link #STOPPED} or {@link #REQUIRES_IOS_17}; never blocks. */
    public static native int availability();

    /** A new session handle, or 0 when the engine refuses new sessions. Never blocks. */
    public static native long create();

    /** Ends a session; its web view is torn down on the main queue. Never blocks. */
    public static native void destroy(long handle);

    /** Enqueues one command; {@code fields} is a frame of UTF-8 fields. Never blocks. */
    public static native void submit(long handle, int op, long id, byte[] fields);

    /** A batch of encoded events, or null when none arrived within {@code timeoutMs}. */
    public static native byte[] nextEvents(int timeoutMs);

    private static native int nativeLiveSessionCount();
}
