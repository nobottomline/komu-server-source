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
 * </ul>
 *
 * <p>Command and event payloads are frames of UTF-8 fields; see
 * {@code xyz.nulldev.androidcompat.webkit.ios.WebEngineFrames}.
 */
public final class JsExecutor {
    public static final int ABI_VERSION = 3;

    // availability()
    public static final int AVAILABLE = 0;
    public static final int REQUIRES_FOREGROUND = 1;
    public static final int STOPPED = 2;

    // submit() operations
    public static final int OP_LOAD_URL = 1;          // url, headers, userAgent, cookies, flags, method, body
    public static final int OP_LOAD_DATA = 2;         // baseUrl, data, mimeType, encoding, userAgent, cookies, flags, historyUrl
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

    // nextEvents() kinds
    public static final int EV_PAGE_STARTED = 1;   // url
    public static final int EV_PAGE_FINISHED = 2;  // url
    public static final int EV_LOAD_ERROR = 3;     // url, NSURLError code, description, isMainFrame
    public static final int EV_EVAL_RESULT = 4;    // id = callback id; JSON
    public static final int EV_BRIDGE_CALL = 5;    // interface, method, arguments (JSON array)
    public static final int EV_WEB_MESSAGE = 6;    // id = reply id; listener, message, origin, isMainFrame
    public static final int EV_REQUEST = 7;        // id = decision id (0: observation); url, method, isMainFrame, isRedirect, headers
    public static final int EV_COOKIES = 8;        // url, Set-Cookie line, url, Set-Cookie line, ...
    public static final int EV_PROCESS_GONE = 9;   // didCrash, reason

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

    /** {@link #AVAILABLE}, {@link #REQUIRES_FOREGROUND} or {@link #STOPPED}; never blocks. */
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
