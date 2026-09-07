import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

/*
 * Release signing.
 *
 * Read from keystore.properties, which is not in version control, and absent for anyone who
 * clones this. That case builds unsigned rather than failing: a contributor should be able to
 * compile the app without holding the key that ships it.
 */
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "org.areel.fishball"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.areel.fishball"
        // API 26 is the floor. It was chosen for an FTS5 index that was never built; what
        // holds it there now is the adaptive launcher icon, which is mipmap-anydpi-v26 only.
        minSdk = 26
        targetSdk = 35
        // What the update check compares. Monotonic, and never reused: the name is for the
        // person reading the modal, this is the only thing that decides.
        versionCode = 9
        // Still short of a 1: the app works end to end, and what it has not had is a second pair
        // of hands using it for a week. A patch on 0.3.0a rather than a minor - an answer can
        // show a picture it found and a plate can be copied or quoted, which is more for the
        // reader to do with an answer, not a different agent underneath. 3 was built and
        // installed but never published and 7 was tagged but not released, so the published
        // sequence runs 0.2a (2), 0.2.0 (4), 0.2.0a (5), 0.2.1a (6), 0.3.0a (8), and this (9).
        versionName = "0.3.1a"

        buildConfigField(
            "String",
            "SEARXNG_BASE_URL",
            "\"${project.findProperty("fishball.searxng.baseUrl") ?: "https://search.areel.org"}\"",
        )

        // Where the app looks to find out whether it is out of date. The official site
        // publishes this; see the fishball page under areel.org.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"${project.findProperty("fishball.update.manifestUrl") ?: "https://areel.org/fishball/latest.json"}\"",
        )
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/io.netty.versions.properties",
        )
    }
}

/*
 * Unit tests could not start on this machine, and the cause is not in this project.
 *
 * PATH here carries a stray double quote - `...\jdk8u282-b08\bin";C:\Users\...`. The JVM folds
 * PATH into `java.library.path` at startup, so the daemon's copy carries the quote too; the
 * Android plugin reads that property when it launches a test worker, appends the jniLibs
 * directories, and passes the result as `-Djava.library.path=...`. The unbalanced quote then
 * breaks the quoting of the rest of the command line and a bare word further along is taken for
 * the main class - the JVM reports `ClassNotFoundException: VS`, the middle word of
 * `Microsoft VS Code`, and the task dies before one test is loaded. Removing that entry from
 * PATH only moves the error to `Files`, from `Program Files`, which is how the quote was found.
 *
 * Two things that look like the fix and are not: setting `environment("PATH", ...)` on the task
 * changes the worker's environment, but the value was taken from the daemon; and setting the
 * system property in `doFirst` is overwritten, because the plugin writes it later.
 *
 * So it is cleaned where it is read from, at configuration time, and it is a no-op on a machine
 * whose PATH is well formed. The real repair is to take the quote out of PATH - this only means
 * the tests do not wait for that.
 */
System.getProperty("java.library.path")?.takeIf { it.contains('"') }?.let {
    System.setProperty("java.library.path", it.replace("\"", ""))
}

dependencies {
    // Trust engine, memory, sessions, the turn state machine. Android-free by design.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    /*
     * Unit tests for :app, which had none.
     *
     * Almost everything here is Compose and wants a device, and that is still true - what this
     * buys is the handful of pure functions that are not: the Markdown an answer arrives
     * wearing, and anything else that turns a string into another string. Those are exactly the
     * pieces where a mistake reaches the reader silently.
     */
    testImplementation(kotlin("test"))

    // No material-icons dependency: every icon is a hand-drawn VectorDrawable in
    // res/drawable, matching the 24-grid in docs/06-design-directions.md section E.
    implementation(libs.kotlinx.coroutines.android)

    /*
     * The one picture an answer might carry, off a page the agent read.
     *
     * The rule above still holds - nothing decorative comes from a library. This is not an icon
     * set: it is fetching, downsampling and caching a photograph from somebody else's server
     * inside a scrolling list, which is three problems that are only easy until you have shipped
     * them. See `Markdown.Piece.Picture` and `AnswerImage`.
     */
    implementation(libs.coil.compose)

    // Not yet needed by the frontend dummy, and added back when the UI is wired to :core:
    //   libs.androidx.datastore.preferences  — persisting the login key (§1)
    //   libs.koog.agents                     — the LLM driver around TurnEngine
    // Ktor and kotlinx-serialization live in :core with the SearXNG gateway.
}
