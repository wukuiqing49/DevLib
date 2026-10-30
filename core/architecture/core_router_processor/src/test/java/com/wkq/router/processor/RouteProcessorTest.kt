package com.wkq.router.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
class RouteProcessorTest {

    @Test
    fun generatesRouteBootstrapFiles() {
        val compilation = compile(
            sources = listOf(
                SourceFile.kotlin(
                    "DemoRoute.kt",
                    """
                    package test.demo

                    import com.wkq.router.annotation.Interceptor
                    import com.wkq.router.annotation.Param
                    import com.wkq.router.annotation.ProvideService
                    import com.wkq.router.annotation.Route
                    import com.wkq.router.api.IInterceptor
                    import com.wkq.router.api.InterceptorCallback
                    import com.wkq.router.api.Postcard

                    @Route("/demo/home")
                    class HomePage {
                        @Param
                        var name: String = ""
                    }

                    interface DemoService {
                        fun name(): String
                    }

                    @ProvideService(DemoService::class)
                    class DemoServiceImpl : DemoService {
                        override fun name(): String = "demo"
                    }

                    @Interceptor(priority = 9)
                    class DemoInterceptor : IInterceptor {
                        override fun process(postcard: Postcard, callback: InterceptorCallback) {
                            callback.onContinue(postcard)
                        }
                    }
                    """.trimIndent()
                )
            ),
            moduleName = "demo"
        )

        val result = compilation.result
        assertEquals(result.messages, KotlinCompilation.ExitCode.OK, result.exitCode)
        val generatedDir = compilation.kspSourcesDir
        assertFileContains(
            generatedDir,
            "kotlin/com/wkq/router/generated/RouteInit_demo.kt",
            "registerGroup(\"demo\"",
            "registerServiceProvider(DemoService::class.java) { DemoServiceImpl() }",
            "registerInterceptor(9, DemoInterceptor())"
        )
        assertFileContains(
            generatedDir,
            "kotlin/com/wkq/router/generated/RouteGroup_demo_demo.kt",
            "register(\"/demo/home\", HomePage::class.java)"
        )
        assertFileContains(
            generatedDir,
            "kotlin/com/wkq/router/generated/RouterPaths_demo.kt",
            "const val DEMO_HOME: String = \"/demo/home\""
        )
        assertFileContains(
            generatedDir,
            "kotlin/test/demo/HomePage_Syringe.kt",
            "t.name = extras.getString(\"name\") ?: t.name"
        )
        assertFileContains(
            compilation.kspResourceDir,
            "sources/resources/META-INF/services/com.wkq.router.api.IRouteInit",
            "com.wkq.router.generated.RouteInit_demo"
        )
    }

    @Test
    fun reportsMissingModuleName() {
        val result = compile(
            sources = listOf(
                SourceFile.kotlin(
                    "MissingModuleName.kt",
                    """
                    package test.demo

                    import com.wkq.router.annotation.Route

                    @Route("/demo/missing")
                    class MissingModuleName
                    """.trimIndent()
                )
            ),
            moduleName = null
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.result.exitCode)
        assertTrue(
            result.result.messages,
            result.result.messages.contains("AndroidCoreRouters: KSP option moduleName is required")
        )
    }

    @Test
    fun reportsDuplicateRoutePath() {
        val result = compile(
            sources = listOf(
                SourceFile.kotlin(
                    "DuplicateRoute.kt",
                    """
                    package test.demo

                    import com.wkq.router.annotation.Route

                    @Route("/demo/dup")
                    class FirstPage

                    @Route("/demo/dup")
                    class SecondPage
                    """.trimIndent()
                )
            ),
            moduleName = "demo"
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.result.exitCode)
        assertTrue(
            result.result.messages,
            result.result.messages.contains("AndroidCoreRouters: duplicate route path found: /demo/dup")
        )
    }

    private fun compile(
        sources: List<SourceFile>,
        moduleName: String?
    ): CompileResult {
        val compilation = KotlinCompilation().apply {
            this.sources = sources + routerApiStub + androidBundleStub
            classpaths = System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .map(::File)
                .filterNot { it.invariantSeparatorsPath.contains("core_router_processor/build/classes") }
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders.add(routeProcessorProvider(moduleName))
                if (moduleName != null) {
                    processorOptions["moduleName"] = moduleName
                }
                withCompilation = false
            }
        }
        return CompileResult(compilation, compilation.compile())
    }

    private fun routeProcessorProvider(moduleName: String?): SymbolProcessorProvider {
        return object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
                val options = moduleName?.let { mapOf("moduleName" to it) } ?: emptyMap()
                return RouteProcessor(environment.codeGenerator, environment.logger, options)
            }
        }
    }

    private fun assertFileContains(root: File, relativePath: String, vararg expected: String) {
        val file = File(root, relativePath)
        assertTrue(
            "Generated file missing: ${file.absolutePath}\nGenerated files:\n${root.generatedFileList()}",
            file.exists()
        )
        val text = file.readText()
        expected.forEach { value ->
            assertTrue("Expected generated file $relativePath to contain: $value\n$text", text.contains(value))
        }
    }

    private fun File.generatedFileList(): String {
        if (!exists()) return "<root missing: $absolutePath>"
        return walkTopDown()
            .filter { it.isFile }
            .joinToString(separator = "\n") { it.relativeTo(this).invariantSeparatorsPath }
            .ifBlank { "<empty>" }
    }

    private val routerApiStub = SourceFile.kotlin(
        "RouterApiStub.kt",
        """
        package com.wkq.router.api

        interface IRouteInit {
            fun init()
        }

        interface IRouteGroup {
            fun load()
        }

        interface ISyringe {
            fun inject(target: Any)
        }

        class Postcard

        interface InterceptorCallback {
            fun onContinue(postcard: Postcard)
            fun onInterrupt(throwable: Throwable)
        }

        interface IInterceptor {
            fun process(postcard: Postcard, callback: InterceptorCallback)
        }

        object RouteTable {
            fun register(path: String, clazz: Class<*>) = Unit
            fun registerGroup(group: String, clazz: Class<*>) = Unit
            fun <T : Any> registerServiceProvider(api: Class<T>, provider: () -> T) = Unit
            fun registerInterceptor(priority: Int, interceptor: IInterceptor) = Unit
        }
        """.trimIndent()
    )

    private val androidBundleStub = SourceFile.kotlin(
        "Bundle.kt",
        """
        package android.os

        class Bundle {
            fun containsKey(key: String): Boolean = false
            fun getString(key: String): String? = null
            fun getInt(key: String, defaultValue: Int): Int = defaultValue
            fun getLong(key: String, defaultValue: Long): Long = defaultValue
            fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
            fun getFloat(key: String, defaultValue: Float): Float = defaultValue
            fun getDouble(key: String, defaultValue: Double): Double = defaultValue
        }
        """.trimIndent()
    )

    private data class CompileResult(
        val compilation: KotlinCompilation,
        val result: JvmCompilationResult
    ) {
        val kspSourcesDir: File get() = compilation.kspSourcesDir
        val kspResourceDir: File get() = compilation.kspSourcesDir.parentFile ?: compilation.kspSourcesDir
    }
}
