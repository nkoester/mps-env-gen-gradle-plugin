import java.io.File
import java.net.URI
import java.nio.file.Files
import org.gradle.api.provider.Provider

plugins {
    kotlin("jvm")
    application
    alias(libs.plugins.graalvm.native)
}

description = "Standalone command line tool to generate isolated MPS environments"

base.archivesName.set("mps-env-gen-cli")

kotlin {
    // the CLI must run on any Java 11+ (MPS itself runs on its own JBR)
    jvmToolchain(11)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.clikt.core)
    testImplementation(testFixtures(project(":core")))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

application {
    applicationName = "mps-env-gen"
    mainClass.set("de.itemis.mps.envgen.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// the version is read from a resource; jar manifests are not available in a native image
val versionResource = tasks.register("versionResource") {
    val version = project.version.toString()
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast { output.get().file("mps-env-gen-version.txt").asFile.apply { parentFile.mkdirs() }.writeText(version) }
}
sourceSets.main { resources.srcDir(versionResource) }

// MPS versions offered by tab completion without local installations; packaged, so completion needs no network.
// Refresh by hand: ./gradlew :cli:updateMpsVersions [-Prepository=<maven repository>]
tasks.register("updateMpsVersions") {
    group = "maintenance"
    description = "Updates src/main/resources/mps-versions.txt from the MPS versions published in the Maven repository."
    val repository = providers.gradleProperty("repository").orElse("https://artifacts.itemis.cloud/repository/maven-mps")
    val output = layout.projectDirectory.file("src/main/resources/mps-versions.txt")
    outputs.upToDateWhen { false }
    doLast {
        val url = "${repository.get().trimEnd('/')}/com/jetbrains/mps/maven-metadata.xml"
        val metadata = URI(url).toURL().readText()
        val pattern = Regex("""^(\d{4})\.(\d+)(?:\.(\d+))?(?:-(.+))?$""")
        val versions = Regex("<version>([^<]+)</version>").findAll(metadata).map { it.groupValues[1].trim() }
            .mapNotNull { v -> pattern.matchEntire(v)?.destructured?.let { (y, m, p, q) -> Triple(v, listOf(y.toInt(), m.toInt(), p.ifEmpty { "0" }.toInt()), q) } }
            // supported range of the tool (MpsVersion.FIRST_SUPPORTED)
            .filter { (_, numbers) -> numbers[0] > 2022 || numbers[0] == 2022 && numbers[1] >= 2 }
            .toList()
        val finals = versions.filter { it.third.isEmpty() }.map { it.second }.toSet()
        // pre-releases only until their release is published
        val listed = versions.filter { (_, numbers, qualifier) -> qualifier.isEmpty() || numbers !in finals }
            .sortedWith(compareBy<Triple<String, List<Int>, String>>({ it.second[0] }, { it.second[1] }, { it.second[2] }, { it.third.isEmpty() }, { it.third }))
            .map { it.first }
        check(listed.isNotEmpty()) { "no MPS versions found in $url" }
        output.asFile.writeText("# MPS versions for tab completion; ./gradlew :cli:updateMpsVersions\n" + listed.joinToString("\n", postfix = "\n"))
        logger.lifecycle("${listed.size} MPS versions written to ${output.asFile}")
    }
}

// default artifact: one runnable jar for all OS (`java -jar mps-env-gen-<version>-all.jar`)
val fatJar = tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a runnable jar containing the CLI and all dependencies."
    archiveBaseName.set("mps-env-gen")
    archiveClassifier.set("all")
    manifest { attributes("Main-Class" to application.mainClass.get()) }
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
    // the mapped classpath above does not carry its task dependencies (e.g. :core:jar)
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}
tasks.assemble { dependsOn(fatJar) }

// optional: native executable for the current OS only, never part of `build`
val nativeImageLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    nativeImageCapable.set(true)
}

graalvmNative {
    // the toolchain below is provisioned via foojay; no local GraalVM needed
    toolchainDetection.set(false)
    binaries {
        named("main") {
            imageName.set("mps-env-gen")
            mainClass.set(application.mainClass)
            javaLauncher.set(nativeImageLauncher)
            buildArgs.addAll("--no-fallback", "--enable-url-protocols=http,https")
        }
    }
}

tasks.named("nativeCompile") {
    val launcher = nativeImageLauncher
    val output = layout.buildDirectory.file("native/nativeCompile/mps-env-gen")
    doFirst {
        // Gradle's toolchain provisioning turns the bin/native-image symlink of GraalVM/Mandrel into an empty file,
        // which then "succeeds" without building anything
        val home = launcher.get().metadata.installationPath.asFile.toPath()
        val nativeImage = home.resolve("bin/native-image")
        val real = home.resolve("lib/svm/bin/native-image")
        if (Files.isRegularFile(nativeImage) && Files.size(nativeImage) == 0L && Files.isRegularFile(real)) {
            Files.delete(nativeImage)
            Files.createSymbolicLink(nativeImage, nativeImage.parent.relativize(real))
            logger.lifecycle("Repaired $nativeImage (empty file instead of symlink)")
        }
    }
    doLast {
        val binary = output.get().asFile
        check(binary.isFile || File(binary.path + ".exe").isFile) { "native-image produced no executable in ${binary.parentFile}" }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Installation into a prefix (Unix layout, also works on Windows)
//
//   ./gradlew installCli                     # ~/.local/bin/mps-env-gen
//   ./gradlew installCli -Pprefix=~/opt      # ~/opt/bin/mps-env-gen
//   ./gradlew installNativeCli               # same with the native binary (current OS only)
//   ./gradlew uninstallCli
//
// Layout: <prefix>/lib/mps-env-gen/      installed tool (replaced on every install)
//         <prefix>/bin/mps-env-gen       symlink to it (Windows: mps-env-gen.bat wrapper)
//         <prefix>/share/bash-completion/completions/mps-env-gen
// ---------------------------------------------------------------------------------------------------------------------

val installPrefix: Provider<File> = providers.gradleProperty("prefix")
    // `-Pprefix=~/x` is not an assignment, so the shell does not expand `~`
    .map { File(it.replaceFirst(Regex("^~(?=/|\\\\|$)"), Regex.escapeReplacement(System.getProperty("user.home")))) }
    .orElse(File(System.getProperty("user.home"), ".local"))
    .map { it.absoluteFile.normalize() }
val isWindows = System.getProperty("os.name").lowercase().contains("win")

val bashCompletion = tasks.register<JavaExec>("bashCompletion") {
    description = "Generates the bash completion script for the CLI."
    val output = layout.buildDirectory.file("completion/mps-env-gen.bash")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("completion", "bash")
    outputs.file(output)
    doFirst { standardOutput = output.get().asFile.apply { parentFile.mkdirs() }.outputStream() }
    doLast { standardOutput.close() }
}

fun Task.linkInstalledTool(home: Provider<File>, executable: String) {
    val prefix = installPrefix
    val tool = "mps-env-gen"
    val completion = bashCompletion.flatMap { it.outputs.files.elements }.map { it.single().asFile }
    doLast {
        val target = home.get().toPath().resolve(executable)
        val bin = prefix.get().toPath().resolve("bin").also { Files.createDirectories(it) }
        if (isWindows) {
            // symlinks need admin rights or developer mode on Windows
            bin.resolve("$tool.bat").toFile().writeText("@\"$target\" %*\r\n")
        } else {
            val link = bin.resolve(tool)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, target)
        }
        val completions = prefix.get().toPath().resolve("share/bash-completion/completions").also { Files.createDirectories(it) }
        completion.get().copyTo(completions.resolve(tool).toFile(), overwrite = true)

        logger.lifecycle("Installed $tool -> $target")
        logger.lifecycle("Make sure ${bin} is on your PATH; completion: source ${completions.resolve(tool)}")
    }
}

tasks.register<Sync>("installCli") {
    group = "distribution"
    description = "Installs the CLI (JVM, Java 11+) into <prefix>/lib/mps-env-gen and links <prefix>/bin/mps-env-gen. " +
        "Property: -Pprefix (default ~/.local)."
    dependsOn(bashCompletion)
    with(distributions.main.get().contents)
    into(installPrefix.map { File(it, "lib/mps-env-gen") })
    linkInstalledTool(installPrefix.map { File(it, "lib/mps-env-gen") }, if (isWindows) "bin/mps-env-gen.bat" else "bin/mps-env-gen")
}

tasks.register<Sync>("installNativeCli") {
    group = "distribution"
    description = "Like installCli, but installs the native executable of the current OS (builds it if needed)."
    dependsOn(bashCompletion)
    // whole installation is replaced, so jars of a former JVM install do not stay behind
    into(installPrefix.map { File(it, "lib/mps-env-gen") })
    from(tasks.named("nativeCompile")) {
        include("mps-env-gen", "mps-env-gen.exe")
        into("bin")
    }
    linkInstalledTool(installPrefix.map { File(it, "lib/mps-env-gen") }, if (isWindows) "bin/mps-env-gen.exe" else "bin/mps-env-gen")
}

tasks.register("uninstallCli") {
    group = "distribution"
    description = "Removes what installCli/installNativeCli installed for -Pprefix."
    val prefix = installPrefix
    val tool = "mps-env-gen"
    doLast {
        val bin = prefix.get().resolve("bin")
        listOf(File(bin, tool), File(bin, "$tool.bat"), File(prefix.get(), "share/bash-completion/completions/$tool"))
            .forEach { Files.deleteIfExists(it.toPath()) }
        File(prefix.get(), "lib/mps-env-gen").deleteRecursively()
        logger.lifecycle("Removed $tool from ${prefix.get()}")
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Release packaging (see root task packageAll)
//
//   ./gradlew packageCli                     # build/release/cli: generic JVM zip + runnable jar + Linux native tar.gz
//   ./gradlew packageCli -PskipNative=true   # without native (e.g. on macOS/Windows hosts)
// ---------------------------------------------------------------------------------------------------------------------

val releaseDir = rootProject.layout.buildDirectory.dir("release/cli")
val skipNative = providers.gradleProperty("skipNative").map { it.toBoolean() }.orElse(false)
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    else -> "x64"
}

distributions.main {
    contents { from(rootProject.file("LICENSE")) }
}

val nativeDistTar = tasks.register<Tar>("nativeDistTar") {
    group = "distribution"
    description = "Packages the Linux native executable as mps-env-gen-<version>-linux-<arch>.tar.gz."
    val top = "mps-env-gen-${project.version}-linux-$hostArch"
    archiveFileName.set("$top.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    from(tasks.named("nativeCompile")) {
        include("mps-env-gen")
        into("$top/bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(rootProject.file("LICENSE")) { into(top) }
}

tasks.register<Sync>("packageCli") {
    group = "distribution"
    description = "Packages the CLI for a release into build/release/cli: generic JVM distribution, runnable jar " +
        "and Linux native distribution (-PskipNative=true to leave the native one out)."
    val linux = System.getProperty("os.name").lowercase().contains("linux")
    val native = skipNative.map { !it }.get()
    if (native && !linux) {
        doFirst {
            throw GradleException("The Linux native distribution can only be built on Linux; use -PskipNative=true on this host.")
        }
    }
    from(tasks.named("distZip"), fatJar)
    if (native && linux) from(nativeDistTar)
    into(releaseDir)
}
