package com.wkq.util.jump

import com.wkq.util.jump.impl.*

/**
 * 智能跳转引擎
 * 管理跳转策略并执行分发
 */
object SmartJumpEngine {

    @Volatile
    private var handlers: List<JumpHandler> = listOf(
        EmbeddedSchemeHandler(),
        TaobaoJumpHandler(),
        JDJumpHandler(),
        PDDJumpHandler(),
        DouyinJumpHandler(),
        IdlefishJumpHandler()
    ).sortedByDescending { it.getPriority() }

    /**
     * 根据 URL 获取对应的跳转 Scheme
     */
    fun getScheme(url: String): String? {
        for (handler in handlers) {
            if (handler.canHandle(url)) {
                val scheme = handler.convertToScheme(url)
                if (scheme != null) return scheme
            }
        }
        return null
    }

    /**
     * 动态注册新的处理器
     */
    @Synchronized
    fun registerHandler(handler: JumpHandler) {
        handlers = (handlers + handler).sortedByDescending { it.getPriority() }
    }

    /** 移除指定类型的处理器。 */
    @Synchronized
    fun unregisterHandler(clazz: Class<out JumpHandler>) {
        handlers = handlers.filterNot { it.javaClass == clazz }
    }

    /** 返回当前已注册处理器快照，便于调试或单元测试。 */
    fun getHandlers(): List<JumpHandler> = handlers.toList()
}
