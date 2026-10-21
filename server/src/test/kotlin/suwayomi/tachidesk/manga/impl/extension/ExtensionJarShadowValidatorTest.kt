package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class ExtensionJarShadowValidatorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `shadow loader returns immutable metadata and allows same-package update IDs`() {
        val jar = compileFixture(listOf(SOURCE_ID))

        val result =
            ExtensionJarShadowValidator.validate(
                jar,
                artifactMetadata(),
                extensionInfo(listOf(source(SOURCE_ID))),
                mapOf(SOURCE_ID to PACKAGE_NAME),
            )

        assertEquals(listOf(ExtensionJarShadowValidator.SourceMetadata(SOURCE_ID, SOURCE_NAME, "all")), result.sources)
        assertEquals("all", result.extensionLanguage)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.sources as MutableList<*>).clear()
        }
        Files.delete(jar)
        assertTrue(Files.notExists(jar))
    }

    @Test
    fun `fixed generated factory remains bound to exact store source identities`() {
        val className = "keiyoushi.source.Generated"
        val jar = compileFixture(listOf(SOURCE_ID), className = className)
        val metadata = artifactMetadata().copy(classFQName = className)
        val expected = extensionInfo(listOf(source(SOURCE_ID)))

        val result = ExtensionJarShadowValidator.validate(jar, metadata, expected, emptyMap())
        assertEquals(listOf(ExtensionJarShadowValidator.SourceMetadata(SOURCE_ID, SOURCE_NAME, "all")), result.sources)
        assertThrows(IllegalStateException::class.java) {
            ExtensionJarShadowValidator.validate(
                jar,
                metadata,
                expected.copy(sources = listOf(source(SOURCE_ID + 1))),
                emptyMap(),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            ExtensionJarShadowValidator.validate(jar, metadata, expected, mapOf(SOURCE_ID to "$PACKAGE_NAME.other"))
        }
    }

    @Test
    fun `candidate duplicate source IDs are rejected`() {
        val jar = compileFixture(listOf(SOURCE_ID, SOURCE_ID))

        assertThrows(IllegalStateException::class.java) {
            ExtensionJarShadowValidator.validate(
                jar,
                artifactMetadata(),
                extensionInfo(listOf(source(SOURCE_ID))),
                emptyMap(),
            )
        }
    }

    @Test
    fun `source ID owned by another package is rejected`() {
        val jar = compileFixture(listOf(SOURCE_ID))

        assertThrows(IllegalStateException::class.java) {
            ExtensionJarShadowValidator.validate(
                jar,
                artifactMetadata(),
                extensionInfo(listOf(source(SOURCE_ID))),
                mapOf(SOURCE_ID to "eu.kanade.tachiyomi.extension.en.other"),
            )
        }
    }

    @Test
    fun `runtime source metadata must match immutable store snapshot`() {
        val jar = compileFixture(listOf(SOURCE_ID))
        val expected = extensionInfo(listOf(source(SOURCE_ID).copy(name = "Different")))

        assertThrows(IllegalStateException::class.java) {
            ExtensionJarShadowValidator.validate(jar, artifactMetadata(), expected, emptyMap())
        }
    }

    @Test
    fun `main class resolved from system loader is rejected`() {
        val jar = compileFixture(listOf(SOURCE_ID))
        val packageName = "suwayomi.tachidesk.manga.impl.extension"
        val metadata =
            artifactMetadata().copy(
                packageName = packageName,
                classFQName = "$packageName.ExtensionJarShadowValidatorTest",
            )
        val expected = extensionInfo(listOf(source(SOURCE_ID))).copy(pkgName = packageName)

        assertThrows(IllegalStateException::class.java) {
            ExtensionJarShadowValidator.validate(jar, metadata, expected, emptyMap())
        }
    }

    @Test
    fun `candidate source must implement catalogue source`() {
        val jar = compileFixture(listOf(SOURCE_ID), catalogue = false)

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionJarShadowValidator.validate(
                jar,
                artifactMetadata(),
                extensionInfo(listOf(source(SOURCE_ID))),
                emptyMap(),
            )
        }
    }

    @Test
    fun `source name exceeding installed schema is rejected before execution`() {
        val expected = extensionInfo(listOf(source(SOURCE_ID).copy(name = "n".repeat(129))))

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionJarShadowValidator.validateExpected(expected)
        }
    }

    @Test
    fun `source home URL exceeding installed base-url schema is rejected before execution`() {
        val expected = extensionInfo(listOf(source(SOURCE_ID).copy(homeUrl = "h".repeat(2049))))

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionJarShadowValidator.validateExpected(expected)
        }
    }

    @Test
    fun `aggregate source languages exceeding persistence schema are rejected`() {
        val sources =
            (1L..256L).map { id ->
                source(id).copy(
                    name = "Source $id",
                    lang = "l${id.toString().padStart(30, 'x')}",
                )
            }
        val expected = extensionInfo(sources).copy(lang = "all")
        val result =
            ExtensionJarShadowValidator.Result(
                sources.map { ExtensionJarShadowValidator.SourceMetadata(it.id, it.name, it.lang) },
                extensionLanguage = "all",
            )

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionJarShadowValidator.validateResult(result, expected, emptyMap())
        }
    }

    private fun compileFixture(
        sourceIDs: List<Long>,
        catalogue: Boolean = true,
        className: String = "$PACKAGE_NAME.ShadowFixtureFactory",
    ): Path {
        val sourceRoot = temporaryDirectory.resolve("java-${sourceIDs.size}-${System.nanoTime()}")
        val sourcePath = sourceRoot.resolve("${className.replace('.', '/')}.java")
        val classes = sourceRoot.resolve("classes")
        Files.createDirectories(sourcePath.parent)
        Files.createDirectories(classes)
        Files.writeString(sourcePath, javaFixture(sourceIDs, catalogue, className))

        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Tests require a JDK compiler" }
        val exitCode =
            compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                sourcePath.toString(),
            )
        check(exitCode == 0) { "Could not compile shadow-loader fixture" }

        val jarPath = sourceRoot.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            Files.walk(classes).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .forEach { classFile ->
                        val name = classes.relativize(classFile).toString().replace('\\', '/')
                        jar.putNextEntry(JarEntry(name))
                        Files.copy(classFile, jar)
                        jar.closeEntry()
                    }
            }
        }
        return jarPath
    }

    private fun javaFixture(
        sourceIDs: List<Long>,
        catalogue: Boolean,
        className: String,
    ): String {
        val ids = sourceIDs.joinToString(", ") { "${it}L" }
        val interfaces = if (catalogue) "CatalogueSource.class" else "Source.class"
        return """
            package ${className.substringBeforeLast('.')};

            import eu.kanade.tachiyomi.source.CatalogueSource;
            import eu.kanade.tachiyomi.source.Source;
            import eu.kanade.tachiyomi.source.SourceFactory;
            import java.lang.reflect.Proxy;
            import java.util.ArrayList;
            import java.util.List;

            public final class ${className.substringAfterLast('.')} implements SourceFactory {
                @Override public List<Source> createSources() {
                    long[] ids = new long[] { $ids };
                    List<Source> result = new ArrayList<>();
                    for (long id : ids) {
                        Source source = (Source) Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] { $interfaces },
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "getId": return id;
                                    case "getName": return "$SOURCE_NAME";
                                    case "getLang": return "all";
                                    case "getSupportsLatest": return false;
                                    case "hashCode": return System.identityHashCode(proxy);
                                    case "equals": return proxy == args[0];
                                    case "toString": return "$SOURCE_NAME (ALL)";
                                    default:
                                        Class<?> type = method.getReturnType();
                                        if (type == boolean.class) return false;
                                        if (type == int.class) return 0;
                                        if (type == long.class) return 0L;
                                        return null;
                                }
                            }
                        );
                        result.add(source);
                    }
                    return result;
                }
            }
            """.trimIndent()
    }

    private companion object {
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.fixture"
        const val SOURCE_ID = 1435103006121310663L
        const val SOURCE_NAME = "Fixture"
        const val SIGNING_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

        fun source(id: Long) =
            ExtensionSource(
                id = id,
                name = SOURCE_NAME,
                lang = "all",
                homeUrl = "https://fixture.invalid",
                message = null,
                contentWarning = ContentWarning.SAFE,
            )

        fun extensionInfo(sources: List<ExtensionSource>) =
            ExtensionInfo(
                storeIndexUrl = "https://repo.invalid/index.json",
                name = SOURCE_NAME,
                pkgName = PACKAGE_NAME,
                apkUrl = "https://cdn.invalid/fixture.apk",
                jarUrl = "https://cdn.invalid/fixture.jar",
                iconUrl = "https://cdn.invalid/fixture.png",
                extensionLib = "1.6",
                versionCode = 1,
                versionName = "1.6.1",
                lang = "all",
                contentWarning = ContentWarning.SAFE,
                sources = sources,
            )

        fun artifactMetadata() =
            ExtensionJarArtifactInspector.Metadata(
                packageName = PACKAGE_NAME,
                versionCode = 1,
                versionName = "1.6.1",
                extensionLib = "1.6",
                name = SOURCE_NAME,
                classFQName = "$PACKAGE_NAME.ShadowFixtureFactory",
                contentWarning = ContentWarning.SAFE,
                signature = ExtensionJarVerifier.Result(SIGNING_KEY, 1, 128, emptySet()),
            )
    }
}
