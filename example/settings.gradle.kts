pluginManagement {
  repositories {
      maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
      mavenLocal()
      gradlePluginPortal()

  }
}

plugins {
  // Apply the foojay-resolver plugin to allow automatic download of JDKs
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "mps-env-gen-gradle-plugin-example"
