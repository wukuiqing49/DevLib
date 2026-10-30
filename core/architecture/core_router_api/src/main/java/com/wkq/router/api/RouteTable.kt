package com.wkq.router.api

/**
 *
 * @ Author: wkq
 *
 * @ Time: 2026/3/24 9:21
 *
 * @ Desc:

 */
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object RouteTable {

    val routes = ConcurrentHashMap<String, RouteMeta>()
    val groups = ConcurrentHashMap<String, Class<out IRouteGroup>>()
    val services = ConcurrentHashMap<Class<*>, Any>()
    private val serviceProviders = ConcurrentHashMap<Class<*>, () -> Any>()
    val interceptors = CopyOnWriteArrayList<com.wkq.router.annotation.InterceptorMeta>()

    fun registerGroup(groupName: String, clazz: Class<out IRouteGroup>) {
        groups[groupName] = clazz
    }

    fun register(path: String, clazz: Class<*>, enterAnim: Int = 0, exitAnim: Int = 0) {
        routes[path] = RouteMeta(clazz, enterAnim, exitAnim)
    }

    fun registerService(api: Class<*>, impl: Any) {
        services[api] = impl
    }

    fun registerServiceProvider(api: Class<*>, provider: () -> Any) {
        serviceProviders[api] = provider
    }

    fun getService(api: Class<*>): Any? {
        services[api]?.let { return it }
        val provider = serviceProviders[api] ?: return null
        return services.getOrPut(api) { provider() }
    }

    fun preloadService(api: Class<*>): Boolean {
        return getService(api) != null
    }

    fun preloadAllServices(): Int {
        var count = 0
        serviceProviders.keys.forEach { api ->
            if (getService(api) != null) {
                count++
            }
        }
        return count
    }

    fun hasRoute(path: String): Boolean {
        return routes.containsKey(path)
    }

    fun getRoute(path: String): RouteMeta? {
        return routes[path]
    }

    fun routePaths(): List<String> {
        return routes.keys.sorted()
    }

    fun groupNames(): List<String> {
        return groups.keys.sorted()
    }

    fun serviceApis(): List<Class<*>> {
        return (services.keys + serviceProviders.keys).distinctBy { it.name }.sortedBy { it.name }
    }

    fun registerInterceptor(priority: Int, interceptor: IInterceptor) {
        interceptors.add(com.wkq.router.annotation.InterceptorMeta(priority, interceptor))
    }

    fun clear() {
        routes.clear()
        groups.clear()
        services.clear()
        serviceProviders.clear()
        interceptors.clear()
    }
}

data class RouteMeta(
    val clazz: Class<*>,
    val enterAnim: Int,
    val exitAnim: Int
)
