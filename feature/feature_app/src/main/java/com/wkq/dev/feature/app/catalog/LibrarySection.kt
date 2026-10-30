package com.wkq.dev.feature.app.catalog

import androidx.annotation.StringRes
import com.wkq.dev.feature.app.R

/** 公共库的三类入口；导航键兼容旧状态，目录名对应 core 下的实际源码分组。 */
enum class LibrarySection(
    val key: String,
    val directory: String,
    @StringRes val title: Int,
    @StringRes val intro: Int,
) {
    ARCHITECTURE("foundation", "architecture", R.string.directory_architecture_title, R.string.foundation_intro),
    FUNCTIONAL("libraries", "functional", R.string.directory_functional_title, R.string.directory_intro),
    THIRDPARTY("thirdparty", "thirdparty", R.string.directory_thirdparty_title, R.string.thirdparty_intro);

    /** 仅解析已知目录键，未知值由调用方按导航或历史参数场景决定回退。 */
    companion object {
        /** 从 Fragment 参数或保存状态恢复合法分类，拒绝过期及内部支撑分类。 */
        fun fromKey(key: String?): LibrarySection? = entries.find { it.key == key }
    }
}
