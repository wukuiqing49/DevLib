package com.wkq.google.billing.ui

import android.app.Activity
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration
import com.wkq.google.R

/** 本地验证真实 XML 膨胀、Base 反射与无配置页面，不代替设备视觉及 Play 验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleBillingActivityTest {
    /** 未配置时仍能显示页面，避免依赖 Firebase 或商品凭据才能打开。 */
    @Test fun activityInflatesWithoutBillingConfiguration() {
        Robolectric.buildActivity(GoogleBillingActivity::class.java).setup().use { controller ->
            assertNotNull(controller.get().findViewById<android.view.View>(R.id.primary))
            controller.get().recreate()
        }
    }

    /** 缺失结果不能误报免费或 Pro。 */
    @Test fun missingResultIsUnknown() {
        val result = GoogleBillingContract().parseResult(Activity.RESULT_CANCELED, Intent())
        assertFalse(result.status.isKnown)
        assertFalse(result.purchaseAttempted)
    }

    /** 底部弹层同样使用真实布局，并可在内部切换兑换说明而不假装已兑换。 */
    @Test fun bottomSheetInflatesAndShowsRedemptionExplanation() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val intent = GoogleBillingUi.createIntent(app, GoogleBillingRequest(presentation = GoogleBillingPresentation.BOTTOM_SHEET))
        Robolectric.buildActivity(GoogleBillingActivity::class.java, intent).setup().use { controller ->
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val activity = controller.get()
            val redeem = activity.findViewById<android.view.View>(R.id.redeem)
            assertNotNull(redeem)
            redeem.performClick()
            assertEquals(activity.getString(R.string.gpay_redeem_go), activity.findViewById<android.widget.TextView>(R.id.primary).text)
            assertFalse(GoogleBillingContract().parseResult(Activity.RESULT_CANCELED, null).status.isPro)
        }
    }
}
