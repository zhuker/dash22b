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
// Commits since the last tag; everything since the root when there is no tag at all.
val gitCommitsSinceTag =
    (if (gitLastTag.isEmpty()) git("rev-list", "--count", "HEAD")
     else git("rev-list", "--count", "$gitLastTag..HEAD")).toIntOrNull() ?: 0

// versionCode is derived from the tag, not from the total commit count. Android refuses
// to install a lower versionCode over a higher one, and the commit count is not
// monotonic across branches: squash-merging a branch collapses its commits, so v0.6.0
// on main (62 commits) came out lower than the branch build it replaced (64), and the
// phone rejected the release as "package appears to be invalid".
//
//   major * 100_000_000 + minor * 1_000_000 + patch * 10_000 + commits since the tag
//
// v0.6.0 is 6_000_000; a dev build 7 commits past v0.5.0 is 5_000_007. Any release
// outranks every build made before its tag, however the branches got merged. The
// ceilings keep the fields from overlapping and stay under Android's 2_100_000_000.
val versionCodeFromTag: Int = run {
    val m = Regex("""^v(\d+)\.(\d+)\.(\d+)""").find(gitLastTag)
    val (major, minor, patch) = m?.destructured?.toList()?.map { it.toInt() } ?: listOf(0, 0, 0)
    check(major <= 20 && minor <= 99 && patch <= 99) {
        "tag $gitLastTag does not fit the versionCode scheme (major <= 20, minor/patch <= 99)"
    }
    major * 100_000_000 + minor * 1_000_000 + patch * 10_000 + gitCommitsSinceTag.coerceAtMost(9_999)
}

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
        val ahead = if (gitLastTag.isEmpty()) 0 else gitCommitsSinceTag
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
        versionCode = versionCodeFromTag
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
