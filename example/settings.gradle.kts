pluginManagement {
  repositories {
      maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
      // released plugin; GitHub Packages needs a classic token with read:packages, even for public repositories:
      // gpr.user and gpr.token in ~/.gradle/gradle.properties
      // maven {
      //     name = "gitHubPackages"
      //     url = uri("https://maven.pkg.github.com/nkoester/mps-env-gen-gradle-plugin")
      //     credentials {
      //         username = providers.gradleProperty("gpr.user").orNull
      //         password = providers.gradleProperty("gpr.token").orNull
      //     }
      //     // only the plugin, its marker and core; other lookups do not hit GitHub
      //     content { includeGroup("de.itemis.mps.envgen") }
      // }
      mavenLocal()
      gradlePluginPortal()
  }
}

plugins {
  // Apply the foojay-resolver plugin to allow automatic download of JDKs
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "mps-env-gen-gradle-plugin-example"
