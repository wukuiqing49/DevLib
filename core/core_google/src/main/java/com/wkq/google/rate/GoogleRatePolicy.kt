package com.wkq.google.rate

import java.util.concurrent.TimeUnit

/** 自动请求的业务节流策略；Play 自身配额仍独立生效，不保证实际展示。 */
data class GoogleRatePolicy(
    val minDays: Long = 1,
    val daysBetweenPrompts: Long = 30,
    val minCompletedActions: Int = 1,
) {
    init {
        require(minDays in 0..36500 && daysBetweenPrompts in 0..36500 && minCompletedActions >= 0)
    }

    /** 按首次使用、关键操作、关闭偏好和冷却时间判断，不读取星级或账号信息。 */
    fun evaluate(state: GoogleRateState, nowMillis: Long): GoogleRateDecision = when {
        state.suppressed -> GoogleRateDecision.SUPPRESSED
        nowMillis < 0 || state.firstUseMillis < 0 || nowMillis < state.firstUseMillis ||
            (state.lastRequestMillis != null && (state.lastRequestMillis < 0 || nowMillis < state.lastRequestMillis)) -> GoogleRateDecision.CLOCK_INVALID
        state.completedActions < minCompletedActions -> GoogleRateDecision.NO_COMPLETED_ACTION
        nowMillis - state.firstUseMillis < TimeUnit.DAYS.toMillis(minDays) -> GoogleRateDecision.TOO_EARLY
        state.lastRequestMillis != null && nowMillis - state.lastRequestMillis < TimeUnit.DAYS.toMillis(daysBetweenPrompts) -> GoogleRateDecision.COOLDOWN
        else -> GoogleRateDecision.ELIGIBLE
    }
}

/** 本地提示状态，不表示用户已评分；时间为毫秒，计数仅供宿主诊断。 */
data class GoogleRateState(
    val firstUseMillis: Long,
    val lastRequestMillis: Long? = null,
    val completedActions: Int = 0,
    val requestCount: Int = 0,
    val suppressed: Boolean = false,
)

/** 自动请求被允许或被业务节流拒绝的原因。 */
enum class GoogleRateDecision { ELIGIBLE, SUPPRESSED, CLOCK_INVALID, NO_COMPLETED_ACTION, TOO_EARLY, COOLDOWN }

/** 流程结束不代表弹窗展示或提交成功，取消通过协程取消原样传播。 */
enum class GoogleReviewStatus { FLOW_FINISHED, NOT_ELIGIBLE, UNAVAILABLE, IN_PROGRESS, HOST_INACTIVE }

/** 请求结果携带节流原因，不提供无法可靠获知的评分值或已评分布尔值。 */
data class GoogleReviewResult(val status: GoogleReviewStatus, val decision: GoogleRateDecision? = null)
