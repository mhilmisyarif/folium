pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Kotatsu's parser library (all Kotatsu sources) is published only on JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.KotatsuApp") } }
    }
}
rootProject.name = "manga-reader"
include(":app")
