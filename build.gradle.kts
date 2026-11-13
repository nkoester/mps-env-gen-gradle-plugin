import java.security.MessageDigest


plugins {
    // one Kotlin Gradle plugin for all modules; kotlin-dsl (gradle-plugin) needs Gradle's embedded version
    kotlin(libs.plugins.jvm.gradle.get().pluginId) version libs.plugins.jvm.gradle.get().version.toString() apply false
}

allprojects {
    group = "de.itemis.mps.envgen"
}
// release and snapshot builds: version.txt (written by the release workflow from the tag); all others: <branch>-SNAPSHOT
version = if (projectDir.resolve("version.txt").exists()) {
    projectDir.resolve("version.txt").readText().trim()
} else {
    gitBranch().replace(Regex("[^A-Za-z0-9._-]"), "-") + "-SNAPSHOT"
}

logger.lifecycle("Building version: $version")
subprojects { version = rootProject.version }
val isSnapshot = version.toString().endsWith("-SNAPSHOT")
val gitHubUrl = "https://github.com/nkoester/mps-env-gen-gradle-plugin"

// write the version to the MANIFEST of all jars; read by the generator for its output
allprojects {
    tasks.withType<Jar>().configureEach {
        manifest { attributes("Implementation-Version" to project.version) }
    }
}


// shared by all published modules (gradle-plugin, core)
allprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            configureRepositories(project)
            publications.withType<MavenPublication>().configureEach { pom { configurePom() } }
        }
        guardRemotePublishing(project)
    }
}

fun MavenPom.configurePom() {
    url.set(gitHubUrl)
    licenses {
        license {
            name.set("The Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }
    }
    scm {
        connection.set("scm:git:https://github.com/nkoester/mps-env-gen-gradle-plugin.git")
        developerConnection.set("scm:git:ssh://git@github.com/nkoester/mps-env-gen-gradle-plugin.git")
        url.set(gitHubUrl)
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
        // GitHub Packages of the repository a workflow runs in (same releases and snapshots as the itemis Nexus);
        // credentials: ORG_GRADLE_PROJECT_gitHubPackagesUsername/Password (checked only when publishing there)
        val gitHubRepository = providers.environmentVariable("GITHUB_REPOSITORY")
        if (gitHubRepository.isPresent) {
            maven {
                name = "gitHubPackages"
                url = uri("https://maven.pkg.github.com/${gitHubRepository.get()}")
                credentials(PasswordCredentials::class)
            }
        }
        // itemis Nexus, only with credentials: Gradle properties (~/.gradle/gradle.properties) or environment
        // variables (workflows); snapshots and releases go to separate repositories; the CLI is not published
        val itemisCloudUser = providers.gradleProperty("artifacts.itemis.cloud.user")
            .orElse(providers.environmentVariable("ARTIFACTS_ITEMIS_CLOUD_USER"))
        val itemisCloudPw = providers.gradleProperty("artifacts.itemis.cloud.pw")
            .orElse(providers.environmentVariable("ARTIFACTS_ITEMIS_CLOUD_PW"))
        if (itemisCloudUser.isPresent && itemisCloudPw.isPresent) {
            maven {
                name = "itemisCloud"
                url = uri("https://artifacts.itemis.cloud/repository/" + if (isSnapshot) "maven-mps-snapshots/" else "maven-mps-releases/")
                credentials {
                    username = itemisCloudUser.get()
                    password = itemisCloudPw.get()
                }
            }
        }
    }
}

// remote repositories cannot be cleaned up: only MAJOR.MINOR.PATCH and MAJOR.MINOR.PATCH-SNAPSHOT are published;
// local <branch>-SNAPSHOT builds never are
fun guardRemotePublishing(project: Project) {
    val published = version.toString()
    val publishable = Regex("""\d+\.\d+\.\d+(-SNAPSHOT)?""").matches(published)
    project.tasks.withType<PublishToMavenRepository>().configureEach {
        if (!name.endsWith("ToItemisCloudRepository") && !name.endsWith("ToGitHubPackagesRepository")) return@configureEach
        doFirst {
            if (!publishable) {
                throw GradleException("Refusing to publish version '$published': must be MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-SNAPSHOT")
            }
        }
    }
}

fun gitBranch(): String {
    // detached HEAD in GitHub workflows (pull requests): branch from the workflow environment
    val workflowBranch = providers.environmentVariable("GITHUB_HEAD_REF").filter { it.isNotEmpty() }
        .orElse(providers.environmentVariable("GITHUB_REF_NAME"))
    return try {
        val result = providers.exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
            isIgnoreExitValue = true
        }
        val output = result.standardOutput.asText.get().trim()
        when {
            result.result.get().exitValue != 0 -> {
                logger.warn("Unable to determine current branch: ${result.standardError.asText.get().trim()}")
                workflowBranch.getOrElse("unknown")
            }
            output == "HEAD" -> workflowBranch.getOrElse("HEAD")
            else -> output
        }
    } catch (e: Exception) {
        logger.warn("Unable to determine current branch: ${e.message}")
        workflowBranch.getOrElse("unknown")
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

// enables .githooks/commit-msg (Conventional Commits) for this clone; only changes this repository's git config,
// never run by other tasks; safe to run again
abstract class InstallGitHooks : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:Internal abstract val repository: DirectoryProperty

    @TaskAction
    fun install() {
        val previous = java.io.ByteArrayOutputStream()
        exec.exec {
            workingDir(repository)
            commandLine("git", "config", "--local", "--get", "core.hooksPath")
            standardOutput = previous
            isIgnoreExitValue = true
        }
        val old = previous.toString().trim()
        if (old == HOOKS_PATH) {
            logger.lifecycle("Git hooks already enabled (core.hooksPath=$HOOKS_PATH)")
            return
        }
        exec.exec {
            workingDir(repository)
            commandLine("git", "config", "--local", "core.hooksPath", HOOKS_PATH)
        }
        if (old.isNotEmpty()) logger.warn("Replaced core.hooksPath=$old")
        logger.lifecycle("Git hooks enabled (core.hooksPath=$HOOKS_PATH)")
    }

    companion object {
        const val HOOKS_PATH = ".githooks"
    }
}

tasks.register<InstallGitHooks>("installGitHooks") {
    group = "help"
    description = "Enables the commit message check (Conventional Commits) for this clone: git config core.hooksPath .githooks."
    repository.set(layout.projectDirectory)
}

// prints the release notes the next vX.Y.Z tag would get (cliff.toml, same as release.yml); needs git-cliff on the PATH
abstract class PreviewChangelog : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:Internal abstract val repository: DirectoryProperty

    @TaskAction
    fun preview() {
        val names = listOf("git-cliff", "git-cliff.exe")
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { dir -> names.any { File(dir, it).canExecute() } }
        if (!onPath) {
            throw GradleException(
                "git-cliff not found on the PATH. Install it, e.g. 'cargo install git-cliff', 'pipx install git-cliff', " +
                    "'brew install git-cliff' or your distribution's package (https://git-cliff.org/docs/installation)."
            )
        }
        exec.exec {
            workingDir(repository)
            commandLine("git-cliff", "--config", "cliff.toml", "--unreleased", "--strip", "all")
        }
    }
}

tasks.register<PreviewChangelog>("previewChangelog") {
    group = "help"
    description = "Prints the release notes of the next release (commits since the last vX.Y.Z tag); needs git-cliff."
    repository.set(layout.projectDirectory)
    notCompatibleWithConfigurationCache("prints the current git state on every run")
    outputs.upToDateWhen { false }
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