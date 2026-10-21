package com.aeonlab.toolkit.xposed.compat

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 兼容层：等价于 legacy 的 de.robv.android.xposed.XposedHelpers。
 *
 * 只保留项目实际使用到的子集：
 * findAndHookMethod / callMethod / getObjectField / findClass。
 */
object XposedHelpers {

    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        val loader = classLoader ?: XposedHelpers::class.java.classLoader
        return Class.forName(className, false, loader)
    }

    @JvmStatic
    fun findAndHookMethod(
        hookClass: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook =
        hookWithArgs(hookClass, methodName, parameterTypesAndCallback)

    /** legacy 重载：(className, classLoader, methodName, ...) */
    @JvmStatic
    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook =
        hookWithArgs(findClass(className, classLoader), methodName, parameterTypesAndCallback)

    private fun hookWithArgs(
        hookClass: Class<*>,
        methodName: String,
        parameterTypesAndCallback: Array<out Any?>
    ): XC_MethodHook.Unhook {
        if (parameterTypesAndCallback.isEmpty() ||
            parameterTypesAndCallback.last() !is XC_MethodHook
        ) {
            throw IllegalArgumentException("findAndHookMethod 末位参数必须是 XC_MethodHook")
        }
        val callback = parameterTypesAndCallback.last() as XC_MethodHook
        val typeCount = parameterTypesAndCallback.size - 1
        val types = Array(typeCount) { index ->
            when (val raw = parameterTypesAndCallback[index]) {
                is Class<*> -> raw
                is String -> findClass(raw, callback.javaClass.classLoader)
                else -> throw IllegalArgumentException(
                    "findAndHookMethod 第 ${index + 1} 个参数不是 Class/String: $raw"
                )
            }
        }
        val method = findMethodExact(hookClass, methodName, types)
        return CompatRuntime.hook(method, callback)
    }

    @JvmStatic
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        val isStaticAccess = obj is Class<*>
        val clazz = if (obj is Class<*>) obj else obj?.javaClass
        requireNotNull(clazz) { "callMethod: 调用目标为 null ($methodName)" }
        val method = findMethodByArgs(clazz, methodName, args)
        method.isAccessible = true
        val receiver = if (isStaticAccess) null else obj
        return try {
            method.invoke(receiver, *args)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @JvmStatic
    fun getObjectField(obj: Any?, fieldName: String): Any? {
        val isStaticAccess = obj is Class<*>
        val clazz = if (obj is Class<*>) obj else obj?.javaClass
        requireNotNull(clazz) { "getObjectField: 目标为 null ($fieldName)" }
        val field = findField(clazz, fieldName)
        field.isAccessible = true
        return field.get(if (isStaticAccess) null else obj)
    }

    // ---------------- 内部辅助 ----------------

    private fun findMethodExact(clazz: Class<*>, name: String, types: Array<Class<*>>): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name == name && method.parameterTypes.contentEquals(types)) {
                    return method
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodException(
            "${clazz.name}.$name(${types.joinToString(",") { it.name }})"
        )
    }

    private fun findMethodByArgs(
        clazz: Class<*>,
        name: String,
        args: Array<out Any?>
    ): Method {
        val candidates = ArrayList<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (method.name != name) continue
                if (method.parameterCount != args.size) continue
                candidates.add(method)
            }
            current = current.superclass
        }
        for (method in candidates) {
            if (argumentsMatch(method.parameterTypes, args)) return method
        }
        throw NoSuchMethodException(
            "${clazz.name}.$name(${args.joinToString(",") { it?.javaClass?.name ?: "null" }})"
        )
    }

    private fun argumentsMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (index in types.indices) {
            val type = types[index]
            val arg = args[index]
            if (arg == null) {
                if (type.isPrimitive) return false
                continue
            }
            if (type.isPrimitive) {
                if (!boxedType(type).isInstance(arg)) return false
            } else if (!type.isInstance(arg)) {
                return false
            }
        }
        return true
    }

    private fun boxedType(primitive: Class<*>): Class<*> = when (primitive) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> primitive
    }

    private fun findField(clazz: Class<*>, name: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                if (field.name == name) return field
            }
            for (iface in current.interfaces) {
                runCatching { return findField(iface, name) }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${clazz.name}.$name")
    }
}
