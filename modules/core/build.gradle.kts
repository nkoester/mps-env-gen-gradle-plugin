plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.maven.publish)
}

description = "Gradle-free core of mps-env-gen: runnable MPS distributions and environment generation"

base.archivesName.set("mps-env-gen-core")

kotlin {
    // the CLI must run on any Java 11+ (MPS itself runs on its own JBR)
    jvmToolchain(11)
    // core runs inside the Gradle plugin, i.e. on the Kotlin stdlib of the consuming Gradle (8.14 embeds 2.0.21);
    // the build itself compiles with Gradle's embedded Kotlin, which kotlin-dsl forces onto all projects
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

java {
    withSourcesJar()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}

// test fixtures are for the other modules of this build only
val javaComponent = components["java"] as AdhocComponentWithVariants
listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach {
    javaComponent.withVariantsFromConfiguration(configurations[it]) { skip() }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "mps-env-gen-core"
            from(components["java"])
        }
    }
}
