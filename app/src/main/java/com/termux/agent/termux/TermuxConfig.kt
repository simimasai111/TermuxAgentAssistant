package com.termux.agent.termux

import android.content.Context
import android.content.SharedPreferences

data class TermuxConfig(
    val bootstrapMirrorUrl: String = defaultMirrorUrl,
) {
    companion object {
        const val DEFAULT_GITHUB_URL = "https://github.com/termux/termux-packages/releases/download/bootstrap-archives/bootstrap-\$arch.zip"
        const val DEFAULT_MIRROR_GHPROXY = "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download/bootstrap-archives/bootstrap-\$arch.zip"

        val defaultMirrorUrl get() = DEFAULT_GITHUB_URL

        private const val PREFS_NAME = "termux_config"
        private const val KEY_BOOTSTRAP_MIRROR = "bootstrap_mirror"

        fun load(context: Context): TermuxConfig {
            val prefs = prefs(context)
            return TermuxConfig(
                bootstrapMirrorUrl = prefs.getString(KEY_BOOTSTRAP_MIRROR, defaultMirrorUrl) ?: defaultMirrorUrl,
            )
        }

        fun save(context: Context, config: TermuxConfig) {
            prefs(context).edit().apply {
                putString(KEY_BOOTSTRAP_MIRROR, config.bootstrapMirrorUrl)
                apply()
            }
        }

        private fun prefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
