package app.komu.security

import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
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

    companion object {
        private const val UNAUTHORIZED_BODY = "Unauthorized"

        /**
         * Installs the capability check above every handler already supplied by
         * Javalin/Jetty. Javalin can then attach its servlet to the preserved
         * inner wrapper while this boundary remains the server root.
         */
        fun installAsServerRoot(
            server: Server,
            isAuthorized: (String?) -> Boolean,
        ) {
            check(server.handler !is EmbeddedCapabilityHandler) {
                "Embedded capability boundary was already installed"
            }
            val existingRoot = server.handler
            server.handler =
                EmbeddedCapabilityHandler(isAuthorized).apply {
                    handler = existingRoot
                }
        }
    }
}
