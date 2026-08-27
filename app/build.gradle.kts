plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

fun git(vararg args: String): String = try {
    providers.exec {
        workingDir = rootDir
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    ""
}

// Version identity comes from git tags, not a hardcoded string. `git describe`
// yields "v0.3.0-fuel-calibration" on a release tag, and
// "v0.3.0-fuel-calibration-1-g4798128-dirty" for anything built past it.
val gitDescribe = git("describe", "--tags", "--dirty", "--always").ifEmpty { "0.0.0-nogit" }
val gitLastTag = git("describe", "--tags", "--abbrev=0")
val gitSha = git("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }
val gitBranch = git("rev-parse", "--abbrev-ref", "HEAD").ifEmpty { "unknown" }
val gitDate = git("log", "-1", "--format=%cd", "--date=short").ifEmpty { "unknown" }
val gitCommitCount = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1

// A build is a "release" only if HEAD sits exactly on a tag with no local edits.
val isReleaseBuild = gitLastTag.isNotEmpty() && gitDescribe == gitLastTag

// One-sentence summary for the Messages welcome banner. On a release build it is
// the first line of the matching release-notes file (the same file that becomes
// the GitHub release body). Otherwise fall back to git, since a work-in-progress
// build has no human-written summary yet.
val whatsNewLine: String = run {
    val notes = rootProject.file("release-notes/$gitLastTag.md")
    if (isReleaseBuild && notes.exists()) {
        notes.readLines().firstOrNull { it.isNotBlank() }.orEmpty()
    } else {
        val ahead = if (gitLastTag.isEmpty()) 0
                    else git("rev-list", "--count", "$gitLastTag..HEAD").toIntOrNull() ?: 0
        val subjects = git("log", "-2", "--format=%s")
            .lines().filter { it.isNotBlank() }.joinToString(" | ")
        val dirtyNote = if (gitDescribe.endsWith("-dirty")) " + uncommitted changes" else ""
        "Dev build, $ahead commit(s) past $gitLastTag$dirtyNote: $subjects"
    }
}

fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

android {
    namespace = "com.example.dash22b"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dash22b"
        minSdk = 24
        targetSdk = 34
        versionCode = gitCommitCount
        versionName = gitDescribe.removePrefix("v")

        buildConfigField("String", "GIT_SHA", "\"${esc(gitSha)}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${esc(gitBranch)}\"")
        buildConfigField("String", "GIT_DATE", "\"${esc(gitDate)}\"")
        buildConfigField("String", "WHATS_NEW", "\"${esc(whatsNewLine)}\"")
        buildConfigField("boolean", "IS_RELEASE_BUILD", isReleaseBuild.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        getByName("test").assets.srcDirs(files("src/main/assets"))
        getByName("androidTest").assets.srcDirs(files("src/main/assets"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.jakewharton.timber)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":usbSerialForAndroid"))
}
