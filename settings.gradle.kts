pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        // GraalVM native build tools publish their plugin marker to Maven Central only
        mavenCentral()
    }
}

plugins {
    // provisions the GraalVM toolchain for the native CLI
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mps-env-gen-gradle-plugin"

// modules live in modules/, project paths stay flat (:core, :cli, :gradle-plugin)
listOf("core", "cli", "gradle-plugin").forEach {
    include(it)
    project(":$it").projectDir = file("modules/$it")
}

//includeBuild("example")
