import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.zip4j)
    testImplementation(kotlin("test"))
}

java {
    val javaVersion = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
        freeCompilerArgs.add("-Xjdk-release=${libs.versions.jvmTarget.get()}")
    }
}

tasks.withType<JavaCompile> {
    options.release = libs.versions.jvmTarget.get().toInt()
}

tasks.test {
    useJUnitPlatform()
}
