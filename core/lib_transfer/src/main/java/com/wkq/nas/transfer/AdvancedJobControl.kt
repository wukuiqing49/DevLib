package com.wkq.nas.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 慢读取按块检查父任务；提交前必须使用 force，不能只检查协程是否活跃。 */
internal class AdvancedJobControl(private val verify: () -> Unit,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AdvancedJobControl>
    private var checkedAt = Long.MIN_VALUE
    @Synchronized fun check(force: Boolean = false) {
        val now = clock()
        if (force || checkedAt == Long.MIN_VALUE || now - checkedAt >= 250) { verify(); checkedAt = now }
    }
}
internal suspend fun checkAdvancedJob(force: Boolean = false) { currentCoroutineContext()[AdvancedJobControl]?.check(force) }
