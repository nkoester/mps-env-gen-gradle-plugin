# Handover (2026-11-04)

State of the work for continuing in a new session.
Decisions and open items: `BACKLOG.md`; user documentation: `README.md`.
`BACKLOG.md`, `HANDOVER.md` and `version.txt` are local only and never committed.

## Next action
1. User: `./gradlew installCli -Pprefix=~/local` and `source <(mps-env-gen completion bash)`, then try `mps-env-gen create 2025.1.4 -t -s`
2. Me: run a full `./gradlew build` on the uncommitted state (only `:cli:test` ran this session: 23/23 green)
3. User commits (I print a message only when asked)
4. Then: fix release publishing (see "Build setup gaps", item 1); open question to the user: releases only from `v*` tags, or also snapshots from `main`?

## Status
- HEAD `64d3d78` (shared cache, ports, start output, docs); `CacheDirTest.kt` is committed (older note was stale)
- `version.txt` (untracked) = `0.1.0-SNAPSHOT`, overrides git based versioning
- Uncommitted, all tested per module:
  - from 2026-10-30: `updateChecks` setting (core `EnvironmentSpec.updateChecks`, `EnvironmentPart.UPDATES` for `updates.xml`, CLI `--update-checks/--no-update-checks`, Gradle `updateChecks`), default on;
    hidden lock file `.<name>.lock`; 2025.2.1 dropped from the completion list (`JbrResolver.UNSUPPORTED` now public, filtered in `updateMpsVersions`);
    `example/` only extracts MPS extensions, fixed port 63400 only in `1-special`
  - 2026-11-04 bash completion fix: Clikt runs custom candidates via `compgen -F`, where bash sets `COMP_CWORD=-1`;
    `INSTALLS_COMPLETION` now reads the caller's local `word` (dynamic scoping), fallback `COMP_WORDS[COMP_CWORD]`.
    Depends on Clikt's internal variable name (clikt-core 5.1.0); guarded by test `bash completes the version with options after the cursor`
  - 2026-11-04 short options for every long option (table below); `--start`/`--debug` take their optional value only attached
    (`acceptsUnattachedValue = false`, plus stripping the `=` Clikt keeps in `-s=MODE`/`-x=PORT`), so options and arguments may come in any order.
    `--start tmuxD` / `--debug 5005` with a space no longer work (accepted: no users). Test `short options in any order, optional values only attached`; README section 3 updated
- Test class in `modules/cli/src/test/.../CliTest.kt` is named `CommandsTest` (Gradle filter: `--tests '*CommandsTest*'`)

## CLI short options
- all environment commands: `-T --target`, `-C --copy`, `-v --verbose`, `-h --help`
- create/copy/refresh: `-t --temp`, `-n --name` (create); `-s --start[=MODE]`, `-d --start-debug`; `-m --mps` (copy, refresh);
  `-i --installs`, `-o --no-download`, `-r --repository`, `-c --cache-dir`, `-I --mps-in-target`, `-j --jbr-version`;
  `-p --project`, `-J --java-home`, `-x --debug[=PORT]`, `-S --debug-suspend`, `-P --http-port`, `-e --theme`, `-L --xms`, `-M --xmx`,
  `-O --vm-option`, `-D --idea-property`, `-g --model-check-before-generation`, `-u --update-checks`
- copy `-l --into`, `-w --with-caches`; rename `-f --force`; refresh `-R --reset-settings`; init-defaults `-p --print`, `-f --force`; root `-V --version`
- `--no-*` negations have no short form (except `--no-download`); defaults file keys stay the long names (`ValueSource.getKey()`)

## Layout
```
modules/core           de.itemis.mps.envgen[.distribution|.environment]   Gradle-free, published as de.itemis.mps.envgen:mps-env-gen-core
modules/cli            de.itemis.mps.envgen.cli                            CLI "mps-env-gen" (clikt-core 5.1.0), Java 11+
modules/gradle-plugin  de.itemis.mps.envgen.gradle                         plugin id de.itemis.mps.mps-env-gen-gradle-plugin
example/               separate Gradle build using the plugin (from mavenLocal)
```
- settings.gradle.kts maps flat project paths (`:core`, `:cli`, `:gradle-plugin`) to `modules/<name>`
- Gradle 9.8.2 (both builds); Kotlin = Gradle's embedded 2.4.10; `core` pinned to Kotlin API/language 2.0 + stdlib 2.0.21 so the plugin works in Gradle 8.14 consumers; `core`/`cli` toolchain 11, plugin 17
- Minimum supported consumer: Gradle 8.14 on Java 17, Kotlin and Groovy DSL

## Key behaviour
- **Runnable MPS from Nexus** (2022.2+): generic zip + JBR (`jbr_jcef`), layout per OS/arch, JBR from marker POM > built-in table (2022.2–2023.3) > same build line; 2025.2.1 needs `jbrVersion`
- **Shared cache** (`CacheDir.kt`): `MPS_ENV_GEN_CACHE` > Linux `$XDG_CACHE_HOME`/`~/.cache` + `/mps-env-gen`, macOS `~/Library/Caches/mps-env-gen`, Windows `%LOCALAPPDATA%\mps-env-gen\cache`
  - CLI and Gradle prepare MPS in `<cache>/distributions/mps-<version>-<OS>`; CLI downloads in `<cache>/downloads`; Gradle downloads in its dependency cache
  - Gradle `mpsInTargetPath.set(true)` keeps it in `targetPath`; CLI `--cache-dir` / defaults file `cache-dir=`
  - `DistributionPreparer.prepareOrReuse`: hidden lock file `.<name>.lock` next to the distribution (stays by design), a matching distribution is never replaced
- **Node URL port**: nothing stored by default; start scripts take the first free port 63321–63341 at every start; a fixed port (`--http-port`/`httpPort`) must be free, otherwise exit 1; `refresh --http-port auto` returns to the default
- **Start scripts**: self-contained; Linux prints "Calling MPS:" one part per line (`%q`, still runnable); macOS same without escaping; Windows equivalent (never executed)
- **User-owned files written only if missing** (vmoptions, idea.properties except path keys, option files incl. `updates.xml`, `config/early-access-registry.txt`); explicit overwrite: Gradle `--overwrite-settings`, CLI `refresh --reset-settings` or the setting's option
- **First start**: no What's New, new UI for 242+, `early-access-registry.txt` with `ide.experimental.ui=true` (prevents the theme reset deadlock in MPS 2026.1)
- **Usage statistics off** in the vmoptions template; update checks on by default (user decision), switchable
- **`.mpsconfig/.gitignore`** with `*` is written once by the generator
- **CLI**: commands `create | copy | rename | refresh | init-defaults | completion`; defaults file `~/.config/mps-env-gen/defaults.properties` applies to `create` only;
  completion of `<mps>` from the installations folder, else the packaged list `modules/cli/src/main/resources/mps-versions.txt`

## Discussed, not decided
- **TOML instead of `.properties` for the defaults file**: only worth it for real lists (`vm-option`) or per-command sections (`[copy]`, `[refresh]`);
  needs a custom Clikt `ValueSource` + `tomlj`. Recommendation: stay with properties until one of those is wanted. Waiting for the user

## Build setup gaps (analysed 2026-11-04)
1. **Release publishing broken**: tag builds are detached HEAD → `gitBranch()` = `HEAD` → `itemisCloud` repo not configured → nothing reaches Nexus;
   `publish.yml` does not run `packageAll`, no GitHub Release / CLI assets; unused `NEXUS_*` secrets; tag pattern `'**'`
2. CLI jar not published to Maven (`jbang` open)
3. CI Linux only: no Windows/macOS matrix, `example/` not built, native image not built, no test report upload; `build.yaml` step says "JDK 11" but sets up 17
4. Milestone 4: CI smoke test starting MPS per OS (Windows `.bat`/macOS scripts never executed)
5. Hygiene: JUnit 5.9.0 → BOM, Renovate/Dependabot, Gradle 10 deprecation warnings unexamined, no config/build cache in `gradle.properties`, thin POM metadata, not on Gradle Plugin Portal

## Other backlog (see BACKLOG.md)
- plugin folders redesign, warmup spike (indexing before first start), Gradle task inputs fingerprint whole MPS installs
- cleanup on request: `/tmp/mps-spike` (6.9 GB), old `/tmp/mps-env-gen/*`, old `~/Downloads/mps-env-gen`, `/tmp/claude-compl-test` (this session's pty test driver)

## README
- Numbered sections, one sentence per line, few semicolons, verbs in bullets and headings
- Usage examples with real console output (user fills them in), commands start with `$`
- Cross-references quoted with section numbers ("5.3 Node URL port"); renumber by hand when sections move

## Build tasks
- `./gradlew build` – all modules, tests
- `./gradlew buildCli` / `:cli:fatJar` → `modules/cli/build/libs/mps-env-gen-<v>-all.jar`
- `./gradlew buildNativeCli` – optional, GraalVM (Mandrel 25) via foojay
- `./gradlew installCli|installNativeCli|uninstallCli -Pprefix=~/local`
- `./gradlew :cli:updateMpsVersions` – refreshes the packaged MPS version list (network; commit the result)
- `./gradlew packageAll [-PskipNative=true]` → `build/release/{cli,maven}` (+ SHA256SUMS)

## Working agreements with the user
- **Do not run install tasks** (`installCli`, `installNativeCli`, `uninstallCli`, `publishToMavenLocal`) – tell the user the command
- **No co-author / Claude lines in commit messages**; the user commits
- **Propose and wait for a go** before starting the next step
- No users yet: breaking changes need no migration/deprecation/changelog
- Don't start MPS; the user verifies startup
- Before deleting anything, look at it first; never delete the user's files in `/tmp` without asking
- Concise answers, bullets, no trailing dots, lead with the next action (ADHD mode in global CLAUDE.md); verify with real command output
- The user edits README and example in parallel: re-read before editing
- Bash completion can be tested in a pty: `/tmp/claude-compl-test/drive.py <script> "<line>" <cursor-left-count>`
