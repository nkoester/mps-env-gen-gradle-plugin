package de.itemis.mps.envgen.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BasicUnitTests {

    @Test
    fun `registers tasks without afterEvaluate`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("de.itemis.mps.mps-env-gen-gradle-plugin")
        val extension = project.extensions.getByType(MpsEnvironmentsExtension::class.java)

        assertNotNull(project.tasks.findByName("generateMpsEnvironmentAll"))
        extension.environments.register("dev") { xmx.set("4g") }
        val task = project.tasks.getByName("generateMpsEnvironment-Dev") as GenerateMpsEnvironmentTask
        assertEquals("4g", task.xmx.get())
        assertEquals("1024m", task.xms.get())
    }
}
