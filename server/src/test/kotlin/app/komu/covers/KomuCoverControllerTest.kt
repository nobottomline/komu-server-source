package app.komu.covers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The additive preference API's wire format, which the app decodes. */
class KomuCoverControllerTest {
    @Test
    fun `every field is written`() {
        assertEquals(
            """{"preferFullSize":true,"rulesVersion":${CoverOriginalRules.VERSION},"cacheToken":"o1"}""",
            KomuCoverController.encode(true, "o1"),
        )
        assertEquals(
            """{"preferFullSize":false,"rulesVersion":${CoverOriginalRules.VERSION},"cacheToken":null}""",
            KomuCoverController.encode(false, null),
        )
    }

    @Test
    fun `an update needs the preference and ignores unknown fields`() {
        assertEquals(false, KomuCoverController.decodeUpdate("""{"preferFullSize":false,"future":1}""")?.preferFullSize)
        assertNull(KomuCoverController.decodeUpdate("""{"nope":1}"""))
        assertNull(KomuCoverController.decodeUpdate("not json"))
    }
}
