// buildSrc puts AGP and the Kotlin plugins on the build classpath once, with the versions from
// gradle/libs.versions.toml; projects then apply them by id without a version.
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle)
    implementation(libs.apksig)
    implementation(libs.kotlin.gradle)
    implementation(libs.kotlin.serialization.gradle)
}
