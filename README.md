# MPS Environment Generation Gradle Plugin

Generates isolated MPS environments: each has its own configuration, caches, plugins and start script, so several can run in parallel without touching your default MPS setup.
Typical uses: several checkouts of one repository, reviewing a pull request.

Environments are written to `.mpsconfig/` by default.
They are generated locally and are not checked in.
The folder contains a `.gitignore` with `*`, so git ignores it without changes to your project's `.gitignore`.

Two front ends share one implementation: a Gradle plugin and a command line tool (`mps-env-gen`).

# 1. Gradle plugin

Requires Gradle 8.14+ on Java 17+ and supports the Kotlin and Groovy DSL.

## 1.0 Get the plugin

Releases and snapshots are published to the itemis Nexus.
Add the repository to `settings.gradle.kts`:

```kts
pluginManagement {
    repositories {
        maven("https://artifacts.itemis.cloud/repository/maven-mps") {
            content { includeGroup("de.itemis.mps.envgen") }   // plugin, its marker and core
        }
        gradlePluginPortal()
    }
}
```

Versions:

- releases `MAJOR.MINOR.PATCH`, see [releases](https://github.com/nkoester/mps-env-gen-gradle-plugin/releases), which also offer the CLI as zip and runnable jar
- snapshots `MAJOR.MINOR.PATCH-SNAPSHOT`, published on demand; Gradle checks for a newer build once a day, `--refresh-dependencies` forces it

## 1.1 Usage

### 1.1.1 Minimal Variant

Minimal setup in your build files for one environment `.mpsconfig/default` with MPS downloaded from Nexus:

```kts
// settings.gradle.kts: plugin repository, see 1.0

// build.gradle.kts
plugins { id("de.itemis.mps.envgen") version "<version>" }
repositories { maven("https://artifacts.itemis.cloud/repository/maven-mps") }   // MPS and JBR downloads

mpsEnvironments {
    mpsVersion.set("2025.1.4")
    environments { register("default") }
}
```

Once Gradle configured the project, the following Gradle tasks are generated:

Tasks (group `mps`):

- `generateMpsEnvironment-default`: writes `.mpsconfig/default` including its start script `.mpsconfig/default/startMPS_LINUX_default.sh`
- `generateMpsEnvironmentAll`: generates all environments
- `prepareMps-<version>`: downloads MPS and a matching JBR from Nexus and prepares a runnable MPS in the shared cache folder (see "5.1 Downloaded MPS and cache folder").
  It runs automatically when needed.

After `./gradlew generateMpsEnvironment-default` (Linux host):

```
.mpsconfig
├── .gitignore                           "*": git ignores the whole folder
├── default                              the environment
│   ├── startMPS_LINUX_default.sh        start script; needs neither Gradle nor mps-env-gen
│   ├── README.md
│   ├── environment.properties           settings and origin of the environment
│   ├── created.txt
│   └── mps                              MPS configuration of this environment
│       ├── idea.properties              its idea.*.path keys (config, system, plugins, log, scratch) point here
│       ├── mps64.vmoptions
│       ├── config
│       │   ├── early-access-registry.txt
│       │   └── options                  laf.xml, colors.scheme.xml, ui.lnf.xml, other.xml, updates.xml, ...
│       ├── system                       caches and indexes, created by MPS on first start
│       └── log                          created by MPS on first start

~/.cache/mps-env-gen                     shared cache folder (Linux), see "5.1 Downloaded MPS and cache folder"
└── distributions
    └── mps-2025.1.4-LINUX               MPS and JBR from Nexus, shared by all environments, projects and the CLI
        ├── bin
        ├── jbr
        ├── lib
        └── ...
```

MPS can then be started via the generated start script:

```bash
$ mpsconfig/default/startMPS_LINUX_default.sh
...
```

### 1.1.2 Expert Variant

There are many settings changable.
Here is an expert setup, with defaults for all environments, a debug environment and one on a locally installed MPS:

```kts
// build.gradle.kts
import de.itemis.mps.envgen.OS
import de.itemis.mps.envgen.environment.Theme

mpsEnvironments {
    // defaults for all environments
    mpsVersion.set("2025.1.4")                                  // MPS 2022.2+ from Nexus
    projectPath.set(layout.projectDirectory.dir("mps-project"))  // optional: trusted and opened on first start
    // also: mpsPath, jbrVersion, javaHome, targetPath (default .mpsconfig),
    //       mpsInTargetPath (prepare MPS in targetPath instead of the shared cache folder)

    environments {
        register("default")

        register("debug") {
            debug.set(true)                         // remote debugging on every start, port 5071 or the next free one
            debugSuspend.set(true)                  // MPS waits for the debugger
            modelCheckBeforeGeneration.set(true)    // default: false
            xmx.set("6g")                           // also: xms, ratio
        }

        register("next") {
            mpsPath.set(file("/vol/mps/MPS-2026.1-linux"))    // installed MPS instead of the default version
            httpPort.set(63400)                     // fixed node URL port, see "5.3 Node URL port"
            theme.set(Theme.DARK)
            updateChecks.set(false)                 // no update checks for MPS and plugins; default: true
            mergeDriverNotification.set(true)       // VCS Addons merge driver popup; default: false (still logged)
            selectOpenedFile.set(false)             // project view "Always Select Opened File"; default: true
            os.set(listOf(OS.LINUX, OS.WINDOWS))    // start scripts to generate; default: current OS
            vmOptions.add("-Dmy.option=1")          // extra lines for mps64.vmoptions
            ideaProperties.add("my.property=1")     // extra lines for idea.properties
            plugins {                               // plugin zips or folders, see "5.5 Plugins"
                register("my-tool") {
                    from("tools/my-tool-1.0-2026.1.zip")
                    ports.put("my.tool.port", 8600)
                }
            }
        }
    }
}
```

The folder `example/` contains a complete project with more deatils.

# 2. Command line tool

Needs Java 11+ (MPS runs on its own JBR).

## 2.1 Install

```bash
./gradlew installCli                   # ~/.local/bin/mps-env-gen
./gradlew installCli -Pprefix=~/opt    # ~/opt/bin/mps-env-gen
./gradlew installNativeCli             # native executable instead (current OS only, GraalVM is downloaded)
./gradlew uninstallCli
```

Layout:

```
<prefix>
├── bin
│   └── mps-env-gen                    symlink to lib/mps-env-gen/bin/mps-env-gen (Windows: mps-env-gen.bat wrapper)
├── lib
│   └── mps-env-gen                    replaced on every install
│       ├── bin/mps-env-gen            start script (Windows: .bat), or native executable (Windows: .exe)
│       └── lib/*.jar                  JVM install only
└── share
    └── bash-completion
        └── completions
            └── mps-env-gen
```

Without installing: `./gradlew buildCli`, then `java -jar modules/cli/build/libs/mps-env-gen-<version>-all.jar`.

### 2.1.1 macOS

Install a Java 11+ (e.g. `brew install openjdk@17`), then:

```bash
./gradlew installCli                                              # ~/.local/bin/mps-env-gen
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc           # ~/.local/bin is not on the macOS PATH
echo 'autoload -Uz compinit && compinit' >> ~/.zshrc              # skip if your ~/.zshrc already has it
echo 'source <(mps-env-gen completion zsh)' >> ~/.zshrc           # zsh is the default shell on macOS
```

Open a new terminal and check with `mps-env-gen --version`.

### 2.1.2 Windows

Install a Java 11+, then in PowerShell:

```powershell
.\gradlew.bat installCli                                          # %USERPROFILE%\.local\bin\mps-env-gen.bat
[Environment]::SetEnvironmentVariable("Path", "$env:USERPROFILE\.local\bin;" + [Environment]::GetEnvironmentVariable("Path", "User"), "User")
```

Open a new terminal and check with `mps-env-gen --version`.
`installCli` writes a `mps-env-gen.bat` wrapper instead of a symlink (symlinks need admin rights or developer mode).
There is no PowerShell completion; `mps-env-gen completion` supports bash, zsh and fish only.
`installNativeCli` probably needs the Visual Studio Build Tools for GraalVM's native-image (untested); the JVM install above does not.

### 2.1.3 From a release download (any OS, no build)

Each GitHub release has the JVM distribution `mps-env-gen-<version>.zip` and the runnable jar `mps-env-gen-<version>-all.jar` (Java 11+ for both):

- unzip `mps-env-gen-<version>.zip` and add its `mps-env-gen-<version>/bin` folder to the `PATH` (it contains `mps-env-gen` and `mps-env-gen.bat`)
- or run the jar directly: `java -jar mps-env-gen-<version>-all.jar create 2026.1 --temp`

The macOS and Windows steps above have not been run on those systems yet; the CI smoke test (see "6.3 Smoke test") uses the CLI from `./gradlew :cli:installDist` instead.

## 2.2 Use

Create a throw-away environment in `$TMPDIR/mps-env-gen/2025.1.4-<date>-<time>`.
Its MPS is kept in the cache (see "5.1 Downloaded MPS and cache folder"), so throw-away environments stay small and share one MPS per version:

```bash
$ mps-env-gen create 2025.1.4 --temp
Environment '2025.1.4-20261029-141046' created
  path       /tmp/mps-env-gen/2025.1.4-20261029-141046
  created    2026-10-29 14:10:46
  MPS        2025.1.4 (nexus)
  JBR        21.0.8-b895.146
  debugging  off (start script: -d)

Start it with (copied to the clipboard):
  /tmp/mps-env-gen/2025.1.4-20261029-141046/startMPS_LINUX_2025.1.4-20261029-141046.sh
```

Create the same and start MPS right away through the environment's start script:

```bash
$ mps-env-gen create 2025.1.4 --temp --start
Environment '2025.1.4-20261029-141316' created
  path       /tmp/mps-env-gen/2025.1.4-20261029-141316
  created    2026-10-29 14:13:16
  MPS        2025.1.4 (nexus)
  JBR        21.0.8-b895.146
  debugging  off (start script: -d)

Start it with (copied to the clipboard):
  /tmp/mps-env-gen/2025.1.4-20261029-141316/startMPS_LINUX_2025.1.4-20261029-141316.sh

Starting 2025.1.4-20261029-141316 ...
Node URL port: 63321 (first free from 63321)  http://127.0.0.1:63321/node

Calling MPS:
    MPS_PROPERTIES=/tmp/mps-env-gen/2025.1.4-20261029-141316/mps/idea.properties \
    IDEA_VM_OPTIONS=/tmp/mps-env-gen/2025.1.4-20261029-141316/mps/mps64.vmoptions \
    MPS_VM_OPTIONS=/tmp/mps-env-gen/2025.1.4-20261029-141316/mps/mps64.vmoptions \
    _JAVA_AWT_WM_NONREPARENTING=1 \
    MPS_JDK=/home/me/.cache/mps-env-gen/distributions/mps-2025.1.4-LINUX/jbr \
    /home/me/.cache/mps-env-gen/distributions/mps-2025.1.4-LINUX/bin/mps.sh

<MPS output ... >
```

Create an environment in `./.mpsconfig/2025.1.4` for a project, which is trusted and opened on first start:

```bash
$ mps-env-gen create 2025.1.4 --project /path/to/my-mps-project
...
```

Create an environment in `./.mpsconfig/review-my-project` for a project, using a locally installed MPS without downloading anything:

```bash
$ mps-env-gen create /vol/mps/MPS-2026.1-linux/ --name review-my-project --project /path/to/my-mps-project
Environment 'review-my-project' created
  path       /tmp/sample-folder/.mpsconfig/review-my-project
  created    2026-10-29 14:30:39
  MPS        /vol/mps/MPS-2026.1-linux (path)
  debugging  off (start script: -d)

Start it with (copied to the clipboard):
  /tmp/sample-folder/.mpsconfig/review-my-project/startMPS_LINUX_review-my-project.sh
```

Create a throw-away environment with a plugin installed (see "5.5 Plugins"):

```bash
mps-env-gen create 2025.1.4 --temp --plugin ~/plugins/my-tool-1.0-2025.1.zip --start
```

## 2.3 Bash completion

`installCli` installs the script to `<prefix>/share/bash-completion/completions/mps-env-gen`.
bash-completion loads it automatically only for the prefix `~/.local`.
For other prefixes, add this to `~/.bashrc`:

```bash
source <(mps-env-gen completion bash)
```

Completion of `<mps>` and `--mps`:

- if an installations folder is given (`--installs` on the command line, else exported `MPS_ENV_GEN_INSTALLS` or `MPS_BASE_PATH`), it offers the versions in the folder names, e.g. `2026.1` for `MPS-2026.1-linux`.
  Folders without a version are not offered.
- otherwise it offers the MPS versions on Nexus from a list packaged with the tool, without network access.
  Maintainers update the list with `./gradlew :cli:updateMpsVersions`.
- it ignores `installs` from the defaults file

`mps-env-gen completion fish` prints a fish variant (untested).

# 3. Configuration options

Gradle plugin:

- settings in `mpsEnvironments { }` are defaults for all environments, settings in `register("<name>") { }` apply to one environment and win
- the IDE's code completion in `build.gradle.kts` lists all settings with their documentation and defaults
- `example/build.gradle.kts` is a complete project with commented settings, "1.1.2 Expert Variant" shows a compact overview

CLI:

- `mps-env-gen init-defaults --print` lists every option of `create` with its help text and default
- `mps-env-gen <command> --help` shows the options of each command
- `copy` and `refresh` accept the environment settings too and change only what is given
- every option has a short form (e.g. `-t` for `--temp`, `-s` for `--start`), listed by `--help`; flags combine, e.g. `create 2025.1.4 -ts`
- options and arguments may come in any order
- the optional values of `--start` and `--debug` must be attached: `--start=tmuxLA`, `-s=tmuxLA`, `--debug=5005`, `-x5005`

Settings stored in files that MPS also edits (heap, theme, model check, update checks, merge driver popup, select opened file, VM options, IDEA properties, project) are written only if the file is missing, or when explicitly requested (`refresh` with the option or `--reset-settings`, Gradle `--overwrite-settings`), see "5.4 Changes to generated environments".

## 3.1 CLI defaults file and environment variables

Defaults for `create` come from `defaults.properties`; `mps-env-gen init-defaults` writes it to the location below.
The same `~/.config` folder is used on every OS:

| OS      | File                                                         |
|---------|--------------------------------------------------------------|
| Linux   | `~/.config/mps-env-gen/defaults.properties`                  |
| macOS   | `/Users/<name>/.config/mps-env-gen/defaults.properties`      |
| Windows | `C:\Users\<name>\.config\mps-env-gen\defaults.properties`    |

`$XDG_CONFIG_HOME/mps-env-gen/defaults.properties` replaces `~/.config/...` if `XDG_CONFIG_HOME` is set; a file named in `MPS_ENV_GEN_CONFIG` replaces both.
On Windows, write paths in the file with `/` or `\\` (`installs=C:/mps`): a single `\` is an escape character in `.properties` files.

Keys are the long option names without `--`:

```properties
installs=/vol/mps
xmx=4096m
theme=dark
```

- `mps-env-gen init-defaults` writes a template with every option commented out.
  `--print` prints it instead, `--force` replaces an existing file and keeps a backup next to it.
- `copy` and `refresh` ignore the file and use the settings stored in the environment
- the environment variables `MPS_ENV_GEN_TARGET`, `MPS_ENV_GEN_INSTALLS` (fallback `MPS_BASE_PATH`), `MPS_ENV_GEN_REPOSITORY` and `MPS_ENV_GEN_CACHE` set the options of the same name
- the command line overrides environment variables, which override the defaults file

## 3.2 Defaults

Every environment starts from these assumptions.
Override them per environment (Gradle `register("<name>") { }`), for all environments (Gradle `mpsEnvironments { }`, CLI defaults file, see "3.1"), or on the command line.

| Default                                                | Gradle                                 | CLI                            |
| ------------------------------------------------------ | -------------------------------------- | ------------------------------ |
| light theme; new UI (MPS 2024.3+) with compact density | `theme.set(Theme.DARK)`                | `-e dark`                      |
| no model check before generation                       | `modelCheckBeforeGeneration.set(true)` | `-g`                           |
| update checks for MPS and plugins on                   | `updateChecks.set(false)`              | `--no-update-checks`           |
| VCS Addons merge driver popup off (still logged)       | `mergeDriverNotification.set(true)`    | `-N`                           |
| project view "Always Select Opened File" on            | `selectOpenedFile.set(false)`          | `--no-select-opened-file`      |
| heap `-Xms1024m -Xmx2048m -XX:NewRatio=4`              | `xms`, `xmx`, `ratio`                  | `-L`, `-M`                     |
| remote debugging off; port Gradle 5071, CLI 51337      | `debug.set(true)`, `debugPort`         | `-x[=PORT]`, start script `-d` |
| node URL port: first free from 63321                   | `httpPort.set(<port>)`                 | `--http-port <port>`           |

Fixed, without an option (change them in MPS, or with `vmOptions` / `--vm-option`, see "5.6"):

- no tips on startup, no "what's new" page, new UI onboarding counted as seen
- a given project is trusted and opened on first start
- no "import settings" dialog, no user agreement and data sharing consent dialogs, no freeze reports, no usage statistics

Existing environments keep their settings files; to apply a changed default, see "5.4 Changes to generated environments".

# 4. Release packages

```bash
./gradlew packageAll                     # build and test all modules, then both packages below
./gradlew packagePlugin                  # build/release/maven: plugin, plugin marker, core (Maven layout)
./gradlew packageCli                     # build/release/cli: JVM zip, runnable jar, Linux native tar.gz
./gradlew packageAll -PskipNative=true   # on non-Linux hosts (native images are built for the host OS only)
```

`build/release/cli/SHA256SUMS` lists the checksums of the CLI packages.

# 5. Generated environments

## 5.1 Downloaded MPS and cache folder

Every environment needs a runnable MPS.
With a given path or an installations folder, the available MPS is used and nothing is downloaded.
When just providing a version, any MPS from 2022.2 on is usable and will be downloaded automatically.
The itemis Nexus provides MPS only as the OS-independent zip without a Java runtime, so the tool also downloads the matching JetBrains Runtime (`jbr_jcef` for the host OS and architecture) and assembles both into a runnable MPS, `mps-<version>-<OS>`.

### 5.1.1 Locations

When downloaded, the tool puts the MPS in:

|               | Downloaded archives                                                       | Prepared MPS (`mps-<version>-<OS>`) |
| ------------- | ------------------------------------------------------------------------- | ----------------------------------- |
| Gradle plugin | Gradle's dependency cache (`~/.gradle/caches`), which the build uses also | `<cache>/distributions/`            |
| CLI           | `<cache>/downloads/`                                                      | `<cache>/distributions/`            |

One prepared MPS per version is shared by all environments, projects, the CLI and the Gradle plugin.
This saves about 1.5 GB per version and checkout, keeps throw-away environments small, and survives `git clean -xdf`.

The default cache dir can be overwritten via `cache-dir` or set to be next to the environments via `mpsInTargetPath.set(true)`.

**Cache folder** (`<cache>`):

| OS      | Default                                                         |
| ------- | --------------------------------------------------------------- |
| Linux   | `$XDG_CACHE_HOME/mps-env-gen`, otherwise `~/.cache/mps-env-gen` |
| macOS   | `~/Library/Caches/mps-env-gen`                                  |
| Windows | `%LOCALAPPDATA%\mps-env-gen\cache`                              |

- `MPS_ENV_GEN_CACHE` sets another folder for the CLI and the Gradle plugin, the CLI also has `--cache-dir`
- a prepared MPS takes about ~1.5 GB per version, plus its downloaded archives
- a prepared MPS that matches is reused and never replaced, as MPS may be running from it
- the folder can be deleted at any time: archives are downloaded again when needed, and `mps-env-gen refresh <env>` or `./gradlew generateMpsEnvironment-<Name>` prepares a missing MPS again

## 5.2 Start scripts

- `startMPS_<OS>_<name>.sh|.bat` starts MPS with the environment and needs neither Gradle nor `mps-env-gen`
- after moving an environment, the Linux/macOS scripts detect it and offer to fix the paths
- debugging is off by default.
  `-d` enables it for one start (also with tmux modes, e.g. `-d tmuxLA`), `debug`/`--debug` enables it for every start
- for debugging, the script takes the first free port from the configured one (CLI 51337, Gradle 5071) up to +20 and binds it to `127.0.0.1`.
  If none is free, MPS starts without debugging.
- debug starts use a per-start copy of the vmoptions, `mps/mps64.runtime.vmoptions`

## 5.3 Node URL port

MPS opens nodes from URLs (`http://127.0.0.1:<port>/node`) on the port in `-Dide.httpsupport.internalPort`.
The start script sets it in `mps/mps64.vmoptions` at every start and prints it:

```
Node URL port: 63321 (first free from 63321)  http://127.0.0.1:63321/node
```

| Setting                                   | Behaviour at start                                                                 |
| ----------------------------------------- | ---------------------------------------------------------------------------------- |
| not set (default)                         | uses the first free port from 63321 to 63341. If none is free, MPS is not started. |
| fixed (`httpPort` / `--http-port <port>`) | uses this port. If it is in use, MPS is not started (exit code 1).                 |

- a fixed port is stored in `environment.properties` and shown when the environment is created or refreshed.
  Without a fixed port, nothing is stored or shown.
- `mps-env-gen refresh <env> --http-port auto` returns to the default

## 5.4 Changes to generated environments

Files with MPS settings or meant for editing are written **only if missing**.
Neither Gradle nor `mps-env-gen` overwrites them unless told to:

- `mps/mps64.vmoptions`, of which the start script updates only the node URL port line and generation only the plugin block (see "5.5 Plugins")
- `mps/idea.properties`, of which only the five `idea.*.path` keys follow the location
- `mps/config/options/`: `laf.xml`, `colors.scheme.xml`, `ui.lnf.xml`, `ide.general.xml`, `other.xml`, `updates.xml`, `notifications.xml`, `projectView.xml`, `generationSettings.xml`, `recentProjects.xml`, `trusted-paths.xml`
- `mps/config/early-access-registry.txt`

Always written:

- start scripts
- `README.md`, `environment.properties`
- `created.txt`, once

A changed setting (e.g. `xmx`, `theme`) therefore only affects new environments.
To apply it to an existing one:

- Gradle: `./gradlew generateMpsEnvironment-<Name> --overwrite-settings` (the build output reports settings that were not applied)
- CLI: `mps-env-gen refresh <env> --reset-settings`, or one option, e.g. `refresh <env> --xmx 6g`

Replaced files are kept in `<env>/.backup/<timestamp>/`.
"Help > Edit Custom VM Options" in MPS edits `mps/mps64.vmoptions`, except in debug starts.

## 5.5 Plugins

Plugin zips or folders are installed into the environment's own plugin folder `mps/plugins`, next to plugins installed from within MPS.
Language libraries of a project (e.g. MPS-extensions resolved by Gradle into `.mps/libraries.xml`) do not belong here.

Declare plugins in a file, which can be checked in and is shared by Gradle and the CLI:

```properties
# mps-plugins.properties: <name>=<zip or folder>; relative paths from this file's folder, ~/ is the home folder
my-tool=../tools/my-tool-1.0-2025.1.zip
# system property for mps64.vmoptions
my-tool.property.my.tool.mode=fast
# system property holding a fixed port: the start script does not start MPS if it is in use
my-tool.port.my.tool.port=8600
# no question or warning if the compatibility with the MPS build is uncertain
my-tool.compatible=true
```

- Gradle: `pluginsFile.set(file("mps-plugins.properties"))` for all environments or per environment, and `plugins { register("<name>") { from(...) } }` (see "1.1.2 Expert Variant").
  Later declarations replace earlier ones of the same name: defaults file, defaults `plugins`, environment file, environment `plugins`.
- CLI (`create`, `copy`, `refresh`): `--plugins-file FILE`, `--plugin [NAME=]PATH`, `--plugin-property NAME:KEY=VALUE`, `--plugin-port NAME:KEY=PORT`, `--plugin-compatible NAME`, `--reinstall-plugins`.
  `copy` and `refresh` also accept `--remove-plugin NAME` and `--no-plugins`, and `refresh` refuses to change plugins while MPS runs.
  `plugins-file=` in the defaults file applies to every `create`.

Compatibility is checked against the MPS build before anything is written:

| `plugin.xml`                                                                                                            | Result                                                                                                                                 |
| ----------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `since-build` later than the MPS build, or `until-build` earlier                                                        | error, point to the build of the plugin for this MPS version                                                                           |
| no `until-build` and built for another MPS series (e.g. `since-build="251"` on MPS 2026.1), or no readable `plugin.xml` | uncertain: the CLI asks `Install anyway? [y/N]` (without a terminal: error, use `--plugin-compatible`), Gradle installs with a warning |

Installation:

- Unchanged plugins are kept; replaced zips, changed folders and missing plugin folders are installed again.
- Plugins no longer declared are removed. Folders of plugins installed otherwise are never touched; a declared plugin does not replace one unless `--reinstall-plugins` is given.
- The plugin properties are written into a block at the end of `mps/mps64.vmoptions`, rewritten on every generation, also when the file is kept. They win over the same `-D` in `vmOptions`.
- Warnings: dependencies that are neither bundled with MPS nor installed, plugins replacing a bundled one, plugins with MPS languages while the project declares libraries.
- `environment.properties` records the plugins, so `refresh`, `copy` and `rename` need no plugin options.

On every start the start script lists the plugins and checks them:

```
Plugins (managed by mps-env-gen):
  my-tool  ../tools/my-tool-1.0-2025.1.zip  (com.example.tool, my.tool.port=8600)
WARNING: plugin 'my-tool': folder plugins/my-tool is missing; restore: mps-env-gen refresh /path/to/env
Other plugins in mps/plugins: installed-from-mps
```

It warns about missing plugin folders, plugins disabled in MPS (`config/disabled_plugins.txt`) and the warnings of the installation, and does not start MPS if a plugin port is in use.

## 5.6 vmoptions defaults

`mps/mps64.vmoptions` combines the MPS defaults with those of the earlier bash tool mps-zookeeper.
The reasons are the assumptions behind them, taken over and not re-verified for every MPS version:

| Option                                                                                               | Why                                                                                                                                                                                   |
| ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `-Xms1024m`, `-Xmx2048m`, `-XX:NewRatio=4`                                                           | configurable (`xms`, `xmx`, `ratio`)                                                                                                                                                  |
| `-XX:ReservedCodeCacheSize=1024m`                                                                    | larger than MPS' 512m, because MPS compiles a lot of generated code                                                                                                                   |
| `-Xss1024k`, `-XX:NewSize=256m`                                                                      | deep model/generator recursion, young generation for short-lived model objects                                                                                                        |
| `-Dintellij.config.imported.in.current.session=true`, `-Didea.initially.ask.config=true`             | no "import settings" dialog                                                                                                                                                           |
| `-Dperformance.watcher.freeze.report=false`, `-Dfreeze.reporter.enabled=false`                       | no freeze reports                                                                                                                                                                     |
| `-Didea.suppress.statistics.report=true`, `-Didea.disable.collect.statistics=true`                   | no usage statistics (telemetry), regardless of the machine-wide consent in `~/.local/share/JetBrains/consentOptions`. MPS sends nothing and, from 2023.3 on, does not collect either. |
| `-Djb.consents.confirmation.enabled=false`                                                           | no data sharing consent dialog on first start; without it MPS waits for an answer on machines that never gave one (new machines, CI)                                                  |
| `-Djb.privacy.policy.text=<!--999.999-->`                                                            | no JetBrains user agreement dialog on first start: 999.999 counts as accepted. On Windows with `mps.bat` the start script passes it via `JAVA_TOOL_OPTIONS` (cmd would read `<` and `>` as redirections) |
| `-Djava.net.preferIPv4Stack=true`                                                                    | node URLs and debugging on `127.0.0.1`                                                                                                                                                |
| `-Dawt.useSystemAAFontSettings=lcd`, `-Dsun.java2d.renderer=sun.java2d.marlin.MarlinRenderingEngine` | font antialiasing and renderer as in mps-zookeeper (Linux desktops)                                                                                                                   |
| `-Didea.log.config.file=log.xml`                                                                     | log configuration as in mps-zookeeper. MPS 2022.3+ ships `log.properties` instead, the effect there is unverified.                                                                    |
| `-Dide.httpsupport.internalPort=<port>`                                                              | node URL port, see "5.3 Node URL port"                                                                                                                                                |
| `-agentlib:jdwp=...address=127.0.0.1:<port>`                                                         | debug starts only, `#`-commented otherwise                                                                                                                                            |

Add or override options with `vmOptions` (Gradle) or `--vm-option` (CLI).
Later lines win.

## 5.7 Other

### Use with git

**`git clean` deletes the environments.**

`.mpsconfig/` is ignored by git, so `git clean -xdf` removes it with all caches and settings.
`git clean -fd` (without `-x`) keeps it, and the downloaded MPS in the shared cache folder is not affected either way.
Exclude it with an alias in git settings:

```git
[alias]
    cleanxdf = clean -xdf -e .mpsconfig
```

### Only allow isolated MPS instances

To keep MPS from ever using the shared default configuration, move it away and make the folder read-only:

```bash
mv ~/.config/JetBrains ~/.config/JetBrains.bak
mkdir ~/.config/JetBrains
chmod a-w ~/.config/JetBrains
```

Warning: This also affects other JetBrains IDEs (e.g. IntelliJ IDEA), which then need an isolated configuration too.

# 6. Development

## 6.1 Commit messages

Commit messages follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/).
Enable the check once per clone (safe to run again):

```bash
./gradlew installGitHooks        # same as: git config core.hooksPath .githooks
```

Format: `<type>[(<scope>)][!]: <description>`, e.g. `fix(core): open macOS packages for mps.sh`.

- types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`
- scopes (optional): `core`, `cli`, `gradle-plugin`, `example`, `docs`, `ci`, `build`
- header at most 72 characters, description starts lowercase without a trailing dot
- a body follows after a blank line; breaking changes get `!` or a `BREAKING CHANGE:` footer
- git's own `Merge ...`, `Revert "..."` and `fixup!`/`squash!`/`amend!` messages pass locally

CI checks every new commit with the same script (`.githooks/commit-msg`) and rejects `fixup!`/`squash!`/`amend!` commits.
Test the script with `sh .githooks/test-commit-msg.sh`.

## 6.2 Release notes

The release workflow writes the text of each GitHub release with [git-cliff](https://git-cliff.org) (`cliff.toml`).
It lists the commits since the previous `vX.Y.Z` tag: breaking changes first, then features, bug fixes, performance, refactoring and documentation.
`chore`, `ci`, `build`, `style`, `test` and `revert` commits, merge commits and non-conventional commits are left out.
`-SNAPSHOT` tags get no GitHub release, and their commits count towards the next release.

Preview the notes of the next release (needs git-cliff on the `PATH`); the preview also lists the hidden types in an "Other" section:

```bash
./gradlew previewChangelog
```

## 6.3 Smoke test

The smoke test starts MPS for real through a generated environment, which the unit tests (fake launchers) cannot.
CI (`.github/workflows/smoke.yml`) runs it on Linux, macOS and Windows for MPS 2022.2.4 and 2026.1 on every push and pull request.

Each run generates an environment with the CLI built from the repository, opens a copy of the sample project (`smoke-test/mps-2022.2`, `smoke-test/mps-2026.1`) and asks MPS to open a node via its node URL.
It passes when MPS reports the node as opened, and fails fast on `Start Failed`, an exiting MPS or a node that cannot be found.

Run it locally (Linux uses Xvfb, `SMOKE_SHOW=1` shows the window):

```bash
./gradlew :cli:installDist
smoke-test/smoke.sh 2026.1 smoke-test/mps-2026.1
smoke-test/smoke.sh 2022.2.4 smoke-test/mps-2022.2
```

On Windows: `pwsh smoke-test/smoke.ps1 2026.1 smoke-test/mps-2026.1`.
Options (timeout, node reference, work folder) are listed at the top of `smoke-test/smoke.sh`.
