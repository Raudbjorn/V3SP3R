import schema.GenerateExecuteCommandSchemaTask
import schema.VerifyExecuteCommandSchemaTask
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load signing properties from local.properties (not checked into git)
val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

// Select a complete signing identity from one source; never mix credentials.
val signingPropertyNames = listOf(
    "RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"
)
val environmentSigning = signingPropertyNames.associateWith { name ->
    providers.environmentVariable(name).orNull
}
val useEnvironmentSigning = environmentSigning.values.any { it != null }
val signingSource = if (useEnvironmentSigning) "environment" else "local.properties"
val selectedSigning = if (useEnvironmentSigning) environmentSigning else {
    signingPropertyNames.associateWith { localProps.getProperty(it) }
}
val releaseSigning = selectedSigning.mapValues { (_, value) ->
    value?.takeIf { it.isNotBlank() }
}
val hasSigningConfig = selectedSigning.values.any { it != null }
if (hasSigningConfig) {
    val missing = signingPropertyNames.filter { releaseSigning[it] == null }
    require(missing.isEmpty()) {
        "Incomplete release signing configuration from $signingSource. Missing: ${missing.joinToString()}. " +
            "Set all four RELEASE_* values or remove them to build an unsigned APK."
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.vesper.flipper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vesper.flipper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                // Relative paths retain the existing app/ directory convention.
                storeFile = file(releaseSigning.getValue("RELEASE_STORE_FILE")!!)
                storePassword = releaseSigning.getValue("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds: no minification, fast iteration
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("net.zetetic:sqlcipher-android:4.10.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Compose BOM & UI
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Coil (image loading + video frame thumbnails)
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-video:2.5.0")

    // Accompanist
    implementation("com.google.accompanist:accompanist-drawablepainter:0.32.0")

    // USB Serial
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    // Security / Encrypted SharedPrefs
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Protobuf (Flipper device protocol)
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    // Diff utils
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
}

// Schema contract generated from the supported baseline actions.
// ────────────────────────────────
// Regenerate: ./gradlew :app:generateExecuteCommandSchema
// Verify in sync (for CI): ./gradlew :app:verifyExecuteCommandSchema

private val commandKt = rootProject.file(
    "app/src/main/java/com/vesper/flipper/domain/model/Command.kt"
)
private val committedSchema = rootProject.file("docs/execute_command_schema.json")

tasks.register<GenerateExecuteCommandSchemaTask>("generateExecuteCommandSchema") {
    group = "vesper"
    description = "Regenerate docs/execute_command_schema.json from CommandAction + arg-shape spec."
    commandKtFile.set(commandKt)
    outputFile.set(committedSchema)
}

tasks.register<VerifyExecuteCommandSchemaTask>("verifyExecuteCommandSchema") {
    group = "verification"
    description = "Fail if docs/execute_command_schema.json is out of sync with CommandAction."
    commandKtFile.set(commandKt)
    committedFile.set(committedSchema)
    // Verify reads the same file generate writes. When both tasks run in one invocation,
    // Gradle rightly demands an explicit ordering — verify checks the *committed* file,
    // so it should always run before generate would rewrite it.

}

tasks.named("generateExecuteCommandSchema") { mustRunAfter("verifyExecuteCommandSchema") }
tasks.named("check") { dependsOn("verifyExecuteCommandSchema") }

tasks.named("preBuild") { dependsOn("verifyExecuteCommandSchema") }
