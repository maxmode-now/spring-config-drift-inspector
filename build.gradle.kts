plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    id("org.jetbrains.intellij.platform") version "2.18.1" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

allprojects {
    group = providers.gradleProperty("pluginGroup").get()
    version = providers.gradleProperty("pluginVersion").get()
}

subprojects {
    repositories {
        mavenCentral()
    }
}
