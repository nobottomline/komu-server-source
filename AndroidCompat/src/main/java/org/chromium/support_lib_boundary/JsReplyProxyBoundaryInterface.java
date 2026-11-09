// Copyright 2018 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
//
// Komu: the subset of the AndroidX WebKit boundary interface that the iOS
// WebView engine implements (method names and parameter types are the
// boundary contract; extensions call them through reflection).

package org.chromium.support_lib_boundary;

import java.lang.reflect.InvocationHandler;

/** Boundary interface for JsReplyProxy. */
public interface JsReplyProxyBoundaryInterface {
    void postMessage(String message);

    void executeJavaScript(String script, /* ValueCallback */ InvocationHandler callback);
}
