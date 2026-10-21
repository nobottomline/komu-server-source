package suwayomi.tachidesk.manga.impl.util

import pxb.android.axml.AxmlParser
import pxb.android.axml.NodeVisitor

/** Reads primitive application metadata without apk-parser's lossy XML rendering of floats. */
internal object ApkExtensionMetadata {
    fun parse(manifestBytes: ByteArray): Map<String, String> {
        require(manifestBytes.size in 1..MAX_MANIFEST_BYTES) { "APK manifest exceeds the parser size limit" }
        val parser = AxmlParser(manifestBytes)
        val metadata = linkedMapOf<String, String>()
        var depth = 0
        var applicationDepth: Int? = null
        while (true) {
            when (parser.next()) {
                AxmlParser.END_FILE -> return metadata
                AxmlParser.START_TAG -> {
                    depth++
                    if (depth == 2 && parser.name == "application") applicationDepth = depth
                    if (applicationDepth != null && depth == applicationDepth + 1 && parser.name == "meta-data") {
                        var name: String? = null
                        var value: String? = null
                        repeat(parser.attributeCount) { index ->
                            if (parser.getAttrNs(index) != ANDROID_NAMESPACE) return@repeat
                            when (parser.getAttrName(index)) {
                                "name" -> name = parser.getAttrValue(index) as? String
                                "value" -> value = primitiveValue(parser, index)
                            }
                        }
                        if (name != null && value != null) metadata[requireNotNull(name)] = requireNotNull(value)
                    }
                }

                AxmlParser.END_TAG -> {
                    if (depth == applicationDepth) applicationDepth = null
                    depth--
                }
            }
        }
    }

    private fun primitiveValue(
        parser: AxmlParser,
        index: Int,
    ): String? {
        val value = parser.getAttrValue(index)
        return when (parser.getAttrType(index)) {
            NodeVisitor.TYPE_STRING -> value as? String
            NodeVisitor.TYPE_FIRST_INT,
            NodeVisitor.TYPE_INT_HEX,
            -> (value as? Int)?.toString()
            NodeVisitor.TYPE_INT_BOOLEAN -> (value as? Boolean)?.let { if (it) "1" else "0" }
            TYPE_FLOAT -> (value as? Int)?.let { Float.fromBits(it).takeIf(Float::isFinite)?.toString() }
            else -> null // Resource references are not primitive extension identity metadata.
        }
    }

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val TYPE_FLOAT = 0x04
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
}
