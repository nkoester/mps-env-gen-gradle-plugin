import java.security.MessageDigest


plugins {
    // one Kotlin Gradle plugin for all modules; kotlin-dsl (gradle-plugin) needs Gradle's embedded version
    kotlin(libs.plugins.jvm.gradle.get().pluginId) version libs.plugins.jvm.gradle.get().version.toString() apply false
}

allprojects {
    group = "de.itemis.mps.envgen"
}
version = if (projectDir.resolve("version.txt").exists()){
    projectDir.resolve("version.txt").readText().trim()
} else {
    gitBranch(true) + (if (!project.findProperty("ciBuild")?.toString().toBoolean()) "-SNAPSHOT" else "")
}

logger.lifecycle("Building version: $version")
subprojects { version = rootProject.version }

// write the version to the MANIFEST of all jars; read by the generator for its output
allprojects {
    tasks.withType<Jar>().configureEach {
        manifest { attributes("Implementation-Version" to project.version) }
    }
}


// shared by all published modules (gradle-plugin, core)
val publishToItemisCloud = gitBranch() == "main"
allprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> { configureRepositories(project) }
    }
}

fun PublishingExtension.configureRepositories(project: Project) {
    repositories {
        mavenLocal()
        // release package: a Maven repository folder with exactly the release artifacts (see packagePlugin)
        maven {
            name = "release"
            url = uri(rootProject.layout.buildDirectory.dir("release/maven"))
        }
        if (publishToItemisCloud) {
            maven {
                name = "itemisCloud"
                url = uri("https://artifacts.itemis.cloud/repository/maven-mps-releases/")
                if (project.hasProperty("artifacts.itemis.cloud.user") && project.hasProperty("artifacts.itemis.cloud.pw")) {
                    credentials {
                        username = project.findProperty("artifacts.itemis.cloud.user") as String?
                        password = project.findProperty("artifacts.itemis.cloud.pw") as String?
                    }
                }
            }
        }
    }
}

fun gitBranch(hash: Boolean = false): String {
    val args = if (hash) listOf("git", "rev-parse", "--short", "HEAD") else listOf("git", "rev-parse", "--abbrev-ref", "HEAD")
    return try {
        val result = providers.exec {
            commandLine(args)
            isIgnoreExitValue = true
        }
        val output = result.standardOutput.asText.get().trim()
        if (result.result.get().exitValue != 0) {
            logger.warn("Unable to determine current branch: ${result.standardError.asText.get().trim()}")
            return "Unknown Branch"
        }
        if (output == "HEAD") logger.warn("Unable to determine current branch: Project is checked out with detached head!")
        output
    } catch (e: Exception) {
        logger.warn("Unable to determine current branch: ${e.message}")
        "Unknown Branch"
    }
}

tasks.register("buildCli") {
    group = "build"
    description = "Builds the mps-env-gen command line tool: runnable jar and distribution in modules/cli/build/install."
    dependsOn(":cli:fatJar", ":cli:installDist")
}

tasks.register("buildNativeCli") {
    group = "build"
    description = "Optional: builds a native mps-env-gen for the current OS (GraalVM toolchain is provisioned automatically)."
    dependsOn(":cli:nativeCompile")
}

// ---------------------------------------------------------------------------------------------------------------------
// Release packaging
//
//   ./gradlew packagePlugin   # build/release/maven: plugin, plugin marker and core as Maven artifacts
//   ./gradlew packageCli      # build/release/cli: see modules/cli/build.gradle.kts
//   ./gradlew packageAll      # build + tests of all modules, both packages and SHA256SUMS
// ---------------------------------------------------------------------------------------------------------------------

val releaseDir = layout.buildDirectory.dir("release")

val cleanRelease = tasks.register<Delete>("cleanRelease") {
    group = "distribution"
    description = "Deletes build/release."
    delete(releaseDir)
}

allprojects {
    tasks.withType<PublishToMavenRepository>().configureEach {
        if (name.endsWith("ToReleaseRepository")) mustRunAfter(cleanRelease)
    }
    tasks.matching { it.name == "packageCli" }.configureEach { mustRunAfter(cleanRelease) }
}

tasks.register("packagePlugin") {
    group = "distribution"
    description = "Packages the Gradle plugin for a release into build/release/maven (plugin, plugin marker, core)."
    dependsOn(cleanRelease, ":gradle-plugin:publishAllPublicationsToReleaseRepository", ":core:publishAllPublicationsToReleaseRepository")
}

tasks.register("packageAll") {
    group = "distribution"
    description = "Builds and tests all modules and packages the plugin and the CLI for a release into build/release."
    dependsOn(":gradle-plugin:build", ":core:build", ":cli:build", "packagePlugin", ":cli:packageCli")
    val cliDir = releaseDir.map { it.dir("cli") }
    val root = releaseDir
    doLast {
        val files = cliDir.get().asFile.listFiles().orEmpty().filter { it.isFile && it.name != "SHA256SUMS" }.sortedBy { it.name }
        cliDir.get().file("SHA256SUMS").asFile.writeText(files.joinToString("") { file ->
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            "${digest.joinToString("") { "%02x".format(it) }}  ${file.name}\n"
        })
        logger.lifecycle("Release packages in ${root.get().asFile}:")
        root.get().asFile.walkTopDown().filter { it.isFile }.sortedBy { it.path }
            .forEach { logger.lifecycle("  ${it.relativeTo(root.get().asFile)}") }
    }
}