package app.komu.security

import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback

/**
 * Jetty-level security boundary installed above Javalin's servlet context.
 *
 * Keeping this at the Server root protects REST, media, OPDS, static content
 * and WebSocket upgrades before route dispatch can create a bypass.
 */
internal class EmbeddedCapabilityHandler(
    private val isAuthorized: (String?) -> Boolean,
) : Handler.Wrapper() {
    override fun handle(
        request: Request,
        response: Response,
        callback: Callback,
    ): Boolean {
        if (isAuthorized(request.headers[EmbeddedSessionCapability.HEADER_NAME])) {
            return super.handle(request, response, callback)
        }

        response.status = 401
        response.headers.put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8")
        response.headers.put(HttpHeader.CACHE_CONTROL, "no-store")
        Content.Sink.write(response, true, UNAUTHORIZED_BODY, callback)
        return true
    }

    private companion object {
        const val UNAUTHORIZED_BODY = "Unauthorized"
    }
}
