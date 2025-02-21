# Example Project using the `mps-env-gen-gradle-plugin`

This project is a minimal working example showing the possible usage of the `mps-env-gen-gradle-plugin gradle plugin.
To test this example, you have to setup the project and call the environment generator:

```shell
$ ./gradlew doAll
$ ./gradlew generateMpsEnvironmentAll
```

After this, you can find three generated environments in $root/.mpsconfig:

```shell
.mpsconfig
├── 0-default
├── 1-special
└── 2-debug
```

Each of these environments holds startup scripts which you can simply run to directly open up MPS on the included MPS project.