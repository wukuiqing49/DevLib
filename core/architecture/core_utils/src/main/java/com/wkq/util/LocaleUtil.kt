package com.wkq.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.text.TextUtilsCompat
import java.util.Locale

/** 应用语言、地区与 RTL 布局相关工具。 */
object LocaleUtil {

    /** 获取当前 Context 实际使用的首选语言环境。 */
    fun getCurrentLocale(context: Context): Locale {
        val configuration = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            configuration.locale ?: Locale.getDefault()
        }
    }

    /** 获取当前 Context 实际使用的 BCP 47 语言标签，例如 `zh-CN`。 */
    fun getCurrentLanguageTag(context: Context): String = getCurrentLocale(context).toLanguageTag()

    /** 判断指定语言环境是否使用从右到左布局。 */
    fun isRtl(locale: Locale): Boolean {
        return TextUtilsCompat.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL
    }

    /** 判断当前 Context 是否使用从右到左布局。 */
    fun isCurrentRtl(context: Context): Boolean = isRtl(getCurrentLocale(context))

    /**
     * 创建指定语言环境的 Context，不会修改全局应用语言。
     */
    fun createLocalizedContext(context: Context, locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }

    /**
     * 设置 AppCompat 管理的应用语言。调用后受影响的 Activity 通常会重建。
     * 传入空字符串会清除应用覆盖语言并跟随系统。
     */
    fun setApplicationLanguageTag(languageTag: String?) {
        val locales = if (languageTag.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** 获取 AppCompat 设置的应用语言标签；未覆盖时返回空字符串。 */
    fun getApplicationLanguageTags(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    /** 清除应用语言覆盖，恢复跟随系统语言。 */
    fun clearApplicationLanguage() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }
}
