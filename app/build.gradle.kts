import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.process.JavaForkOptions

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.example.filemanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.filemanager"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.0")
    // Keep tracing version consistent across app + androidTest classpaths (AGP uses consistent resolution).
    implementation("androidx.tracing:tracing:1.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose UI tests (Instrumentation)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Compose UI tests (Robolectric/JVM)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
    testImplementation("org.hamcrest:hamcrest:2.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Network protocol libraries (dependency-only for now)
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("commons-net:commons-net:3.10.0")

    // Classic Material theme resources (for manifest theme)
    implementation("com.google.android.material:material:1.12.0")

    // Paparazzi snapshot tests (JVM)
    testImplementation("app.cash.paparazzi:paparazzi:1.3.3")

    // Robolectric (pure JVM, runs Android framework code without emulator)
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core-ktx:1.6.1")

    // Mock HTTP server for JVM unit tests
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Mock frameworks / protocol test servers (pure JVM)
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.mockftpserver:MockFtpServer:3.1.0")

    // Instrumentation E2E needs an on-device FTP server (avoid depending on host/adb reverse).
    androidTestImplementation("org.mockftpserver:MockFtpServer:3.1.0")

    // Fix Paparazzi + AGP toolchain Guava variant mismatch (avoid android-only guava on JVM).
    testImplementation("com.google.guava:guava:33.0.0-jre")

    testImplementation("junit:junit:4.13.2") {
        // 避免 Espresso/Compose 测试在运行时加载到旧版 hamcrest-core 导致 NoSuchMethodError。
        exclude(group = "org.hamcrest", module = "hamcrest-core")
    }
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// Ensure unit tests run with the standard JVM environment (so Gradle picks JRE variants of deps like Guava).
afterEvaluate {
    configurations.matching { it.name.contains("UnitTest", ignoreCase = true) }.configureEach {
        attributes.attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
    }

    // Paparazzi resolves Android platform jars via ANDROID_HOME / ANDROID_SDK_ROOT.
    // Propagate the AGP-detected SDK directory into forked JVM processes (tests + paparazzi tasks).
    val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile
    tasks.withType<Test>().configureEach {
        environment("ANDROID_HOME", sdkDir.absolutePath)
        environment("ANDROID_SDK_ROOT", sdkDir.absolutePath)
    }
    tasks.matching {
        it.name.startsWith("recordPaparazzi", ignoreCase = true) ||
            it.name.startsWith("verifyPaparazzi", ignoreCase = true) ||
            it.name.startsWith("renderPaparazzi", ignoreCase = true)
    }.configureEach {
        (this as? JavaForkOptions)?.apply {
            environment("ANDROID_HOME", sdkDir.absolutePath)
            environment("ANDROID_SDK_ROOT", sdkDir.absolutePath)
        }
    }
}
