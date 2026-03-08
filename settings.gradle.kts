pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Dash22b"
include(":app")
include(":usbSerialForAndroid")
project(":usbSerialForAndroid").projectDir = file("usb-serial-for-android/usbSerialForAndroid")
