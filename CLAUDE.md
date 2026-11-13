# CLAUDE.md

Generates isolated, self-contained JetBrains MPS environments (config, plugins, start scripts per OS), optionally with a
runnable MPS + JBR downloaded from Nexus. Two front ends on one Gradle-free core: a Gradle plugin and a CLI (`mps-env-gen`).

Read on demand, not up front. Start with the row matching the task.

## Where to find what

| Need                                       | Look at                                                                                     |
|--------------------------------------------|---------------------------------------------------------------------------------------------|
| User-facing behaviour, options, examples   | `README.md` (numbered sections; grep the heading list `^#` first)                           |
| Gradle execution flow                      | `docs/gradle-flow.md` (sequence diagram)                                                    |
| Decisions, designs, open features          | `BACKLOG.md` (local only, untracked; may be absent)                                         |
| Known bugs, ranked                         | `CODEANALYSIS.md` (local only; findings may be fixed already, verify in code)               |
| Session state, working agreements          | `HANDOVER.md` (local; may be stale, check `git log`)                                        |
| Build logic, versioning, release packaging | `build.gradle.kts` (root), `settings.gradle.kts` (maps `:core` etc. to `modules/<name>`)    |
| Dependency versions                        | `gradle/libs.versions.toml`                                                                 |
| CI / release                               | `.github/workflows/{build.yaml,release.yml,smoke.yml}`, `cliff.toml`                        |
| Commit message rules                       | `.githooks/commit-msg`, README "6.1 Commit messages"                                        |

## Code map

`modules/core` (`de.itemis.mps.envgen`, Gradle-free, Java 11, Kotlin API 2.0 so the plugin runs on Gradle 8.14)
- `distribution/`: Nexus MPS + JBR preparation; OS/arch table `Platform.kt`, layout and launcher rule `DistributionLayout.kt`,
  `JbrResolver.kt`, `DistributionPreparer.kt`, `Archives.kt`
- `environment/`: `EnvironmentGenerator.kt` (writes everything), `EnvironmentMetadata.kt` (`environment.properties`), `Ports.kt`
- `plugins/`: plugin installation, plugin file, vmoptions block, start script snippets
- `src/main/resources/templates/`: start scripts (`linux/`, `mac/`, `win/`), `mps/` config files
- `CacheDir.kt`: shared cache location

`modules/cli` (`de.itemis.mps.envgen.cli`, clikt-core): `Commands.kt` (all commands), `Options.kt`, `MpsResolver.kt`,
`NexusArtifactSource.kt`; `src/main/resources/mps-versions.txt` (completion list, refresh via `:cli:updateMpsVersions`)

`modules/gradle-plugin` (`de.itemis.mps.envgen.gradle`, Java 17): `MpsEnvironmentGenerationPlugin.kt` (wiring),
`MpsEnvironmentsExtension.kt` (DSL), `GenerateMpsEnvironmentTask.kt`, `PrepareMpsDistributionTask.kt`, `DistributionTasks.kt`

`example/`: separate Gradle build consuming the released plugin

## Build and test

- Narrowest first: `./gradlew :core:test`, `:cli:test`, `:gradle-plugin:test`, filter with `--tests '*Name*'`
- CLI tests live in `CliTest.kt` but the class is `CommandsTest`
- Start scripts are executed against fake launchers in `StartScriptTest` (gradle-plugin) and `PluginStartScriptTest` (core);
  `.bat` files are only generated, never executed; macOS scripts run on Linux in tests
- Hook script tests: `sh .githooks/test-commit-msg.sh`; release notes preview: `./gradlew previewChangelog` (needs git-cliff)
- Real MPS start: `smoke-test/smoke.sh <version> smoke-test/mps-<series>` (README "6.3 Smoke test"); starts MPS, so only
  run it when the user asks; CI runs it on 3 OS (`.github/workflows/smoke.yml`)
- Configuration cache is on with `problems=fail`: new tasks must be compatible or declare otherwise

## Rules

- Never publish, tag, push or release; never run install tasks (`installCli`, `installNativeCli`, `uninstallCli`,
  `publishToMavenLocal`, `installGitHooks`); print the command for the user instead
- Propose and wait for a go before acting on a plan; do not start the next step on your own
- Never start MPS; the user verifies startups
- Commit messages: Conventional Commits as enforced by `.githooks/commit-msg`; no co-author or tool attribution lines
- Never commit `BACKLOG.md`, `HANDOVER.md`, `CODEANALYSIS.md`, `version.txt`
- Non-public tools or plugins never appear in tracked files; use neutral stand-ins (`my-tool`, `com.example.tool`)
- `README.md` and `example/` are edited by the user in parallel: re-read before editing
- User-owned generated files (`mps64.vmoptions`, `idea.properties`, option XMLs) are written only if missing; keep that contract
- No users yet: breaking changes need no migration path
- MPS internals: verify against the distribution (`~/.cache/mps-env-gen/distributions/`) instead of assuming
