package de.itemis.mps

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test


class BasicUnitTests {

    @Test
    fun pluginRegistersATask() {
        // Create a test project and apply the plugin
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("de.itemis.mps.mps-env-gen-gradle-plugin")

        // Verify the result
        Assertions.assertNotNull(project.tasks.findByName("generateMpsEnvironmentAll"))
    }

}