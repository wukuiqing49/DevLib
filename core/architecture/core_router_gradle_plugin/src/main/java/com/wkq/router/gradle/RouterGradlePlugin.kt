package com.wkq.router.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property

abstract class RouterExtension {
    abstract val moduleName: Property<String>
    abstract val version: Property<String>
    abstract val group: Property<String>
    abstract val addApiDependency: Property<Boolean>
    abstract val addProcessorDependency: Property<Boolean>
}

class RouterGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("wkqRouter", RouterExtension::class.java)
        extension.moduleName.convention(defaultModuleName(project))
        extension.version.convention(
            project.providers.gradleProperty("androidCoreRoutersVersion")
                .orElse(project.providers.gradleProperty("routerVersion"))
                .orElse("0.1.0-SNAPSHOT")
        )
        extension.group.convention(
            project.providers.gradleProperty("androidCoreRoutersGroup")
                .orElse("com.wkq.dev")
        )
        extension.addApiDependency.convention(true)
        extension.addProcessorDependency.convention(true)

        project.pluginManager.apply("com.google.devtools.ksp")

        project.plugins.withId("com.android.application") {
            configureRouter(project, extension)
        }
        project.plugins.withId("com.android.library") {
            configureRouter(project, extension)
        }
    }

    private fun configureRouter(project: Project, extension: RouterExtension) {
        // 在 KSP 的 afterEvaluate 检查前注册依赖，值仍延迟到宿主配置完成后读取。
        project.configurations.named("implementation") { configuration ->
            configuration.dependencies.addAllLater(project.provider {
                if (extension.addApiDependency.get()) listOf(project.dependencies.create(
                    "${extension.group.get()}:core-router-api:${extension.version.get()}"
                )) else emptyList()
            })
        }
        project.configurations.named("ksp") { configuration ->
            configuration.dependencies.addAllLater(project.provider {
                if (extension.addProcessorDependency.get()) listOf(project.dependencies.create(
                    "${extension.group.get()}:core-router-processor:${extension.version.get()}"
                )) else emptyList()
            })
        }
        project.afterEvaluate {
            configureKspModuleName(project, extension.moduleName.get())
        }
    }

    private fun configureKspModuleName(project: Project, moduleName: String) {
        val kspExtension = project.extensions.findByName("ksp")
        if (kspExtension != null) {
            val argMethod = kspExtension.javaClass.methods.firstOrNull { method ->
                method.name == "arg" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.parameterTypes[1] == String::class.java
            }
            if (argMethod != null) {
                argMethod.invoke(kspExtension, "moduleName", moduleName)
                return
            }
        }

        project.logger.warn(
            "AndroidCoreRouters: KSP extension was not found. " +
                "Make sure com.google.devtools.ksp is available in pluginManagement."
        )
    }

    private fun defaultModuleName(project: Project): String {
        return project.path
            .trim(':')
            .replace(':', '_')
            .let { value ->
                val normalized = value.ifBlank { project.name }
                    .replace(Regex("[^A-Za-z0-9_]"), "_")
                if (normalized.firstOrNull()?.isLetter() == true) normalized else "m_$normalized"
            }
    }
}
