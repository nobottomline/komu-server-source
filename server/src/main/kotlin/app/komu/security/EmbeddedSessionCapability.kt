package app.komu.security

import java.security.MessageDigest
import java.util.Base64

/**
 * Immutable verifier for Komu's 256-bit, process-local iOS capability.
 *
 * The bootstrap token is reduced to a SHA-256 digest immediately. Request
 * candidates are hashed to the same fixed width before constant-time digest
 * comparison, and request-owned byte arrays are cleared after use.
 */
internal class SessionCapability private constructor(
    expectedDigest: ByteArray,
) {
    private val expectedDigest = expectedDigest.copyOf()

    fun matches(candidate: String?): Boolean {
        val candidateBytes = (candidate ?: "").toByteArray(Charsets.UTF_8)
        val candidateDigest = sha256(candidateBytes)
        candidateBytes.fill(0)
        val matches = MessageDigest.isEqual(expectedDigest, candidateDigest)
        candidateDigest.fill(0)
        return matches
    }

    companion object {
        const val BYTE_COUNT = 32
        const val ENCODED_LENGTH = 43
        private val encodedPattern = Regex("^[A-Za-z0-9_-]{$ENCODED_LENGTH}$")

        fun fromBootstrapToken(token: String): SessionCapability {
            require(encodedPattern.matches(token)) {
                "Embedded session capability has an invalid shape"
            }
            val decoded =
                try {
                    Base64.getUrlDecoder().decode(token)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("Embedded session capability is invalid")
                }
            require(decoded.size == BYTE_COUNT) {
                "Embedded session capability has an invalid size"
            }
            decoded.fill(0)

            val tokenBytes = token.toByteArray(Charsets.UTF_8)
            val digest = sha256(tokenBytes)
            tokenBytes.fill(0)
            return SessionCapability(digest).also { digest.fill(0) }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** Runtime holder installed before applicationSetup opens any HTTP surface. */
internal object EmbeddedSessionCapability {
    const val HEADER_NAME = "X-Komu-Session"

    private val embeddedIOSProfile: Boolean
        get() = System.getProperty("komu.platform") == "ios"

    @Volatile
    private var verifier: SessionCapability? = null

    val isRequired: Boolean
        get() = embeddedIOSProfile

    fun initialize(mainArguments: Array<String>) {
        if (!embeddedIOSProfile) {
            return
        }
        require(mainArguments.size == 1) {
            "Embedded iOS profile requires exactly one session capability"
        }
        check(verifier == null) {
            "Embedded session capability was already initialized"
        }
        verifier = SessionCapability.fromBootstrapToken(mainArguments.single())
    }

    fun requireInstalled() {
        check(!embeddedIOSProfile || verifier != null) {
            "Embedded iOS security boundary was not initialized"
        }
    }

    fun isAuthorized(candidate: String?): Boolean {
        if (!embeddedIOSProfile) {
            return true
        }
        return verifier?.matches(candidate) == true
    }
}
