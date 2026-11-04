plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Bytecode targets Java 17 (what the extension runtimes accept) regardless of the build JDK.
kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Everything the core needs is provided by the host app (Mihon / Tachimanga) at runtime.
    compileOnly(libs.bundles.host.provided)

    testImplementation(libs.bundles.host.provided)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    // Live network tests only run when explicitly requested: LOA_LIVE=1 ./gradlew :core:test
    environment("LOA_LIVE", System.getenv("LOA_LIVE") ?: "")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = System.getenv("LOA_LIVE") == "1"
    }
}
