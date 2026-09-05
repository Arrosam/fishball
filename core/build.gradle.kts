plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * Pure Kotlin/JVM. No Android dependency, deliberately — spec Phase 1 requires the trust
 * engine, memory and TTL logic to be testable without an emulator. Anything needing Android
 * (SQLite, Context) sits behind an interface implemented in :app.
 */

/*
 * Target Java 17 bytecode from whatever JDK is running, rather than demanding a JDK 17
 * toolchain. `jvmToolchain(17)` makes the build fail outright on a machine that only has a
 * newer JDK, and it buys nothing here: :app already compiles against 17 the same way, so both
 * modules emit the same class file version without a second JDK having to exist.
 */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        // The tier list ships as a resource, so tests run against the data that actually
        // ships rather than against a fixture written to suit the tests.
        resources.srcDir(rootProject.file("data"))
    }
}

/*
 * `api`, not `implementation`, and deliberately.
 *
 * :core does not merely use these - it exposes them. A tool schema is a kotlinx JsonObject, and
 * the gateways take an HttpClient so a test can hand them a fake transport. Both types appear
 * in signatures :app has to resolve, so hiding them here only produces "cannot access class"
 * at the call site. If that ever stops being true, tighten it back.
 */
dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.okhttp)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

/*
 * Mints a custom-provider activation code.
 *
 *     ./gradlew :core:mintProfile -Purl=... -Pkey=... -Psys=... -Pflash=... -Ppro=... -Psearch=...
 *
 * Optional: -Pembed= -Prerank= -Pasr= -PsearchKey=. Omitting one says the provider does not offer
 * it, which has consequences the tool prints back - see MintProfile.
 *
 * On the test runtime classpath because the tool lives in the test source set: it is a workbench
 * tool and must not be reachable from the shipped app.
 */
// Read here, at configuration time. Reaching for `project` from inside the task's execution is
// what the configuration cache forbids, and it fails the build rather than degrading.
val mintFlags = listOf(
    "url", "key", "sys", "flash", "pro", "search", "embed", "rerank", "asr", "searchKey",
).mapNotNull { name -> (project.findProperty(name) as? String)?.let { "--$name=$it" } }

tasks.register<JavaExec>("mintProfile") {
    group = "fishball"
    description = "Mint a custom-provider activation code (fb1.…)."
    mainClass.set("org.areel.fishball.core.config.MintProfile")
    classpath = sourceSets["test"].runtimeClasspath
    args = mintFlags
}
