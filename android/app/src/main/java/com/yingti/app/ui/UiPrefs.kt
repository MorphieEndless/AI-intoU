package com.yingti.app.ui

import android.content.Context

/**
 * UI 偏好（普通 SharedPreferences，非敏感数据）：
 * - 深色/亮色模式（默认跟随系统首启，之后手动切换持久化）
 * - UI 版式（palette key，默认酒红 wine）
 * - 开发者模式（默认开启：主页面显示协议调试 HEX 入口；关闭后完全隐藏）
 */
object UiPrefs {
    private const val PREFS = "yingti_ui"
    private const val KEY_DARK = "dark_theme"
    private const val KEY_PALETTE = "palette"
    private const val KEY_DEV_MODE = "dev_mode"
    private const val KEY_AI_ACCESS_DONE = "onboarding_ai_access_done"
    private const val KEY_ONBOARDING_DISMISSED = "onboarding_dismissed"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun darkTheme(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK, false)

    fun setDarkTheme(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK, value).apply()
    }

    fun paletteKey(context: Context): String =
        prefs(context).getString(KEY_PALETTE, "wine") ?: "wine"

    fun setPaletteKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_PALETTE, key).apply()
    }

    /** 默认开启：这是面向 GitHub 的开发者工具，协议调试默认可见。 */
    fun devMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEV_MODE, true)

    fun setDevMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEV_MODE, value).apply()
    }

    /** 首次使用引导第 3 步：已经有过至少一个 AI 接入（本机创建或列表里查到）。 */
    fun aiAccessDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AI_ACCESS_DONE, false)

    fun setAiAccessDone(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AI_ACCESS_DONE, value).apply()
    }

    /** 用户手动收起了首次使用引导卡片。 */
    fun onboardingDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DISMISSED, false)

    fun setOnboardingDismissed(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DISMISSED, value).apply()
    }
}
