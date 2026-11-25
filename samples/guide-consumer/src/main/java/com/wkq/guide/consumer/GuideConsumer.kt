package com.wkq.guide.consumer

import android.view.View
import android.view.Window
import androidx.lifecycle.LifecycleOwner
import com.wkq.guide.FeatureGuide
import com.wkq.guide.GuideOptions
import com.wkq.guide.GuideResult
import com.wkq.guide.GuideStep

/** 独立消费工程只引用发布 AAR，证明公开入口不要求 Base、XPopup 或工作台依赖。 */
object GuideConsumer {
    /** 业务项目提供窗口、生命周期、目标和结束处理，文案使用自身资源。 */
    fun show(window: Window, owner: LifecycleOwner, target: () -> View?, onFinished: (GuideResult) -> Unit): FeatureGuide =
        FeatureGuide.show(window, owner, listOf(GuideStep("create", target,
            window.context.getString(R.string.sample_title), window.context.getString(R.string.sample_message))),
            GuideOptions(onFinished = onFinished))
}
