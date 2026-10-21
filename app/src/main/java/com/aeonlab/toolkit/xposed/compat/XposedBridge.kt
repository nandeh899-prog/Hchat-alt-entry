package com.aeonlab.toolkit.xposed.compat

import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 兼容层：等价于 legacy 的 de.robv.android.xposed.XposedBridge。
 *
 * 只保留项目实际使用到的子集：hookMethod / hookAllMethods / hookAllConstructors / log。
 * 内部统一委托给 [CompatRuntime]，由它决定走 libxposed API 102 还是 legacy 框架。
 */
object XposedBridge {

    @JvmStatic
    fun log(text: String) {
        CompatRuntime.log(text)
    }

    @JvmStatic
    fun log(t: Throwable) {
        CompatRuntime.log(t)
    }

    @JvmStatic
    fun hookMethod(member: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        require(member is Executable) { "只能 hook 方法或构造函数: $member" }
        return CompatRuntime.hook(member, callback)
    }

    /** hook 指定类（含父类）中所有同名方法，语义对齐 legacy。 */
    @JvmStatic
    fun hookAllMethods(
        hookClass: Class<*>,
        methodName: String,
        callback: XC_MethodHook
    ): Set<XC_MethodHook.Unhook> {
        val result = LinkedHashSet<XC_MethodHook.Unhook>()
        val seen = HashSet<String>()
        var current: Class<*>? = hookClass
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (method.name != methodName) continue
                if (method.isSynthetic || method.isBridge) continue
                // 抽象方法没有方法体，hook 无意义；具体实现会在子类里被单独遍历到
                if (Modifier.isAbstract(method.modifiers)) continue
                if (!seen.add(signature(method))) continue
                runCatching { result.add(CompatRuntime.hook(method, callback)) }
            }
            current = current.superclass
        }
        return result
    }

    /** hook 指定类的全部构造函数。 */
    @JvmStatic
    fun hookAllConstructors(
        hookClass: Class<*>,
        callback: XC_MethodHook
    ): Set<XC_MethodHook.Unhook> {
        val result = LinkedHashSet<XC_MethodHook.Unhook>()
        for (ctor in hookClass.declaredConstructors) {
            if (ctor.isSynthetic) continue
            runCatching { result.add(CompatRuntime.hook(ctor, callback)) }
        }
        return result
    }

    private fun signature(method: Method): String =
        method.name + method.parameterTypes.joinToString(",", "(", ")") { it.name }
}
