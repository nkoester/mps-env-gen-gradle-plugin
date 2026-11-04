# Example Project using the `mps-env-gen-gradle-plugin`

This project shows the usage of the `mps-env-gen-gradle-plugin` Gradle plugin.
`doAll` extracts the MPS extensions used by `mps-project` and generates all environments:

```shell
$ ./gradlew doAll
```

MPS itself is downloaded from Nexus in the version of `gradle/libs.versions.toml` and kept in `.mpsconfig` (`mpsInTargetPath`).
After this, you find three generated environments in `.mpsconfig`:

```shell
.mpsconfig
├── 0-default
├── 1-special
├── 2-debug
└── mps-2022.2.4-LINUX
```

Each of these environments holds startup scripts which you can simply run to directly open up MPS on the included MPS project.