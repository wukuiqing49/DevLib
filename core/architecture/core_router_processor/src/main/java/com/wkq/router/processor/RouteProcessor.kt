package com.wkq.router.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class RouteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()

        val routeName = "com.wkq.router.annotation.Route"
        val serviceName = "com.wkq.router.annotation.ProvideService"
        val interceptorName = "com.wkq.router.annotation.Interceptor"
        val paramName = "com.wkq.router.annotation.Param"

        val routeSymbols = resolver.getSymbolsWithAnnotation(routeName).toList()
        val serviceSymbols = resolver.getSymbolsWithAnnotation(serviceName).toList()
        val interceptorSymbols = resolver.getSymbolsWithAnnotation(interceptorName).toList()
        val paramSymbols = resolver.getSymbolsWithAnnotation(paramName).toList()

        val ret = (routeSymbols + serviceSymbols + interceptorSymbols + paramSymbols)
            .filter { !it.validate() }
            .toList()
        if (ret.isNotEmpty()) {
            return ret
        }

        validateAnnotationTargets(routeSymbols, serviceSymbols, interceptorSymbols, paramSymbols)

        val annotatedRoutes = routeSymbols.filterIsInstance<KSClassDeclaration>().toList()
        val annotatedServices = serviceSymbols.filterIsInstance<KSClassDeclaration>().toList()
        val annotatedInterceptors = interceptorSymbols.filterIsInstance<KSClassDeclaration>().toList()
        val annotatedParams = paramSymbols.filterIsInstance<KSPropertyDeclaration>().toList()

        if (
            annotatedRoutes.isEmpty() &&
            annotatedServices.isEmpty() &&
            annotatedInterceptors.isEmpty() &&
            annotatedParams.isEmpty()
        ) {
            return ret
        }

        val moduleName = options["moduleName"]
        if (moduleName.isNullOrBlank() || moduleName == "Default") {
            logger.error(
                "AndroidCoreRouters: KSP option moduleName is required.\n" +
                    "Add this to every module that uses router annotations:\n" +
                    "ksp { arg(\"moduleName\", \"feature_user\") }"
            )
            return ret
        }
        if (!moduleName.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
            logger.error(
                "AndroidCoreRouters: moduleName must match [A-Za-z][A-Za-z0-9_]*, current value: $moduleName.\n" +
                    "Example: ksp { arg(\"moduleName\", \"feature_user\") }"
            )
            return ret
        }

        validateRoutes(annotatedRoutes, routeName)
        validateServices(resolver, annotatedServices, serviceName)
        validateInterceptors(resolver, annotatedInterceptors)
        validateParams(annotatedParams)

        val packageName = "com.wkq.router.generated"
        val className = "RouteInit_$moduleName"

        generateSyringeFiles(resolver, annotatedParams)

        val routeGroups = groupRoutes(annotatedRoutes, routeName)
        generateRoutePathFile(resolver, packageName, moduleName, routeName, annotatedRoutes)
        generateRouteGroupFiles(resolver, packageName, moduleName, routeName, routeGroups)
        generateRouteInitFile(
            resolver = resolver,
            packageName = packageName,
            className = className,
            moduleName = moduleName,
            routeGroups = routeGroups,
            serviceName = serviceName,
            annotatedServices = annotatedServices,
            interceptorName = interceptorName,
            annotatedInterceptors = annotatedInterceptors
        )
        generateServiceFile(packageName, className)

        invoked = true
        return ret
    }

    private fun generateSyringeFiles(
        resolver: Resolver,
        annotatedParams: List<KSPropertyDeclaration>
    ) {
        val paramGroups = annotatedParams.groupBy { it.parentDeclaration as KSClassDeclaration }
        paramGroups.forEach { (clazz, params) ->
            val targetClassName = clazz.simpleName.asString()
            val syringeClassName = "${targetClassName}_Syringe"
            val targetPackage = clazz.packageName.asString()
            val isActivity = clazz.superTypes.any {
                it.resolve().declaration.qualifiedName?.asString()?.contains("Activity") == true
            }
            val isFragment = clazz.superTypes.any {
                it.resolve().declaration.qualifiedName?.asString()?.contains("Fragment") == true
            }

            val syringeFile = FileSpec.builder(targetPackage, syringeClassName)
                .addType(
                    TypeSpec.classBuilder(syringeClassName)
                        .addSuperinterface(ClassName("com.wkq.router.api", "ISyringe"))
                        .addFunction(
                            FunSpec.builder("inject")
                                .addModifiers(KModifier.OVERRIDE)
                                .addParameter("target", Any::class)
                                .addStatement("val t = target as %T", ClassName(targetPackage, targetClassName))
                                .apply {
                                    when {
                                        isActivity -> addStatement("val extras = t.intent?.extras ?: return")
                                        isFragment -> addStatement("val extras = t.arguments ?: return")
                                        else -> addStatement("val extras = android.os.Bundle()")
                                    }
                                }
                                .apply {
                                    addParamStatements(resolver, params, targetClassName)
                                }
                                .build()
                        )
                        .build()
                )
                .build()
            syringeFile.writeTo(codeGenerator, Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()))
        }
    }

    private fun FunSpec.Builder.addParamStatements(
        resolver: Resolver,
        params: List<KSPropertyDeclaration>,
        targetClassName: String
    ) {
        params.forEach { param ->
            val paramName = param.simpleName.asString()
            val annotation = param.annotations.find {
                it.annotationType.resolve().declaration.qualifiedName?.asString() ==
                    "com.wkq.router.annotation.Param"
            }
            val key = annotation?.arguments?.find { it.name?.asString() == "name" }?.value as? String
            val finalKey = if (key.isNullOrEmpty()) paramName else key
            val type = param.type.resolve()
            val typeName = type.declaration.qualifiedName?.asString()
            val parcelableType = resolver
                .getClassDeclarationByName(resolver.getKSNameFromString("android.os.Parcelable"))
                ?.asType(emptyList())
            val serializableType = resolver
                .getClassDeclarationByName(resolver.getKSNameFromString("java.io.Serializable"))
                ?.asType(emptyList())

            when (typeName) {
                "kotlin.String" -> addStatement(
                    "if (extras.containsKey(%S)) t.%L = extras.getString(%S) ?: t.%L",
                    finalKey,
                    paramName,
                    finalKey,
                    paramName
                )

                "kotlin.Int" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getInt(%S)", finalKey, paramName, finalKey)
                "kotlin.Boolean" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getBoolean(%S)", finalKey, paramName, finalKey)
                "kotlin.Long" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getLong(%S)", finalKey, paramName, finalKey)
                "kotlin.Float" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getFloat(%S)", finalKey, paramName, finalKey)
                "kotlin.Double" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getDouble(%S)", finalKey, paramName, finalKey)
                "kotlin.Byte" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getByte(%S)", finalKey, paramName, finalKey)
                "kotlin.Short" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getShort(%S)", finalKey, paramName, finalKey)
                "kotlin.Char" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getChar(%S)", finalKey, paramName, finalKey)
                "kotlin.IntArray" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getIntArray(%S)", finalKey, paramName, finalKey)
                "kotlin.BooleanArray" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getBooleanArray(%S)", finalKey, paramName, finalKey)
                "kotlin.LongArray" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getLongArray(%S)", finalKey, paramName, finalKey)
                "kotlin.FloatArray" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getFloatArray(%S)", finalKey, paramName, finalKey)
                "kotlin.DoubleArray" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getDoubleArray(%S)", finalKey, paramName, finalKey)
                "kotlin.ByteArray" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getByteArray(%S)", finalKey, paramName, finalKey)
                "kotlin.ShortArray" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getShortArray(%S)", finalKey, paramName, finalKey)
                "kotlin.CharArray" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getCharArray(%S)", finalKey, paramName, finalKey)
                "kotlin.collections.ArrayList", "java.util.ArrayList" -> {
                    val elementTypeName = type.arguments.firstOrNull()?.type?.resolve()
                        ?.declaration
                        ?.qualifiedName
                        ?.asString()
                    when (elementTypeName) {
                        "kotlin.String", "java.lang.String" -> addStatement(
                            "if (extras.containsKey(%S)) t.%L = extras.getStringArrayList(%S)",
                            finalKey,
                            paramName,
                            finalKey
                        )

                        "kotlin.Int", "java.lang.Integer" -> addStatement(
                            "if (extras.containsKey(%S)) t.%L = extras.getIntegerArrayList(%S)",
                            finalKey,
                            paramName,
                            finalKey
                        )

                        else -> logger.error(
                            "AndroidCoreRouters: unsupported @Param ArrayList element type $elementTypeName for $paramName in $targetClassName. " +
                                "Supported ArrayList element types: String, Int.",
                            param
                        )
                    }
                }
                "android.os.Bundle" -> addStatement("if (extras.containsKey(%S)) t.%L = extras.getBundle(%S)", finalKey, paramName, finalKey)
                else -> {
                    if (parcelableType != null && parcelableType.isAssignableFrom(type)) {
                        addStatement("if (extras.containsKey(%S)) t.%L = extras.getParcelable(%S)", finalKey, paramName, finalKey)
                    } else if (serializableType != null && serializableType.isAssignableFrom(type)) {
                        addStatement(
                            "if (extras.containsKey(%S)) t.%L = extras.getSerializable(%S) as? %T",
                            finalKey,
                            paramName,
                            finalKey,
                            ClassName.bestGuess(typeName ?: "java.lang.Object")
                        )
                    } else {
                        logger.error(
                            "AndroidCoreRouters: unsupported @Param type $typeName for $paramName in $targetClassName. " +
                                "Supported types: primitives, primitive arrays, String, Array<String>, Bundle, Parcelable, Serializable, ArrayList<String>, ArrayList<Int>.",
                            param
                        )
                    }
                }
            }
        }
    }

    private fun groupRoutes(
        annotatedRoutes: List<KSClassDeclaration>,
        routeName: String
    ): Map<String, List<KSClassDeclaration>> {
        return annotatedRoutes.groupBy { clazz ->
            val annotation = clazz.annotations.find {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == routeName
            }
            val path = annotation?.arguments?.find { it.name?.asString() == "path" }?.value as? String ?: ""
            val group = if (path.startsWith("/")) {
                path.substring(1).substringBefore("/")
            } else {
                path.substringBefore("/")
            }
            if (group.isEmpty()) "default" else group
        }
    }

    private fun generateRouteGroupFiles(
        resolver: Resolver,
        packageName: String,
        moduleName: String,
        routeName: String,
        routeGroups: Map<String, List<KSClassDeclaration>>
    ) {
        routeGroups.forEach { (groupName, classes) ->
            val groupClassName = "RouteGroup_${moduleName}_$groupName"
            val groupFileSpec = FileSpec.builder(packageName, groupClassName)
                .addType(
                    TypeSpec.classBuilder(groupClassName)
                        .addSuperinterface(ClassName("com.wkq.router.api", "IRouteGroup"))
                        .addFunction(
                            FunSpec.builder("load")
                                .addModifiers(KModifier.OVERRIDE)
                                .apply {
                                    classes.forEach { clazz ->
                                        val annotation = clazz.annotations.find {
                                            it.annotationType.resolve().declaration.qualifiedName?.asString() == routeName
                                        }
                                        val path = annotation?.arguments?.find { it.name?.asString() == "path" }?.value as? String
                                        if (path != null) {
                                            val qualifiedName = clazz.qualifiedName?.asString() ?: ""
                                            addStatement(
                                                "com.wkq.router.api.RouteTable.register(%S, %T::class.java)",
                                                path,
                                                ClassName.bestGuess(qualifiedName)
                                            )
                                        }
                                    }
                                }
                                .build()
                        )
                        .build()
                )
                .build()
            groupFileSpec.writeTo(codeGenerator, Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()))
        }
    }

    private fun generateRoutePathFile(
        resolver: Resolver,
        packageName: String,
        moduleName: String,
        routeName: String,
        routes: List<KSClassDeclaration>
    ) {
        if (routes.isEmpty()) return

        val constants = routes.mapNotNull { clazz ->
            val annotation = clazz.annotations.find {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == routeName
            }
            val path = annotation?.arguments?.find { it.name?.asString() == "path" }?.value as? String
            path?.let { it to toConstantName(it) }
        }

        if (constants.isEmpty()) return
        constants.groupBy { it.second }
            .filterValues { it.size > 1 }
            .forEach { (name, values) ->
                logger.error(
                    "AndroidCoreRouters: generated route constant $name is duplicated by paths: " +
                        values.joinToString { it.first }
                )
            }

        val typeBuilder = TypeSpec.objectBuilder("RouterPaths_$moduleName")
        constants.forEach { (path, name) ->
            typeBuilder.addProperty(
                PropertySpec.builder(name, String::class)
                    .addModifiers(KModifier.CONST)
                    .initializer("%S", path)
                    .build()
            )
        }

        FileSpec.builder(packageName, "RouterPaths_$moduleName")
            .addType(typeBuilder.build())
            .build()
            .writeTo(codeGenerator, Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()))
    }

    private fun generateRouteInitFile(
        resolver: Resolver,
        packageName: String,
        className: String,
        moduleName: String,
        routeGroups: Map<String, List<KSClassDeclaration>>,
        serviceName: String,
        annotatedServices: List<KSClassDeclaration>,
        interceptorName: String,
        annotatedInterceptors: List<KSClassDeclaration>
    ) {
        val fileSpec = FileSpec.builder(packageName, className)
            .addType(
                TypeSpec.classBuilder(className)
                    .addSuperinterface(ClassName("com.wkq.router.api", "IRouteInit"))
                    .addFunction(
                        FunSpec.builder("init")
                            .addModifiers(KModifier.OVERRIDE)
                            .apply {
                                routeGroups.keys.forEach { groupName ->
                                    addStatement(
                                        "com.wkq.router.api.RouteTable.registerGroup(%S, %T::class.java)",
                                        groupName,
                                        ClassName(packageName, "RouteGroup_${moduleName}_$groupName")
                                    )
                                }

                                annotatedServices.forEach { clazz ->
                                    val annotation = clazz.annotations.find {
                                        it.annotationType.resolve().declaration.qualifiedName?.asString() == serviceName
                                    }
                                    val apiType = annotation?.arguments?.find { it.name?.asString() == "api" }?.value as? KSType
                                    val apiClassName = apiType?.declaration?.qualifiedName?.asString()

                                    if (apiClassName != null) {
                                        val implClassName = clazz.qualifiedName?.asString() ?: ""
                                        addStatement(
                                            "com.wkq.router.api.RouteTable.registerServiceProvider(%T::class.java) { %T() }",
                                            ClassName.bestGuess(apiClassName),
                                            ClassName.bestGuess(implClassName)
                                        )
                                    }
                                }

                                annotatedInterceptors.forEach { clazz ->
                                    val annotation = clazz.annotations.find {
                                        it.annotationType.resolve().declaration.qualifiedName?.asString() == interceptorName
                                    }
                                    val priority = annotation?.arguments?.find { it.name?.asString() == "priority" }?.value as? Int ?: 0
                                    val implClassName = clazz.qualifiedName?.asString() ?: ""
                                    addStatement(
                                        "com.wkq.router.api.RouteTable.registerInterceptor(%L, %T())",
                                        priority,
                                        ClassName.bestGuess(implClassName)
                                    )
                                }
                            }
                            .build()
                    )
                    .build()
            )
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()))
    }

    private fun validateRoutes(routes: List<KSClassDeclaration>, routeName: String) {
        val seenPaths = mutableMapOf<String, KSClassDeclaration>()
        routes.forEach { clazz ->
            val annotation = clazz.annotations.find {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == routeName
            }
            val path = annotation?.arguments?.find { it.name?.asString() == "path" }?.value as? String
            if (path.isNullOrBlank()) {
                logger.error("@Route path cannot be empty.", clazz)
                return@forEach
            }
            if (!path.matches(Regex("^/[A-Za-z0-9_]+/[A-Za-z0-9_./-]+$"))) {
                logger.error("AndroidCoreRouters: @Route path must match /group/page format, current path: $path", clazz)
            }
            if (clazz.qualifiedName == null) {
                logger.error("AndroidCoreRouters: @Route target must be a named class.", clazz)
            }
            val existed = seenPaths[path]
            if (existed != null) {
                logger.error(
                    "AndroidCoreRouters: duplicate route path found: $path, already used by ${existed.qualifiedName?.asString()}",
                    clazz
                )
            } else {
                seenPaths[path] = clazz
            }
        }
    }

    private fun validateAnnotationTargets(
        routeSymbols: List<KSAnnotated>,
        serviceSymbols: List<KSAnnotated>,
        interceptorSymbols: List<KSAnnotated>,
        paramSymbols: List<KSAnnotated>
    ) {
        routeSymbols.filterNot { it is KSClassDeclaration }.forEach {
            logger.error("AndroidCoreRouters: @Route can only be used on classes.", it)
        }
        serviceSymbols.filterNot { it is KSClassDeclaration }.forEach {
            logger.error("AndroidCoreRouters: @ProvideService can only be used on classes.", it)
        }
        interceptorSymbols.filterNot { it is KSClassDeclaration }.forEach {
            logger.error("AndroidCoreRouters: @Interceptor can only be used on classes.", it)
        }
        paramSymbols.filterNot { it is KSPropertyDeclaration }.forEach {
            logger.error("AndroidCoreRouters: @Param can only be used on properties.", it)
        }
    }

    private fun validateServices(
        resolver: Resolver,
        services: List<KSClassDeclaration>,
        serviceName: String
    ) {
        services.forEach { clazz ->
            val annotation = clazz.annotations.find {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == serviceName
            }
            val apiType = annotation?.arguments?.find { it.name?.asString() == "api" }?.value as? KSType
            if (apiType == null) {
                logger.error("AndroidCoreRouters: @ProvideService must declare api.", clazz)
                return@forEach
            }
            if (!apiType.isAssignableFrom(clazz.asType(emptyList()))) {
                logger.error(
                    "AndroidCoreRouters: @ProvideService target ${clazz.qualifiedName?.asString()} must implement ${apiType.declaration.qualifiedName?.asString()}.",
                    clazz
                )
            }
            if (!hasPublicNoArgConstructor(clazz)) {
                logger.error("AndroidCoreRouters: @ProvideService class must have a public no-arg constructor.", clazz)
            }
        }
    }

    private fun validateInterceptors(
        resolver: Resolver,
        interceptors: List<KSClassDeclaration>
    ) {
        val interceptorType = resolver
            .getClassDeclarationByName(resolver.getKSNameFromString("com.wkq.router.api.IInterceptor"))
            ?.asType(emptyList())
        interceptors.forEach { clazz ->
            if (interceptorType != null && !interceptorType.isAssignableFrom(clazz.asType(emptyList()))) {
                logger.error("AndroidCoreRouters: @Interceptor class must implement IInterceptor.", clazz)
            }
            if (!hasPublicNoArgConstructor(clazz)) {
                logger.error("AndroidCoreRouters: @Interceptor class must have a public no-arg constructor.", clazz)
            }
        }
    }

    private fun validateParams(params: List<KSPropertyDeclaration>) {
        params.forEach { param ->
            if (param.isMutable.not()) {
                logger.error("AndroidCoreRouters: @Param field must be var or @JvmField mutable property.", param)
            }
            val parent = param.parentDeclaration
            if (parent !is KSClassDeclaration) {
                logger.error("AndroidCoreRouters: @Param can only be used in classes.", param)
            }
        }
    }

    private fun hasPublicNoArgConstructor(clazz: KSClassDeclaration): Boolean {
        val constructors = clazz.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.simpleName.asString() == "<init>" }
            .toList()
        return constructors.isEmpty() || constructors.any {
            it.parameters.isEmpty() && Modifier.PRIVATE !in it.modifiers && Modifier.PROTECTED !in it.modifiers
        }
    }

    private fun toConstantName(path: String): String {
        val name = path.trim('/')
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .uppercase()
            .ifBlank { "ROOT" }
        return if (name.first().isLetter() || name.first() == '_') name else "PATH_$name"
    }

    private fun generateServiceFile(packageName: String, className: String) {
        val path = "META-INF/services/com.wkq.router.api.IRouteInit"
        try {
            codeGenerator.createNewFile(
                Dependencies(true),
                "",
                path,
                ""
            ).use { output ->
                OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                    writer.write("$packageName.$className")
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate service file: ${e.message}")
        }
    }
}
