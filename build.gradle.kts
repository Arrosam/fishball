/*
 * Every plugin version is resolved here once, with `apply false`, and subprojects apply them
 * without a version. kotlin-android and kotlin-jvm ship from the same artifact, so declaring a
 * version in a subproject for one while the root has already put the other on the classpath
 * fails with "already on the classpath with an unknown version".
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
