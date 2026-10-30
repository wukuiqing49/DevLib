package com.wkq.google.billing.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import com.wkq.google.billing.GooglePaidStatus
import java.io.Serializable

/** 同一支付流程的展示形态，底部弹层由可恢复的透明 Activity 承载。 */
enum class GoogleBillingPresentation { ACTIVITY, BOTTOM_SHEET }

/** 支付页非商业配置；条款链接由宿主提供，沉浸式仅在全页模式显式启用。 */
data class GoogleBillingUiOptions(
    val termsUrl: String = "",
    val privacyUrl: String = "",
    val immersive: Boolean = false,
) : Serializable

/** 可经 Activity Result 和状态恢复传递的请求，不携带账号回调、凭据或订单令牌。 */
data class GoogleBillingRequest(
    val page: GoogleBillingPageConfig = GoogleBillingPageConfig(emptyList()),
    val presentation: GoogleBillingPresentation = GoogleBillingPresentation.ACTIVITY,
    val options: GoogleBillingUiOptions = GoogleBillingUiOptions(),
) : Serializable

/** 关闭时的付费快照及是否经历 Play 购买/兑换；未打开或进程被终止时快照可能未知。 */
data class GoogleBillingPageResult(
    val status: GooglePaidStatus = GooglePaidStatus(),
    val purchaseAttempted: Boolean = false,
    val redemptionOpened: Boolean = false,
) : Serializable

/** 统一全页和底部弹层结果契约；关闭页面不是购买成功，宿主须检查 status.isKnown。 */
class GoogleBillingContract : ActivityResultContract<GoogleBillingRequest, GoogleBillingPageResult>() {
    /** 创建显式库内页面 Intent，商品配置仍从宿主初始化的运行时读取。 */
    override fun createIntent(context: Context, input: GoogleBillingRequest): Intent =
        GoogleBillingUi.createIntent(context, input)

    /** 安全读取返回的无敏感付费快照；异常退出按未知处理，不据此撤销原授权。 */
    override fun parseResult(resultCode: Int, intent: Intent?): GoogleBillingPageResult =
        intent?.let { IntentCompat.getSerializableExtra(it, GoogleBillingUi.RESULT, GoogleBillingPageResult::class.java) }
            ?: GoogleBillingPageResult()
}

/** 单库支付页面入口，复用宿主原有 GoogleBillingRuntimeConfig 与 GoogleBillingPageConfig。 */
object GoogleBillingUi {
    internal const val REQUEST = "com.wkq.google.billing.ui.REQUEST"
    internal const val RESULT = "com.wkq.google.billing.ui.RESULT"

    /** 供已有导航和 Activity Result 调用方构建显式 Intent。 */
    fun createIntent(context: Context, request: GoogleBillingRequest = GoogleBillingRequest()): Intent =
        Intent(context, GoogleBillingActivity::class.java).putExtra(REQUEST, request)

    /** 打开完整页面；launcher 应由宿主在创建阶段注册，不能在点击后临时注册。 */
    fun openActivity(
        launcher: ActivityResultLauncher<GoogleBillingRequest>,
        page: GoogleBillingPageConfig = GoogleBillingPageConfig(emptyList()),
        options: GoogleBillingUiOptions = GoogleBillingUiOptions(),
    ) = launcher.launch(GoogleBillingRequest(page, GoogleBillingPresentation.ACTIVITY, options))

    /** 显示可旋转恢复的 XPopup 底部弹层，结果回调与完整页面使用相同契约。 */
    fun showBottomSheet(
        launcher: ActivityResultLauncher<GoogleBillingRequest>,
        page: GoogleBillingPageConfig = GoogleBillingPageConfig(emptyList()),
        options: GoogleBillingUiOptions = GoogleBillingUiOptions(),
    ) = launcher.launch(GoogleBillingRequest(page, GoogleBillingPresentation.BOTTOM_SHEET, options))
}
