plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    alias(libs.plugins.maven.publish)
}

description = "Gradle plugin to generate isolated MPS environments"

// published as before; the folder name is not the artifact id
base.archivesName.set("mps-env-gen-gradle-plugin")

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

gradlePlugin {
    plugins {
        create("MpsEnvGenPlugin") {
            id = "de.itemis.mps.mps-env-gen-gradle-plugin"
            displayName = "Plugin to generate isolated MPS configurations and run environments"
            description = "A plugin that allows you run your MPS project in isolated environments. Works for Linux/Windows/OSX."
            tags = listOf("mps", "environment", "generation")
            implementationClass = "de.itemis.mps.envgen.gradle.MpsEnvironmentGenerationPlugin"
        }
    }
}

publishing {
    publications.withType<MavenPublication>().matching { it.name == "pluginMaven" }.configureEach {
        artifactId = "mps-env-gen-gradle-plugin"
    }
}

tasks.test {
    maxParallelForks = 3
    useJUnitPlatform()
}
