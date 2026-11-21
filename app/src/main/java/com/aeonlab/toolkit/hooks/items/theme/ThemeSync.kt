package com.aeonlab.toolkit.hooks.items.theme

import android.content.Context
import com.aeonlab.toolkit.hooks.items.roundavatar.RoundAvatarSettings
import com.aeonlab.toolkit.preferences.AeonLabStorage

/**
 * 主题项与已有模块项的联动。
 *
 * 「圆形头像」不重复写一套头像 Hook（重复 Hook 同一入口会互相覆盖），
 * 而是直接写「圆角头像」模块自己的设置，由它那套已经真机验证过的实现去生效。
 */
object ThemeSync {
    private const val GLOBAL_PREFS = "AeonLab_global_config"
    private const val ROUND_AVATAR_FEATURE_KEY = "round_avatar_enabled"

    fun applyRoundAvatar(context: Context, round: Boolean) {
        runCatching {
            val sp = AeonLabStorage.preferences(context, RoundAvatarSettings.PREFS_NAME)
            val editor = sp.edit().putBoolean(RoundAvatarSettings.KEY_ENABLE, round)
            if (round) {
                editor.putFloat(RoundAvatarSettings.KEY_RADIUS_FACTOR, 0.5f)
            }
            editor.apply()
        }
        // 圆形头像依赖「圆角头像」功能自己的 Hook：功能级总开关关着时它的钩子根本没装，
        // 只写子设置不会生效。这里在开启圆形头像时把功能级开关一并打开（下次启动生效）。
        if (round) {
            runCatching {
                val global = AeonLabStorage.preferences(context, GLOBAL_PREFS)
                global.edit().putBoolean(ROUND_AVATAR_FEATURE_KEY, true).apply()
            }
        }
    }
}
