plugins {
    id("org.jetbrains.kotlin.jvm")
    id("application")
    id("com.gradleup.shadow")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(kotlin("stdlib"))
}

application {
    mainClass.set("io.github.configdrift.cli.ConfigDriftCliKt")
}

tasks.shadowJar {
    archiveBaseName.set("config-drift-cli")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
