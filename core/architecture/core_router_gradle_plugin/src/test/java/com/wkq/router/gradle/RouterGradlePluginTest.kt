package com.wkq.router.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RouterGradlePluginTest {

    @Test
    fun pluginCanBeResolvedByGradleTestKit() {
        val projectDir = createTempDirectory(prefix = "router-plugin-test").toFile()
        File(projectDir, "settings.gradle").writeText(
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                    maven { url = uri("https://jitpack.io") }
                }
            }
            rootProject.name = "router-plugin-test"
            """.trimIndent()
        )
        File(projectDir, "build.gradle").writeText(
            """
            plugins {
                id "com.wkq.router"
            }

            tasks.register("printRouterModuleName") {
                doLast {
                    println("moduleName=" + wkqRouter.moduleName.get())
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("printRouterModuleName", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":printRouterModuleName")?.outcome)
        assert(result.output.contains("moduleName=router_plugin_test"))
    }
}
