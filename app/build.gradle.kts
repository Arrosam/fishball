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
        versionCode = 3
        // Alpha, and named like one. The app works end to end; what it has not had is a second
        // pair of hands using it for a week, which is the only thing that earns a 1.
        versionName = "0.3a"

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

dependencies {
    // Trust engine, memory, sessions, the turn state machine. Android-free by design.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // No material-icons dependency: every icon is a hand-drawn VectorDrawable in
    // res/drawable, matching the 24-grid in docs/06-design-directions.md section E.
    implementation(libs.kotlinx.coroutines.android)

    // Not yet needed by the frontend dummy, and added back when the UI is wired to :core:
    //   libs.androidx.datastore.preferences  — persisting the login key (§1)
    //   libs.koog.agents                     — the LLM driver around TurnEngine
    // Ktor and kotlinx-serialization live in :core with the SearXNG gateway.
}
