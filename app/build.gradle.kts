plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val repoRoot = rootProject.projectDir
val versionProperties = Properties().apply {
    val file = File(repoRoot, "version.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}
val buildNumberFile = File(repoRoot, "build-number.txt")
val buildNumber = if (buildNumberFile.exists()) {
    buildNumberFile.readText().trim().toInt()
} else {
    1
}

android {
    namespace = "jp.viastrasse.cabinet"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.viastrasse.cabinet"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = versionProperties.getProperty("VERSION_NAME", "0.1.1")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// CabinetColorsSyncTest は colors.xml をファイルとして読む。
// 入力として宣言しないと colors.xml だけを変えたときに UP-TO-DATE でスキップされ、
// 同期ずれを検知できなくなる。
tasks.withType<Test>().configureEach {
    inputs.file(File(projectDir, "src/main/res/values/colors.xml"))
        .withPropertyName("cabinetColorsXml")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Exec>("buildRustAndroidDebug") {
    workingDir = repoRoot
    commandLine(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        "${projectDir}/src/main/jniLibs",
        "build",
        "-p",
        "cabinet-android-ffi",
    )
}

tasks.register<Exec>("buildRustAndroidRelease") {
    workingDir = repoRoot
    commandLine(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        "${projectDir}/src/main/jniLibs",
        "build",
        "-p",
        "cabinet-android-ffi",
        "--release",
    )
}

afterEvaluate {
    tasks.named("preDebugBuild") {
        dependsOn("buildRustAndroidDebug")
    }
    tasks.named("preReleaseBuild") {
        dependsOn("buildRustAndroidRelease")
    }
}

dependencies {
    implementation(files("libs/viastrasse-family-ui-release.aar"))
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
}
