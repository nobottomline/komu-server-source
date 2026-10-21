package suwayomi.tachidesk.manga.impl.extension

import android.os.Bundle
import org.junit.jupiter.api.Test
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import suwayomi.tachidesk.manga.impl.util.ApkExtensionMetadata
import suwayomi.tachidesk.manga.impl.util.PackageTools
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApkExtensionMetadataTest {
    @Test
    fun `compiled float API and integer content warning retain manifest semantics`() {
        val metadata = ApkExtensionMetadata.parse(manifest())
        assertEquals("1.4", metadata[PackageTools.METADATA_EXTENSION_LIB])
        assertEquals("1", metadata[PackageTools.METADATA_CONTENT_WARNING])
        assertEquals("1", metadata[PackageTools.METADATA_NSFW])
        assertEquals(".ExtensionGenerated", metadata[PackageTools.METADATA_SOURCE_CLASS])
    }

    @Test
    fun `nested activity metadata cannot override application extension identity`() {
        val metadata = ApkExtensionMetadata.parse(manifest())
        assertEquals("1.4", metadata[PackageTools.METADATA_EXTENSION_LIB])
        assertEquals(4, metadata.size)
    }

    @Test
    fun `normalized package metadata reads numeric warning without Bundle type coercion`() {
        val bundle = Bundle()
        ApkExtensionMetadata.parse(manifest()).forEach { (name, value) -> bundle.putString(name, value) }
        val provider = Extension.MetadataProvider.FromPackageInfo(bundle)
        assertEquals(1, provider.getInt(PackageTools.METADATA_CONTENT_WARNING))
        assertEquals("1.4", provider.getString(PackageTools.METADATA_EXTENSION_LIB))
        bundle.putInt(PackageTools.METADATA_CONTENT_WARNING, 2)
        assertEquals(2, provider.getInt(PackageTools.METADATA_CONTENT_WARNING))
        assertEquals(0, provider.getInt("missing"))
    }

    @Test
    fun `oversized and empty APK manifests are rejected before binary parsing`() {
        assertFailsWith<IllegalArgumentException> { ApkExtensionMetadata.parse(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { ApkExtensionMetadata.parse(ByteArray(1024 * 1024 + 1)) }
    }

    private fun manifest(): ByteArray {
        val writer = AxmlWriter()
        writer.ns("android", ANDROID_NAMESPACE, 1)
        val manifest = writer.child(null, "manifest")
        val application = manifest.child(null, "application")
        metadata(application, PackageTools.METADATA_EXTENSION_LIB, 0x04, 1.4f.toRawBits())
        metadata(application, PackageTools.METADATA_CONTENT_WARNING, NodeVisitor.TYPE_FIRST_INT, 1)
        metadata(application, PackageTools.METADATA_NSFW, NodeVisitor.TYPE_INT_BOOLEAN, true)
        metadata(application, PackageTools.METADATA_SOURCE_CLASS, NodeVisitor.TYPE_STRING, ".ExtensionGenerated")
        val activity = application.child(null, "activity")
        metadata(activity, PackageTools.METADATA_EXTENSION_LIB, NodeVisitor.TYPE_STRING, "wrong")
        activity.end()
        application.end()
        manifest.end()
        writer.end()
        return writer.toByteArray()
    }

    private fun metadata(
        parent: NodeVisitor,
        name: String,
        type: Int,
        value: Any,
    ) {
        parent.child(null, "meta-data").apply {
            attr(ANDROID_NAMESPACE, "name", 0x01010003, NodeVisitor.TYPE_STRING, name)
            attr(ANDROID_NAMESPACE, "value", 0x01010024, type, value)
            end()
        }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
