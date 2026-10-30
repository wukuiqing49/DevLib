package com.wkq.google.rate

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** 评分节流边界测试，不依赖设备时钟或实际 Play 服务。 */
class GoogleRatePolicyTest {
    private val day = TimeUnit.DAYS.toMillis(1)
    private val policy = GoogleRatePolicy()

    /** 关键操作缺失时不能仅凭使用时长发起评分。 */
    @Test fun requiresCompletedAction() {
        assertEquals(GoogleRateDecision.NO_COMPLETED_ACTION, policy.evaluate(GoogleRateState(0), day))
    }
    /** 使用时长在一天边界之前拒绝，到达边界允许。 */
    @Test fun firstUseBoundaryIsExact() {
        val state = GoogleRateState(0, completedActions = 1)
        assertEquals(GoogleRateDecision.TOO_EARLY, policy.evaluate(state, day - 1))
        assertEquals(GoogleRateDecision.ELIGIBLE, policy.evaluate(state, day))
    }
    /** 请求节流在三十天边界恢复，非按自然日截断。 */
    @Test fun cooldownBoundaryIsExact() {
        val state = GoogleRateState(0, day, 1)
        assertEquals(GoogleRateDecision.COOLDOWN, policy.evaluate(state, 31 * day - 1))
        assertEquals(GoogleRateDecision.ELIGIBLE, policy.evaluate(state, 31 * day))
    }
    /** 用户关闭偏好优先于其他允许条件。 */
    @Test fun suppressionWins() {
        assertEquals(GoogleRateDecision.SUPPRESSED, policy.evaluate(GoogleRateState(0, completedActions = 100, suppressed = true), 100 * day))
    }
    /** 设备时间倒退或负时间不会产生请求资格。 */
    @Test fun rejectsClockRollbackAndNegativeTime() {
        assertEquals(GoogleRateDecision.CLOCK_INVALID, policy.evaluate(GoogleRateState(day, completedActions = 1), day - 1))
        assertEquals(GoogleRateDecision.CLOCK_INVALID, policy.evaluate(GoogleRateState(0, 2 * day, 1), day))
        assertEquals(GoogleRateDecision.CLOCK_INVALID, policy.evaluate(GoogleRateState(0), -1))
    }
    /** 起点为零的合法请求也必须遵守冷却，不能当作从未请求。 */
    @Test fun epochRequestIsNotMissingRequest() {
        assertEquals(GoogleRateDecision.COOLDOWN, GoogleRatePolicy(minDays = 0).evaluate(GoogleRateState(0, 0, 1), 1))
    }
    /** 每个宿主可设置自己的关键操作门槛与使用时长。 */
    @Test fun customThresholdRemainsIndependentOfBusinessTypes() {
        val custom = GoogleRatePolicy(minDays = 2, minCompletedActions = 3)
        assertEquals(GoogleRateDecision.NO_COMPLETED_ACTION, custom.evaluate(GoogleRateState(0, completedActions = 2), 3 * day))
        assertEquals(GoogleRateDecision.ELIGIBLE, custom.evaluate(GoogleRateState(0, completedActions = 3), 2 * day))
    }
    /** 显式零门槛可用于受控宿主策略，但不是绕过 Play 配额。 */
    @Test fun explicitZeroThresholdIsSupported() {
        assertEquals(GoogleRateDecision.ELIGIBLE, GoogleRatePolicy(0, 0, 0).evaluate(GoogleRateState(0), 0))
    }
    /** 无效参数在构造时被拒绝，避免时间单位溢出。 */
    @Test fun invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { GoogleRatePolicy(minDays = -1) }
        assertThrows(IllegalArgumentException::class.java) { GoogleRatePolicy(daysBetweenPrompts = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { GoogleRatePolicy(minCompletedActions = -1) }
    }
}
