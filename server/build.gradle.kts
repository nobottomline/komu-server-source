import de.undercouch.gradle.tasks.download.Download
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import suwayomi.gradle.VerifyJavaArtifactTask
import java.security.MessageDigest
import java.time.Instant

plugins {
    id(
        libs.plugins.kotlin.jvm
            .get()
            .pluginId,
    )
    id(
        libs.plugins.kotlin.serialization
            .get()
            .pluginId,
    )
    id(
        libs.plugins.ktlint
            .get()
            .pluginId,
    )
    application
    alias(libs.plugins.shadowjar)
    id(
        libs.plugins.buildconfig
            .get()
            .pluginId,
    )
    id(
        libs.plugins.jte
            .get()
            .pluginId,
    )
}

val legacyH2Migration =
    configurations.create("legacyH2Migration") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }

dependencies {
    // Shared
    implementation(libs.bundles.shared)
    testImplementation(libs.bundles.sharedTest)

    // OkHttp
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)

    // Javalin api
    implementation(libs.bundles.javalin)
    implementation(libs.bundles.jackson)

    // GraphQL
    implementation(libs.graphql.kotlin.server)
    implementation(libs.graphql.kotlin.scheme)
    implementation(libs.graphql.java.scalars)

    // Exposed ORM
    implementation(libs.bundles.exposed)
    implementation(libs.postgres)
    implementation(libs.h2)
    implementation(libs.hikaricp)
    add(legacyH2Migration.name, "com.h2database:h2:1.4.200")

    // Exposed Migrations
    implementation(libs.exposed.migrations)

    // tray icon
    implementation(libs.bundles.systemtray)

    // dependencies of Mihon (Tachiyomi) extensions, some are duplicate, keeping it here for reference
    implementation(libs.injekt)
    implementation(libs.okhttp.core)
    implementation(libs.rxjava)
    implementation(libs.jsoup)

    // ComicInfo
    implementation(libs.serialization.xml.core)
    implementation(libs.serialization.xml)

    // Sort
    implementation(libs.sort)

    // asm for ByteCodeEditor(fixing SimpleDateFormat) (must match Dex2Jar version)
    implementation(libs.asm)

    // Disk & File
    implementation(libs.cache4k)
    implementation(libs.zip4j)
    implementation(libs.commonscompress)
    implementation(libs.junrar)

    // AES/CBC/PKCS7Padding Cypher provider for zh.copymanga
    implementation(libs.bouncycastle)

    // AndroidCompat
    implementation(projects.androidCompat)
    implementation(projects.androidCompat.config)

    // i18n
    implementation(projects.server.i18n)

    // Settings module
    implementation(projects.server.serverConfig)

    // uncomment to test extensions directly
//    implementation(fileTree("lib/"))
    implementation(kotlin("script-runtime"))

    testImplementation(libs.mockk)

    implementation(libs.cron4j)

    implementation(libs.cronUtils)

    implementation(libs.jwt)

    compileOnly(libs.kte)
}

jte {
    generate()
}

application {
    applicationDefaultJvmArgs =
        listOf(
            "-Djunrar.extractor.thread-keep-alive-seconds=30",
        )
    mainClass.set(MainClass)
}

sourceSets {
    main {
        resources {
            srcDir("src/main/resources")
            srcDir("build/generated/src/main/resources")
        }
        kotlin {
            srcDir("build/generated/src/main/kotlin")
        }
    }
    test {
        resources {
            srcDir("build/generated/src/test/resources")
        }
    }
}

buildConfig {
    className("BuildConfig")
    packageName("suwayomi.tachidesk.server.generated")

    useKotlinOutput()

    fun quoteWrap(obj: Any): String = """"$obj""""

    buildConfigField("String", "NAME", quoteWrap(rootProject.name))
    buildConfigField("String", "VERSION", quoteWrap(getTachideskVersion()))
    buildConfigField("String", "REVISION", quoteWrap(getTachideskRevision()))
    buildConfigField("String", "BUILD_TYPE", quoteWrap(if (System.getenv("ProductBuildType") == "Stable") "Stable" else "Preview"))
    buildConfigField("long", "BUILD_TIME", Instant.now().epochSecond.toString())

    buildConfigField("String", "WEBUI_TAG", quoteWrap(webUIRevisionTag))

    buildConfigField("String", "GITHUB", quoteWrap("https://github.com/Suwayomi/Suwayomi-Server"))
    buildConfigField("String", "DISCORD", quoteWrap("https://discord.gg/DDZdqZWaHA"))
    buildConfigField("String", "JCEF_VERSION", quoteWrap(libs.versions.jcef.get()))
    buildConfigField("String", "JCEF_JBR_RELEASE", quoteWrap(webviewJbrRelease))
}

tasks {
    val packagedServer = named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    val legacyH2Sha256 = "3ad9ac4b6aae9cd9d3ac1c447465e1ed06019b851b893dd6a8d76ddb6d85bca6"
    val verifyLegacyH2MigrationArtifact =
        register("verifyLegacyH2MigrationArtifact") {
            inputs.files(legacyH2Migration)
            doLast {
                val artifact = legacyH2Migration.singleFile
                val digest = MessageDigest.getInstance("SHA-256")
                artifact.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        digest.update(buffer, 0, read)
                    }
                }
                val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                check(actual == legacyH2Sha256) {
                    "Unexpected SHA-256 for pinned H2 1.4.200 migration artifact"
                }
            }
        }

    shadowJar {
        isZip64 = true
        manifest {
            attributes(
                "Main-Class" to MainClass,
                "Implementation-Title" to rootProject.name,
                "Implementation-Vendor" to "The Suwayomi Project",
                "Specification-Version" to getTachideskVersion(),
                "Implementation-Version" to getTachideskRevision(),
                "Multi-Release" to true, // needed for polyglot
                "X-JBR-Release" to webviewJbrRelease,
            )
        }
        archiveBaseName.set(rootProject.name)
        archiveVersion.set(getTachideskVersion())
        archiveClassifier.set("")
        destinationDirectory.set(File("$rootDir/server/build"))
        // The embedded server is launched as one classpath JAR. Module
        // descriptors from individual dependencies become false and
        // order-dependent once Shadow merges those dependencies together.
        exclude("module-info.class")
        exclude("META-INF/versions/**/module-info.class")
        mergeServiceFiles()
    }

    val verifyEmbeddedJava17Artifact =
        register<VerifyJavaArtifactTask>("verifyEmbeddedJava17Artifact") {
            group = "verification"
            description = "Verifies that every class visible to embedded Java 17 uses compatible bytecode"
            dependsOn(packagedServer)
            archiveFile.set(packagedServer.flatMap { it.archiveFile })
            targetJava.set(libs.versions.jvmTarget.map(String::toInt))
        }

    check {
        dependsOn(verifyEmbeddedJava17Artifact)
    }

    test {
        useJUnitPlatform {
            if (!project.hasProperty("masstest")) {
                exclude("**/masstest/*")
            }
        }
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }

    withType<KotlinJvmCompile> {
        compilerOptions {
            freeCompilerArgs.add(
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            )
        }
    }

    named<Copy>("processResources") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mustRunAfter("downloadWebUI")
        dependsOn(verifyLegacyH2MigrationArtifact)
        from(legacyH2Migration) {
            into("h2-migration")
            rename { "h2-1.4.200.bin" }
        }
    }

    register<Download>("downloadWebUI") {
        src("https://github.com/Suwayomi/Suwayomi-WebUI-preview/releases/download/$webUIRevisionTag/Suwayomi-WebUI-$webUIRevisionTag.zip")
        dest("src/main/resources/WebUI.zip")

        fun shouldOverwrite(): Boolean {
            val zipPath = project.projectDir.absolutePath + "/src/main/resources/WebUI.zip"
            val zipFile = net.lingala.zip4j.ZipFile(zipPath)

            var shouldOverwrite = true
            if (zipFile.isValidZipFile) {
                val zipRevision =
                    zipFile.getInputStream(zipFile.getFileHeader("revision")).bufferedReader().use {
                        it.readText().trim()
                    }

                if (zipRevision == webUIRevisionTag) {
                    shouldOverwrite = false
                }
            }

            return shouldOverwrite
        }

        overwrite(shouldOverwrite())
    }

    register("runElectron") {
        group = "application"
        finalizedBy(run)
        doFirst {
            application.applicationDefaultJvmArgs =
                listOf(
                    "-Dsuwayomi.tachidesk.config.server.webUIInterface=electron",
                    // Change this to the installed electron application
                    "-Dsuwayomi.tachidesk.config.server.electronPath=/usr/bin/electron",
                )
        }
    }

    runKtlintCheckOverMainSourceSet {
        mustRunAfter(generateJte)
    }

    compileKotlin {
        dependsOn(":server:server-config-generate:generateSettings")
    }

    processResources {
        dependsOn(":server:server-config-generate:generateSettings")
    }

    processTestResources {
        dependsOn(":server:server-config-generate:generateSettings")
    }
}
