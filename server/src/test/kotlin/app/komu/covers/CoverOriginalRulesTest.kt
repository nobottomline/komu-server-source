package app.komu.covers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** URL shapes are the ones measured live on 2026-11-08 (ADR-0084). */
class CoverOriginalRulesTest {
    private fun candidate(
        url: String,
        base: String? = null,
    ) = CoverOriginalRules.candidateFor(url, base)

    @Test
    fun `wordpress size suffix is removed on the source's own site and keeps the fragment`() {
        val result =
            candidate(
                "https://likemanga.in/wp-content/uploads/images/1704704010-659bb80a9e4ce-dungeon-55016-175x238.jpg#thumb",
                "https://likemanga.in",
            )!!
        assertEquals(CoverOriginalRules.WORDPRESS_SIZE_SUFFIX, result.ruleId)
        // The fragment keeps LikeManga's thumbnail rate-limit exemption semantics.
        assertEquals("https://likemanga.in/wp-content/uploads/images/1704704010-659bb80a9e4ce-dungeon-55016.jpg#thumb", result.url)
    }

    @Test
    fun `wordpress rule accepts www and bare host of one site and every image extension`() {
        assertEquals(
            "https://bunmanga.com/wp-content/uploads/2025/05/Why-Is-The-Male-Lead-Seducing-Me.jpeg",
            candidate("https://bunmanga.com/wp-content/uploads/2025/05/Why-Is-The-Male-Lead-Seducing-Me-193x278.jpeg", "https://www.bunmanga.com")?.url,
        )
        for (extension in listOf("png", "webp", "gif", "JPG")) {
            assertEquals(
                "https://drakecomic.com/wp-content/uploads/2023/01/Time.$extension",
                candidate("https://drakecomic.com/wp-content/uploads/2023/01/Time-207x300.$extension", "https://drakecomic.com")?.url,
            )
        }
    }

    @Test
    fun `wordpress rule never applies to another site's uploads or without a source site`() {
        val url = "https://cdn.example.net/wp-content/uploads/2023/01/Time-207x300.jpg"
        assertNull(candidate(url, "https://drakecomic.com"))
        assertNull(candidate(url, null))
        assertNull(candidate("https://drakecomic.com/wp-content/uploads/2023/01/Time-207x300.jpg", "https://evil-drakecomic.com"))
    }

    @Test
    fun `wordpress rule needs the uploads path and a trailing size suffix`() {
        assertNull(candidate("https://likemanga.in/images/cover-175x238.jpg", "https://likemanga.in"))
        assertNull(candidate("https://likemanga.in/wp-content/uploads/cover.jpg", "https://likemanga.in"))
        assertNull(candidate("https://likemanga.in/wp-content/uploads/cover-175x238.jpg.php", "https://likemanga.in"))
        // A size inside the name (an uploaded 193x278 file) is not the suffix.
        assertEquals(
            "https://likemanga.in/wp-content/uploads/images/1692840546-64e6b262b7134-annoyingalice193x278-7361.webp",
            candidate(
                "https://likemanga.in/wp-content/uploads/images/1692840546-64e6b262b7134-annoyingalice193x278-7361-175x238.webp",
                "https://likemanga.in",
            )?.url,
        )
    }

    @Test
    fun `baozi resize parameters are dropped and quality kept`() {
        val result = candidate("https://static-tw.baozimh.com/cover/silingfashiwojishitianzai-mantudezhuyuanzhuheiniaoshe.jpg?w=285&h=375&q=100")!!
        assertEquals(CoverOriginalRules.BAOZI_COVER_RESIZE, result.ruleId)
        assertEquals("https://static-tw.baozimh.com/cover/silingfashiwojishitianzai-mantudezhuyuanzhuheiniaoshe.jpg?q=100", result.url)
    }

    @Test
    fun `baozi rule needs its cover CDN and a size parameter`() {
        assertNull(candidate("https://static-tw.baozimh.com/cover/x.jpg?q=100"))
        assertNull(candidate("https://static-tw.baozimh.com/scomic/x.jpg?w=285&h=375"))
        assertNull(candidate("https://static-tw.baozimh.com.evil.net/cover/x.jpg?w=285&h=375"))
        assertNull(candidate("https://cn.webmota.com/cover/x.jpg?w=285&h=375"))
    }

    @Test
    fun `mangageko avatar size directory is removed on its image server`() {
        val result = candidate("https://imgsrv5.com/avatar/288x412/media/manga_covers/apex-future-martial-arts.jpg")!!
        assertEquals(CoverOriginalRules.MANGAGEKO_AVATAR_SIZE, result.ruleId)
        assertEquals("https://imgsrv5.com/media/manga_covers/apex-future-martial-arts.jpg", result.url)
        assertNull(candidate("https://imgsrv5.com/media/manga_covers/apex-future-martial-arts.jpg"))
        assertNull(candidate("https://imgsrv5.com.evil.net/avatar/288x412/media/x.jpg"))
    }

    @Test
    fun `goda width suffix has no rule because no original exists`() {
        assertNull(candidate("https://c-nc-1.6wm.top/manga/baoyuzheng-26482/MrfJXwLdX.webp-w320.webp", "https://baozimh.org"))
    }

    @Test
    fun `non http urls and garbage never match`() {
        assertNull(candidate("ftp://likemanga.in/wp-content/uploads/a-175x238.jpg", "https://likemanga.in"))
        assertNull(candidate("/wp-content/uploads/a-175x238.jpg", "https://likemanga.in"))
        assertNull(candidate("", "https://likemanga.in"))
    }
}
