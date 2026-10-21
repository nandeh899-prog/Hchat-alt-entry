package com.aeonlab.toolkit.xposed.compat

import android.content.pm.ApplicationInfo

/**
 * 兼容层：等价于 legacy 的 de.robv.android.xposed.callbacks.XC_LoadPackage。
 */
class XC_LoadPackage private constructor() {

    class LoadPackageParam @JvmOverloads constructor(
        @JvmField var packageName: String? = null,
        @JvmField var processName: String? = null,
        @JvmField var classLoader: ClassLoader? = null,
        @JvmField var appInfo: ApplicationInfo? = null,
        @JvmField var isFirstApplication: Boolean = false
    )
}