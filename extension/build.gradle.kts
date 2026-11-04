import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** Bump on every release. The full version is "<libVersion>.<extVersionCode>" (e.g. 1.6.1). */
val extVersionCode = 7
val libVersion = "1.6"
val extName = "Library of Alexandria"
val extLang = "en"
/**
 * Until 1.6.3 the source was published as lang "all"; Mihon derives the source id from "name/lang/versionId",
 * so the id is pinned to that original value to keep users' libraries, settings and cache attached.
 */
val sourceId = computeSourceId(extName, "all")
val pkgSuffix = "all.libraryofalexandria"

val signingKey = rootProject.file("signingkey.jks")

android {
    namespace = "eu.kanade.tachiyomi.extension.$pkgSuffix"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.$pkgSuffix"
        minSdk = 26
        targetSdk = 37
        versionCode = libVersion.split(".").joinToString("") { it.padStart(2, '0') }.toInt() * 1000 + extVersionCode
        versionName = "$libVersion.$extVersionCode"
        manifestPlaceholders["extName"] = extName
        manifestPlaceholders["extClass"] = "eu.kanade.tachiyomi.extension.$pkgSuffix.LibraryOfAlexandria"
        manifestPlaceholders["extLib"] = libVersion
        buildConfigField("String", "VERSION", "\"$libVersion.$extVersionCode\"")
        buildConfigField("long", "SOURCE_ID", "${sourceId}L")
    }

    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        res.directories.clear()
        res.directories.add("res")
    }

    signingConfigs {
        create("release") {
            storeFile = signingKey
            storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ALIAS").orNull
            keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            // Without the real key (local builds) fall back to the debug key so the APK is still installable for testing.
            signingConfig = signingConfigs.getByName(if (signingKey.exists()) "release" else "debug")
            isMinifyEnabled = false
            @Suppress("UnstableApiUsage")
            vcsInfo.include = false
        }
    }

    buildFeatures { buildConfig = true }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    packaging { resources.excludes += listOf("kotlin-tooling-metadata.json", "META-INF/*.version") }
    lint { checkReleaseBuilds = false; abortOnError = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

val provided: Configuration by configurations.creating { isCanBeResolved = true; isCanBeConsumed = false }

dependencies {
    implementation(project(":core"))
    compileOnly(libs.tachiyomi.lib)
    compileOnly(libs.injekt.core)
    compileOnly(libs.bundles.host.provided)
    provided(libs.bundles.host.provided)
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val fileBase = "tachiyomi-$pkgSuffix-v$libVersion.$extVersionCode"
        variant.outputs.forEach { output ->
            @Suppress("UnstableApiUsage")
            output.outputFileName.set("$fileBase.apk")
        }

        val createJar = tasks.register<CreateExtensionJarTask>("createReleaseExtensionJar") {
            providedClasspath.from(provided)
            manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            outputJar.set(layout.buildDirectory.file("intermediates/extension_jar/unsigned.jar"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(createJar)
            .toGet(ScopedArtifact.CLASSES, CreateExtensionJarTask::jars, CreateExtensionJarTask::dirs)

        val signJar = tasks.register<SignExtensionJarTask>("signReleaseExtensionJar") {
            inputJar.set(createJar.flatMap { it.outputJar })
            if (signingKey.exists()) keystore.from(signingKey)
            storePassword.set(providers.environmentVariable("KEY_STORE_PASSWORD").orElse(""))
            keyAlias.set(providers.environmentVariable("ALIAS").orElse(""))
            keyPassword.set(providers.environmentVariable("KEY_PASSWORD").orElse(""))
            minSdkVersion.set(26)
            outputJar.set(layout.buildDirectory.file("outputs/jar/release/$fileBase.jar"))
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(signJar) }
    }
}

/** Source id exactly as Mihon computes it (MD5 of "name/lang/versionId"), for the repository index. */
fun computeSourceId(name: String, lang: String, versionId: Int = 1): Long {
    val bytes = MessageDigest.getInstance("MD5").digest("${name.lowercase()}/$lang/$versionId".toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff }.reduce { acc, l -> (acc shl 8) or l } and Long.MAX_VALUE
}

/** Metadata consumed by scripts/publish_repo.py when generating the repository index. */
val writeExtensionMetadata by tasks.registering {
    val out = layout.buildDirectory.file("outputs/extension-metadata.json")
    outputs.file(out)
    doLast {
        val id = sourceId
        out.get().asFile.writeText(
            """
            {
              "name": "$extName",
              "pkg": "eu.kanade.tachiyomi.extension.$pkgSuffix",
              "lang": "$extLang",
              "code": ${libVersion.split(".").joinToString("") { it.padStart(2, '0') }.toInt() * 1000 + extVersionCode},
              "version": "$libVersion.$extVersionCode",
              "libVersion": "$libVersion",
              "nsfw": 1,
              "contentWarning": 2,
              "apk": "tachiyomi-$pkgSuffix-v$libVersion.$extVersionCode.apk",
              "jar": "tachiyomi-$pkgSuffix-v$libVersion.$extVersionCode.jar",
              "sources": [ { "name": "$extName", "lang": "$extLang", "id": $id, "baseUrl": "https://mangabaka.org" } ]
            }
            """.trimIndent(),
        )
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(writeExtensionMetadata) }
