/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A larger original that a source's thumbnail URL names implicitly.
 *
 * [maxAspectDeviation] is how far the original's aspect ratio may differ
 * from the thumbnail's: WordPress hard-crops its size variants, and the
 * Baozi and MangaGeko resizers stretch to a fixed box, so the original of a
 * genuine pair can have another shape. The picture itself is still checked
 * by [CoverIdentity].
 */
data class CoverCandidate(
    val ruleId: String,
    val url: String,
    val maxAspectDeviation: Double,
    /** Hosts a fetch of [url] may reach, redirects included ([CoverEgress]). */
    val allowedHosts: Set<String>,
)

/**
 * The explicit, per-host rule set mapping a source thumbnail URL to its
 * candidate original (ADR-0084). A rule only rewrites a URL on a host the
 * source itself serves covers from, never fetches pages, and never invents a
 * host. Every rule is backed by extension-source evidence and a live
 * measurement; a pattern that measured no originals (GoDa's `-w320.webp`
 * files) is deliberately absent.
 *
 * Bump [VERSION] when a rule is added or changes meaning: verdicts recorded
 * under an older version are re-evaluated lazily, the next time a cover is
 * shown.
 */
object CoverOriginalRules {
    const val VERSION = 1

    const val WORDPRESS_SIZE_SUFFIX = "wordpress-size-suffix"
    const val BAOZI_COVER_RESIZE = "baozi-cover-resize"
    const val MANGAGEKO_AVATAR_SIZE = "mangageko-avatar-size"

    /** `…/wp-content/uploads/…/name-175x238.jpg` → `…/name.jpg`. */
    private val wordpressSuffix =
        Regex("""^(.*/wp-content/uploads/.+?)-\d{2,4}x\d{2,4}\.(jpe?g|png|webp|gif)$""", RegexOption.IGNORE_CASE)

    /** Baozi's cover CDN: `static-tw.baozimh.com/cover/x.jpg?w=285&h=375&q=100`. */
    private val baoziHost = Regex("""^static-[a-z0-9]+\.baozimh\.com$""")

    /** MangaGeko's image server: `imgsrv5.com/avatar/288x412/media/…`. */
    private val mangaGekoHost = Regex("""^imgsrv\d*\.com$""")
    private val mangaGekoAvatar = Regex("""^/avatar/\d{2,4}x\d{2,4}(/media/.+)$""")

    /**
     * The candidate original for [thumbnailUrl], or `null` when no rule
     * applies. [sourceBaseUrl] is the source's own site; the WordPress rule
     * applies only to uploads on that site.
     */
    fun candidateFor(
        thumbnailUrl: String,
        sourceBaseUrl: String?,
    ): CoverCandidate? {
        val url = thumbnailUrl.toHttpUrlOrNull() ?: return null
        // Candidates are fetched over https only (ADR-0084 Review 1, [CoverEgress]).
        if (url.scheme != "https") return null
        return wordpress(url, sourceBaseUrl)
            ?: baozi(url)
            ?: mangaGeko(url)
    }

    private fun wordpress(
        url: HttpUrl,
        sourceBaseUrl: String?,
    ): CoverCandidate? {
        val sourceHost = sourceBaseUrl?.toHttpUrlOrNull()?.host ?: return null
        if (siteHost(url.host) != siteHost(sourceHost)) return null
        val match = wordpressSuffix.matchEntire(url.encodedPath) ?: return null
        val path = "${match.groupValues[1]}.${match.groupValues[2]}"
        val candidate = url.newBuilder().encodedPath(path).build()
        // `www.` and the bare host are one site (WordPress redirects between them).
        val twin = if (url.host.startsWith("www.")) url.host.removePrefix("www.") else "www.${url.host}"
        return CoverCandidate(WORDPRESS_SIZE_SUFFIX, candidate.toString(), maxAspectDeviation = 0.5, allowedHosts = setOf(url.host, twin))
    }

    private fun baozi(url: HttpUrl): CoverCandidate? {
        if (!baoziHost.matches(url.host) || !url.encodedPath.startsWith("/cover/")) return null
        if (url.queryParameter("w") == null && url.queryParameter("h") == null) return null
        val candidate =
            url
                .newBuilder()
                .removeAllQueryParameters("w")
                .removeAllQueryParameters("h")
                .build()
        return CoverCandidate(BAOZI_COVER_RESIZE, candidate.toString(), maxAspectDeviation = 0.5, allowedHosts = setOf(url.host))
    }

    private fun mangaGeko(url: HttpUrl): CoverCandidate? {
        if (!mangaGekoHost.matches(url.host)) return null
        val match = mangaGekoAvatar.matchEntire(url.encodedPath) ?: return null
        val candidate = url.newBuilder().encodedPath(match.groupValues[1]).build()
        return CoverCandidate(MANGAGEKO_AVATAR_SIZE, candidate.toString(), maxAspectDeviation = 0.5, allowedHosts = setOf(url.host))
    }

    /** `www.example.com` and `example.com` are one site. */
    private fun siteHost(host: String): String = host.lowercase().removePrefix("www.")
}
