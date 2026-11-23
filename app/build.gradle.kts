import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.mangareader"
    compileSdk = 36

    defaultConfig {
        // Published ID (first release 0.1.0). The Kotlin namespace stays dev.mangareader.
        applicationId = "io.github.mhilmisyarif.folium"
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    // Release signing from keystore.properties (not in git; back it up — every update must use the same key).
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // No R8: kotatsu-parsers finds parsers and models by reflection.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true // parsers use java.time on minSdk 23
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += listOf("META-INF/README.md", "META-INF/NOTICE.md") } }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("org.koitharu.kotatsu.parsers.InternalParsersApi")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Same parser build as Kotatsu 9.4.1 (gradle/libs.versions.toml: parsers = "4d1e521aef"); GPL-3.0.
    implementation("com.github.KotatsuApp:kotatsu-parsers:4d1e521aef") { exclude(group = "org.json", module = "json") }
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("io.coil-kt.coil3:coil:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar's org.json is a stub on the JVM
    // Instrumented tests (DB migration on a device/emulator); test APK only.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
