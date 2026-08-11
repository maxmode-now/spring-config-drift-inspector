plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Root gradle.properties sets kotlin.stdlib.default.dependency=false for the IntelliJ
    // plugin (platform ships its own stdlib). Core is a plain JVM library and needs it explicitly.
    api(kotlin("stdlib"))
    api("org.yaml:snakeyaml:2.4")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    systemProperty(
        "configdrift.fixture",
        rootProject.file("plugin/testFixtures/sample-spring-project").absolutePath,
    )
}
