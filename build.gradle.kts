plugins {
    kotlin(libs.plugins.jvm.gradle.get().pluginId) version libs.plugins.jvm.gradle.get().version.toString()
    alias(libs.plugins.maven.publish)
    `java-gradle-plugin`
    `kotlin-dsl`
    `jvm-test-suite`
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

group = "de.itemis.mps"
version = "0.1-SNAPSHOT"

repositories {
    maven(url = "https://artifacts.itemis.cloud/repository/maven-mps")
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.0")
//    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
}

gradlePlugin {
        plugins.create("lolwat") {
            id = "de.itemis.mps.mps-env-gen-gradle-plugin"
            displayName = "Plugin to generate isolated MPS configurations and run environments"
            description = "A plugin that allows you run your MPS project in isolated environments. Works for Linux/Windows/OSX."
            tags = listOf("mps", "environment", "generation")
            implementationClass = "de.itemis.mps.MpsEnvironmentGenerationPlugin"
        }
}

// write the version to the MANIFEST
tasks.jar {
    manifest {
        attributes("Implementation-Version" to version)
    }
}

tasks.test {
    maxParallelForks = 3
    useJUnitPlatform()
}


publishing {
    repositories {
        mavenLocal()
    }
}
