plugins {
    kotlin(libs.plugins.jvm.gradle.get().pluginId) version libs.plugins.jvm.gradle.get().version.toString()
    alias(libs.plugins.maven.publish)
    `java-gradle-plugin`
    `kotlin-dsl`
}
kotlin {
    jvmToolchain(17)
}

group = "de.itemis.mps"
version = "1.0-SNAPSHOT"

repositories {
    maven(url = "https://artifacts.itemis.cloud/repository/maven-mps")
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    val modelSync by plugins.creating {
        id = "de.itemis.mps.environment-generator"
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
    useJUnitPlatform()
}


publishing {
    repositories {
        mavenLocal()
    }
}
