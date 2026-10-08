// Root build.gradle.kts
plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
