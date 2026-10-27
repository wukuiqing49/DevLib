package com.wkq.dev

import androidx.core.view.doOnLayout
import com.wkq.base.activity.BaseTitleActivity
import com.wkq.dev.databinding.ActivityMainBinding

/** 开发者工作台宿主，仅复用 Base 标题、系统栏和顶层导航容器，不承载工具业务。 */
class MainActivity : BaseTitleActivity<ActivityMainBinding>() {
    /** 设置工作台标题与字号，首页隐藏返回入口，具体列表由 CatalogFragment 展示。 */
    override fun initView() {
        setPageTitle(getString(com.wkq.dev.feature.app.R.string.catalog_title))
        // 基础 setPageTitleSize 的历史实现实际使用 SP；宿主直接按资源解析出的 PX 设置，避免重复缩放。
        val titleView = binding.titleBar.findViewById<android.widget.TextView>(com.wkq.base.R.id.tv_center_title)
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.app_toolbar_title_size))
        binding.titleBar.doOnLayout { bar ->
            titleView.maxWidth = (bar.width - resources.getDimensionPixelSize(R.dimen.app_spacing_large) * 4).coerceAtLeast(0)
        }
        setLeftVisible(false)
    }

    /** 搜索框使用 Base 的底部系统栏与 IME 合并避让，不在 Fragment 再消费 Insets。 */
    override fun shouldApplyImeInset(): Boolean = true

    /** 工作区数据由现有 Fragment 与 ViewModel 订阅，宿主不重复加载。 */
    override fun initData() = Unit
}
