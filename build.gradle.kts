// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
}

// usb-serial-for-android is a pinned upstream checkout (mik3y, v3.7.2), so its
// build files must stay untouched -- a local commit in that submodule could never
// be pushed, and the pointer would dangle for anyone else cloning this repo.
// AGP 8 stopped generating BuildConfig by default and the library needs it, so
// turn it back on from here instead of editing the submodule.
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension> {
            buildFeatures.buildConfig = true
        }
    }
}
