# MPS Environment Generation Gradle Plugin

❗❗ This plugin is work in progress and not yet released or published ❗❗


A small Gradle plugin which allows you to define isolated MPS environments.
Environment are self-contained and can consequently be started in parallel.

This setup can be helpful if you have a repository checked out multiple times or need to quickly review a pull request without changing your MPS setup and/or configuration.

The generated configuration prefix are written to `.mpsconfig` by default and are considered generated artifacts which should not be checked into VCS but rather be generated locally.


# Usage

Requires Gradle 8.14+ on Java 17+; Kotlin and Groovy build scripts are supported.

```kts
// settings.gradle.kts
pluginManagement { repositories { maven("https://artifacts.itemis.cloud/repository/maven-mps"); gradlePluginPortal() } }

// build.gradle.kts
plugins { id("de.itemis.mps.mps-env-gen-gradle-plugin") version "<version>" }
repositories { maven("https://artifacts.itemis.cloud/repository/maven-mps") }   // MPS and JBR downloads

mpsEnvironments {
    // defaults for all environments
    mpsVersion.set("2025.3")        // download MPS 2022.2+ from Nexus; or mpsPath.set(file("/opt/MPS-2025.3"))
    projectPath.set(layout.projectDirectory.dir("mps-project"))
    // jbrVersion, javaHome, targetPath (default .mpsconfig)

    environments {
        register("default")
        register("debug") {
            mpsVersion.set("2026.1")                // every default can be overridden
            debug.set(true)                         // debugPort (5071), debugSuspend
            xmx.set("4096m")                        // xms, ratio, httpPort
            theme.set(Theme.DARK)                   // import de.itemis.mps.envgen.environment.Theme
            modelCheckBeforeGeneration.set(true)    // default: false
            os.set(listOf(OS.LINUX, OS.WINDOWS))    // import de.itemis.mps.envgen.OS; default: current OS
            vmOptions.add("-Dmy.option=1")          // extra lines for mps64.vmoptions
            ideaProperties.add("my.property=1")     // extra lines for idea.properties
        }
    }
}
```

Tasks (group `mps`):
- `generateMpsEnvironment-<Name>` writes `.mpsconfig/<name>`; start MPS with the script inside, e.g. `.mpsconfig/default/startMPS_LINUX_default.sh`
- `generateMpsEnvironmentAll`
- `prepareMps-<version>` downloads MPS and a matching JBR (Nexus only) into `.mpsconfig/mps-<version>-<OS>`; run automatically

See `example/` for a complete project.

# Command line tool

The same environments can be created without a Gradle project using `mps-env-gen`
(module `cli`, needs Java 11+; MPS itself runs on its own JBR).

## Install

```bash
./gradlew installCli                                         # ~/.local/bin/mps-env-gen
./gradlew installCli -Pprefix=~/.local -PcliName=zookeeper2  # ~/.local/bin/zookeeper2
./gradlew installNativeCli -PcliName=zookeeper2              # native executable instead (current OS only, GraalVM is downloaded)
./gradlew uninstallCli -PcliName=zookeeper2
```

Layout: `<prefix>/lib/mps-env-gen` (the tool), `<prefix>/bin/<cliName>` (symlink; `<cliName>.bat` on Windows)
and `<prefix>/share/bash-completion/completions/<cliName>`. Without installing:
`java -jar modules/cli/build/libs/mps-env-gen-<version>-all.jar` after `./gradlew buildCli`.

## Use

```bash
zookeeper2 create 2025.3 --project ./mps-project   # MPS from local installs (--installs) or Nexus
zookeeper2 create /opt/MPS-2025.3 --name review    # explicit installation
zookeeper2 create 2025.3 --temp                    # throw-away environment in $TMPDIR/mps-env-gen
zookeeper2 create 2025.3 --temp --start            # ... and start it right away (runs its start script)
zookeeper2 refresh next --start=tmuxLA -d          # start in tmux with log window, debugging for this start
zookeeper2 copy 2025.3 experiment --mps 2026.1     # settings and plugins, other MPS
zookeeper2 rename experiment next
zookeeper2 refresh next --xmx 6g --debug           # debugging on for every start (per start: script -d)
zookeeper2 completion bash                         # tab completion script
```

Every command prints the start script of the environment and copies it to the clipboard (`--no-copy` to skip);
environments do not need the tool to start. `<mps>` is a version, a path, or the name of any folder in `--installs`
(tab completion lists them). Every environment records its creation time in `created.txt`; `refresh` keeps the
previous files in `<env>/.backup/<timestamp>/`.
Defaults: `~/.config/mps-env-gen/defaults.properties` (e.g. `installs=/vol/mps`, `xmx=4096m`, used by `create`; keys are
the long option names; `mps-env-gen init-defaults` writes a commented template, `--print` shows it) and the environment
variables `MPS_ENV_GEN_TARGET`, `MPS_ENV_GEN_INSTALLS` (or `MPS_BASE_PATH`), `MPS_ENV_GEN_REPOSITORY`, `MPS_ENV_GEN_CACHE`,
which take precedence over the file.

# Release packages

```bash
./gradlew packageAll                     # build + tests of all modules, then both packages below
./gradlew packagePlugin                  # build/release/maven: plugin, plugin marker, core (Maven layout, for the release)
./gradlew packageCli                     # build/release/cli: JVM zip, runnable jar, Linux native tar.gz
./gradlew packageAll -PskipNative=true   # on hosts other than Linux (native images are built for the host OS only)
```

`build/release/cli/SHA256SUMS` lists the checksums of the CLI packages.

# Generated environments

## Start scripts
- `startMPS_<OS>_<name>.sh|.bat` start MPS with the environment; they need neither Gradle nor `mps-env-gen`
- moved environment: the Linux/macOS scripts detect it and offer to fix the paths in place
- port for opening nodes from URLs (`http://127.0.0.1:<port>/node`): each start takes the first free port from the
  configured one (default 63321) up to +20, so several environments can run at the same time
- debugging is off by default; `startMPS_<OS>_<name>.sh -d` (also with tmux modes: `-d tmuxLA`) enables it for one
  start, `debug`/`--debug` for every start. The script takes the first free port from the configured one (CLI 51337,
  Gradle 5071) up to +20 and binds it to `127.0.0.1` only
- the ports of a start go into `mps/mps64.runtime.vmoptions`, which MPS reads; `mps/mps64.vmoptions` stays as generated

## Your changes are kept
Files that are MPS settings or meant to be edited are written **only if missing**; afterwards they are yours and
neither Gradle nor `mps-env-gen` overwrite them unless told explicitly:

| Written only if missing | Always written |
|---|---|
| `mps/mps64.vmoptions` (the start script keeps only the node URL port line up to date) | start scripts |
| `mps/idea.properties` (only the five `idea.*.path` keys follow the location) | `README.md`, `environment.properties` |
| `mps/config/options/`: `laf.xml`, `colors.scheme.xml`, `ui.lnf.xml`, `ide.general.xml`, `other.xml`, `updates.xml`, `generationSettings.xml`, `recentProjects.xml`, `trusted-paths.xml` | `created.txt` (once) |

So a changed setting (e.g. `xmx`, `theme`) only affects new environments. To apply it to an existing one:
- Gradle: `./gradlew generateMpsEnvironment-<Name> --overwrite-settings` (the build output tells when settings were not applied)
- CLI: `mps-env-gen refresh <env> --reset-settings`, or a single option like `refresh <env> --xmx 6g`

The previous files are kept in `<env>/.backup/<timestamp>/`. MPS' "Help > Edit Custom VM Options" edits
`mps/mps64.vmoptions`, except in debug starts, which use a per-start copy (`mps/mps64.runtime.vmoptions`).

## First start
New environments open without the "What's new" page: older versions read `updates.xml` (`SHOW_WHATS_NEW_EDITOR=false`),
platform 242+ (from `build.txt`) compares `whats.new.last.shown.version` in `other.xml` with the page's version, so the
stored `9999-9-0-mpsenvgen` always counts as newer. These versions also start with the new UI, light theme, compact
density and the onboarding marked as seen (`experimental.ui.used.version`); older versions use the classic light theme.

## vmoptions defaults
`mps/mps64.vmoptions` combines the defaults of MPS with those of the earlier bash tool mps-zookeeper.
The reasons below are the assumptions behind them; they were taken over, not re-verified for every MPS version:

| Option | Why |
|---|---|
| `-Xms1024m`, `-Xmx2048m`, `-XX:NewRatio=4` | configurable (`xms`, `xmx`, `ratio`) |
| `-XX:ReservedCodeCacheSize=1024m` | larger than MPS' 512m; MPS compiles a lot of generated code |
| `-Xss1024k`, `-XX:NewSize=256m` | deep model/generator recursion; young generation for short lived model objects |
| `-Dintellij.config.imported.in.current.session=true`, `-Didea.initially.ask.config=true` | fresh environments start without the "import settings" dialog |
| `-Dperformance.watcher.freeze.report=false`, `-Dfreeze.reporter.enabled=false` | no freeze reports from isolated/throw-away environments |
| `-Djava.net.preferIPv4Stack=true` | node URLs and debugging on `127.0.0.1` |
| `-Dawt.useSystemAAFontSettings=lcd`, `-Dsun.java2d.renderer=sun.java2d.marlin.MarlinRenderingEngine` | font antialiasing and renderer as in mps-zookeeper (Linux desktops) |
| `-Didea.log.config.file=log.xml` | log configuration as in mps-zookeeper; MPS 2022.3+ ships `log.properties` instead, effect there unverified |
| `-Dide.httpsupport.internalPort=<port>` | node URL port, see above |
| `-agentlib:jdwp=...address=127.0.0.1:<port>` | only with `debug`; `#`-commented otherwise |

Add or override anything with `vmOptions` (Gradle) or `--vm-option` (CLI); later lines win.

## Tips

**`git clean` deletes the environments.** `.mpsconfig/` is not checked in, so `git clean -xdf` removes it including
the MPS caches and settings of all environments. Exclude it with an alias:

```git
[alias]
    cleanxdf = clean -xdf -e .mpsconfig
```

**Only allow isolated MPS instances.** To make sure MPS never starts with the shared default configuration, move it
away and make the folder read-only:

```bash
mv ~/.config/JetBrains ~/.config/JetBrains.bak
mkdir ~/.config/JetBrains
chmod a-w ~/.config/JetBrains
```

Warning: this also affects other JetBrains IDEs (e.g. IntelliJ IDEA), which then need an isolated configuration too.
