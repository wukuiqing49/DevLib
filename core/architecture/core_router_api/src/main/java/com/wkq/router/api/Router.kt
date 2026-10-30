package com.wkq.router.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResult
import androidx.fragment.app.FragmentActivity
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/**
 * 路由入口。
 */
object Router {

    private val initialized = AtomicBoolean(false)
    private val syringeCache = ConcurrentHashMap<String, ISyringe>()
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var lastInitResult: RouterInitResult? = null

    fun setDebug(debug: Boolean) {
        RouterConfig.debug = debug
    }

    fun setThrowExceptionWhenRouteNotFound(enable: Boolean) {
        RouterConfig.throwExceptionWhenRouteNotFound = enable
    }

    fun setAutoInitWhenNavigate(enable: Boolean) {
        RouterConfig.autoInitWhenNavigate = enable
    }

    fun setInterceptorTimeout(timeoutMs: Long) {
        RouterConfig.interceptorTimeoutMs = timeoutMs.coerceAtLeast(0L)
    }

    fun setDegradationService(service: IDegradationService?) {
        RouterConfig.globalDegradationService = service
    }

    fun setLogger(logger: RouterConfig.Logger) {
        RouterConfig.logger = logger
    }

    fun isInitialized(): Boolean {
        return initialized.get()
    }

    fun getLastInitResult(): RouterInitResult? {
        return lastInitResult
    }

    /**
     * 初始化路由，扫描所有模块生成的注册类。
     */
    fun init(context: Context): RouterInitResult {
        if (!initialized.compareAndSet(false, true)) {
            RouterConfig.logger.d("Router already initialized, skip.")
            return lastInitResult ?: RouterInitResult(0, 0, true)
        }

        appContext = context.applicationContext
        var successCount = 0
        var failureCount = 0

        try {
            val iterator = ServiceLoader.load(IRouteInit::class.java).iterator()
            while (true) {
                val init = try {
                    if (!iterator.hasNext()) break
                    iterator.next()
                } catch (t: Throwable) {
                    failureCount++
                    RouterConfig.logger.e("Load router initializer failed.", t)
                    continue
                }

                try {
                    init.init()
                    successCount++
                } catch (t: Throwable) {
                    failureCount++
                    RouterConfig.logger.e("Run router initializer failed: ${init.javaClass.name}", t)
                }
            }
        } catch (t: Throwable) {
            initialized.set(false)
            lastInitResult = RouterInitResult(successCount, failureCount + 1, false)
            RouterConfig.logger.e("Router init failed.", t)
            if (RouterConfig.throwExceptionWhenRouteNotFound) {
                throw t
            }
            return lastInitResult!!
        }

        lastInitResult = RouterInitResult(successCount, failureCount, true)
        RouterConfig.logger.d("Router init finished, success=$successCount, failure=$failureCount.")
        if (failureCount > 0 && RouterConfig.throwExceptionWhenRouteNotFound) {
            throw RouterException("Router init has failed modules, failure=$failureCount")
        }
        return lastInitResult!!
    }

    fun build(path: String): Postcard {
        return Postcard(path)
    }

    fun build(uri: Uri): Postcard {
        return Postcard.fromUri(uri)
    }

    fun buildUri(uri: String): Postcard {
        return build(Uri.parse(uri))
    }

    fun navigate(context: Context, path: String) {
        navigate(context, build(path))
    }

    fun navigate(context: Context, uri: Uri) {
        navigate(context, build(uri))
    }

    fun navigateUri(context: Context, uri: String) {
        navigate(context, buildUri(uri))
    }

    fun navigate(context: Context, postcard: Postcard) {
        ensureInitialized(context)
        val interceptors = RouteTable.interceptors
            .sortedByDescending { it.priority }
            .mapNotNull { meta ->
                meta.interceptor as? IInterceptor ?: run {
                    RouterConfig.logger.e("Invalid router interceptor: ${meta.interceptor.javaClass.name}")
                    null
                }
            }

        executeInterceptors(interceptors, 0, postcard, { p, t ->
            handleLost(context, p, t)
        }) { p ->
            realNavigate(context, p)
        }
    }

    fun preload(path: String): Boolean {
        ensureInitialized(appContext)
        checkAndLoadGroup(path)
        return RouteTable.hasRoute(path)
    }

    fun preloadGroup(group: String): Boolean {
        ensureInitialized(appContext)
        val groupClass = RouteTable.groups[group] ?: return false
        return try {
            val groupInstance = groupClass.getConstructor().newInstance()
            groupInstance.load()
            RouteTable.groups.remove(group)
            true
        } catch (t: Throwable) {
            RouterConfig.logger.e("Preload route group failed: $group", t)
            false
        }
    }

    fun preloadAllGroups(): Int {
        ensureInitialized(appContext)
        val groups = RouteTable.groupNames()
        var count = 0
        groups.forEach { group ->
            if (preloadGroup(group)) {
                count++
            }
        }
        return count
    }

    fun contains(path: String): Boolean {
        ensureInitialized(appContext)
        checkAndLoadGroup(path)
        return RouteTable.hasRoute(path)
    }

    fun findRoute(path: String): RouteMeta? {
        ensureInitialized(appContext)
        checkAndLoadGroup(path)
        return RouteTable.getRoute(path)
    }

    fun loadedRoutePaths(): List<String> {
        ensureInitialized(appContext)
        return RouteTable.routePaths()
    }

    fun pendingGroupNames(): List<String> {
        ensureInitialized(appContext)
        return RouteTable.groupNames()
    }

    fun navigateWithResult(
        activity: FragmentActivity,
        postcard: Postcard,
        callback: (ActivityResult) -> Unit
    ) {
        ensureInitialized(activity)
        val interceptors = RouteTable.interceptors
            .sortedByDescending { it.priority }
            .mapNotNull { meta ->
                meta.interceptor as? IInterceptor ?: run {
                    RouterConfig.logger.e("Invalid router interceptor: ${meta.interceptor.javaClass.name}")
                    null
                }
            }

        executeInterceptors(interceptors, 0, postcard, { p, t ->
            handleLost(activity, p, t)
        }) { p ->
            realNavigateWithResult(activity, p, callback)
        }
    }

    private fun extractGroup(path: String): String {
        val cleanPath = if (path.startsWith("/")) path.substring(1) else path
        val group = cleanPath.substringBefore("/")
        return if (group.isEmpty()) "default" else group
    }

    private fun checkAndLoadGroup(path: String) {
        if (RouteTable.routes[path] != null) return

        val group = extractGroup(path)
        val groupClass = RouteTable.groups[group] ?: return
        try {
            val groupInstance = groupClass.getConstructor().newInstance()
            groupInstance.load()
            RouteTable.groups.remove(group)
        } catch (t: Throwable) {
            RouterConfig.logger.e("Load route group failed: $group", t)
        }
    }

    private fun executeInterceptors(
        interceptors: List<IInterceptor>,
        index: Int,
        postcard: Postcard,
        onError: (Postcard, Throwable) -> Unit,
        finish: (Postcard) -> Unit
    ) {
        if (index >= interceptors.size) {
            finish(postcard)
            return
        }

        val interceptor = interceptors[index]
        val completed = AtomicBoolean(false)
        try {
            val timeoutMs = RouterConfig.interceptorTimeoutMs
            val timeoutId = interceptorTimeoutId.incrementAndGet()
            if (timeoutMs > 0L) {
                mainHandler.postDelayed({
                    if (completed.compareAndSet(false, true)) {
                        onError(
                            postcard,
                            RouteInterruptedException(
                                postcard.path,
                                RouterException("Router interceptor timeout after ${timeoutMs}ms, id=$timeoutId")
                            )
                        )
                    }
                }, timeoutMs)
            }
            interceptor.process(postcard, object : InterceptorCallback {
                override fun onContinue(postcard: Postcard) {
                    if (completed.compareAndSet(false, true)) {
                        executeInterceptors(interceptors, index + 1, postcard, onError, finish)
                    }
                }

                override fun onInterrupt(exception: Throwable?) {
                    if (!completed.compareAndSet(false, true)) return
                    val routeException = RouteInterruptedException(postcard.path, exception)
                    exception?.let {
                        RouterConfig.logger.e("Router interrupted: ${postcard.path}", it)
                    }
                    onError(postcard, routeException)
                }
            })
        } catch (t: Throwable) {
            RouterConfig.logger.e("Router interceptor failed: ${postcard.path}", t)
            if (completed.compareAndSet(false, true)) {
                onError(postcard, t)
            }
        }
    }

    private fun realNavigate(context: Context, postcard: Postcard) {
        checkAndLoadGroup(postcard.path)
        val meta = RouteTable.routes[postcard.path]
        if (meta == null) {
            handleLost(context, postcard, null)
            return
        }

        val intent = Intent(context, meta.clazz).apply {
            putExtras(postcard.getExtras())
            if (postcard.getFlags() != -1) {
                flags = postcard.getFlags()
            }
            if (context !is Activity && flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            RouterConfig.logger.e("Start route failed: ${postcard.path}", t)
            handleLost(context, postcard, t)
            return
        }

        handleAnimation(context, postcard, meta)
    }

    private fun realNavigateWithResult(
        activity: FragmentActivity,
        postcard: Postcard,
        callback: (ActivityResult) -> Unit
    ) {
        checkAndLoadGroup(postcard.path)
        val meta = RouteTable.routes[postcard.path]
        if (meta == null) {
            handleLost(activity, postcard, null)
            return
        }

        val intent = Intent(activity, meta.clazz).apply {
            putExtras(postcard.getExtras())
        }

        val proxy = RouterResultProxyFragment()
        proxy.setParams(intent, callback)

        activity.supportFragmentManager.beginTransaction()
            .add(proxy, "RouterResultProxy")
            .commitAllowingStateLoss()
    }

    private fun handleAnimation(context: Context, postcard: Postcard, meta: RouteMeta) {
        if (context !is Activity) return

        val enterId = if (postcard.getEnterAnim() != 0) postcard.getEnterAnim() else meta.enterAnim
        val exitId = if (postcard.getExitAnim() != 0) postcard.getExitAnim() else meta.exitAnim
        if (enterId == 0 && exitId == 0) return

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            context.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enterId, exitId)
        } else {
            @Suppress("DEPRECATION")
            context.overridePendingTransition(enterId, exitId)
        }
    }

    fun getFragment(path: String, bundle: Bundle? = null): androidx.fragment.app.Fragment? {
        ensureInitialized(appContext)
        checkAndLoadGroup(path)
        val meta = RouteTable.routes[path] ?: return null
        return try {
            val fragment = meta.clazz.getConstructor().newInstance() as? androidx.fragment.app.Fragment
            fragment?.arguments = bundle
            fragment
        } catch (t: Throwable) {
            RouterConfig.logger.e("Create fragment failed: $path", t)
            null
        }
    }

    fun getView(path: String, context: Context): android.view.View? {
        ensureInitialized(context)
        checkAndLoadGroup(path)
        val meta = RouteTable.routes[path] ?: return null
        return try {
            meta.clazz.getConstructor(Context::class.java).newInstance(context) as? android.view.View
        } catch (t: Throwable) {
            RouterConfig.logger.e("Create view failed: $path", t)
            null
        }
    }

    /**
     * 自动注入 @Param 参数。
     */
    fun inject(target: Any) {
        val className = target.javaClass.name + "_Syringe"
        var syringe = syringeCache[className]
        if (syringe == null) {
            syringe = try {
                val syringeClass = Class.forName(className)
                (syringeClass.getConstructor().newInstance() as ISyringe).also {
                    syringeCache[className] = it
                }
            } catch (_: ClassNotFoundException) {
                return
            } catch (t: Throwable) {
                RouterConfig.logger.e("Create syringe failed: $className", t)
                return
            }
        }

        try {
            syringe.inject(target)
        } catch (t: Throwable) {
            RouterConfig.logger.e("Inject route params failed: ${target.javaClass.name}", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getService(api: KClass<T>): T? {
        ensureInitialized(appContext)
        return RouteTable.getService(api.java) as? T
    }

    fun <T : Any> getService(api: Class<T>): T? {
        ensureInitialized(appContext)
        return api.cast(RouteTable.getService(api))
    }

    fun <T : Any> preloadService(api: KClass<T>): Boolean {
        ensureInitialized(appContext)
        return RouteTable.preloadService(api.java)
    }

    fun preloadService(api: Class<*>): Boolean {
        ensureInitialized(appContext)
        return RouteTable.preloadService(api)
    }

    fun preloadAllServices(): Int {
        ensureInitialized(appContext)
        return RouteTable.preloadAllServices()
    }

    fun serviceApis(): List<Class<*>> {
        ensureInitialized(appContext)
        return RouteTable.serviceApis()
    }

    /**
     * 兼容旧调用方式。
     */
    fun open(path: String, context: Context, block: (Intent.() -> Unit)? = null) {
        ensureInitialized(context)
        val postcard = build(path)
        checkAndLoadGroup(path)
        val meta = RouteTable.routes[path]
        if (meta == null) {
            handleLost(context, postcard, null)
            return
        }

        val intent = Intent(context, meta.clazz)
        block?.invoke(intent)
        postcard.withBundle(intent.extras ?: Bundle())
        postcard.withFlags(intent.flags)
        navigate(context, postcard)
    }

    private fun handleLost(context: Context?, postcard: Postcard, cause: Throwable?) {
        RouterConfig.logger.e("Route not found or unavailable: ${postcard.path}", cause)
        if (context != null) {
            val degradationService = RouterConfig.globalDegradationService
                ?: getService(IDegradationService::class)
            if (degradationService != null) {
                try {
                    degradationService.onLost(context, postcard)
                    return
                } catch (t: Throwable) {
                    RouterConfig.logger.e("Route degradation failed: ${postcard.path}", t)
                }
            }
        }

        if (RouterConfig.throwExceptionWhenRouteNotFound) {
            throw RouteNotFoundException(postcard.path, cause)
        }
    }

    private fun ensureInitialized(context: Context?) {
        if (initialized.get()) return
        if (!RouterConfig.autoInitWhenNavigate) {
            RouterConfig.logger.e("Router is not initialized. Call Router.init(context) in Application.onCreate().")
            return
        }
        if (context != null) {
            init(context)
        } else {
            RouterConfig.logger.e("Router is not initialized and no Context is available for auto init.")
        }
    }

    fun resetForTest() {
        initialized.set(false)
        appContext = null
        lastInitResult = null
        syringeCache.clear()
        RouteTable.clear()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val interceptorTimeoutId = AtomicInteger(0)
}
