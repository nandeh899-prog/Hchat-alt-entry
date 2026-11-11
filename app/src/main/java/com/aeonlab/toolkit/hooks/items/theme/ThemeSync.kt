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
    fun applyRoundAvatar(context: Context, round: Boolean) {
        runCatching {
            val sp = AeonLabStorage.preferences(context, RoundAvatarSettings.PREFS_NAME)
            val editor = sp.edit().putBoolean(RoundAvatarSettings.KEY_ENABLE, round)
            if (round) {
                editor.putFloat(RoundAvatarSettings.KEY_RADIUS_FACTOR, 0.5f)
            }
            editor.apply()
        }
    }
}
