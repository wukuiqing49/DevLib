package com.wkq.router.api

open class RouterException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class RouteNotFoundException(
    val routePath: String,
    cause: Throwable? = null
) : RouterException("Route not found: $routePath", cause)

class RouteInterruptedException(
    val routePath: String,
    cause: Throwable? = null
) : RouterException("Route interrupted: $routePath", cause)
