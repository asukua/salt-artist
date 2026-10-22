package dev.saltartist.bridge

import dev.saltartist.PluginLog

/**
 * 宿主内部反射入口的公共工具。所有对宿主混淆类的访问都集中在这里，
 * 失败时返回 null 而不是抛出，插件据此降级到原生能力。
 */
object HostReflection {
    fun classOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name, false, HostReflection::class.java.classLoader) }.getOrNull()

    fun singletonOrNull(className: String): Any? =
        runCatching { classOrNull(className)?.getField("INSTANCE")?.get(null) }.getOrNull()

    fun noArgMethod(target: Any?, vararg names: String): java.lang.reflect.Method? {
        val type = target?.javaClass ?: return null
        val wanted = names.toSet()
        return runCatching {
            type.methods.firstOrNull { it.parameterCount == 0 && it.name in wanted }
                ?: type.declaredMethods.firstOrNull { it.parameterCount == 0 && it.name in wanted }
                    ?.apply { isAccessible = true }
        }.getOrNull()
    }

    fun invoke(target: Any?, method: java.lang.reflect.Method?, vararg args: Any?): Any? =
        runCatching { method?.invoke(target, *args) }.getOrNull()

    /** 读取 Kotlin data class 的字符串属性，兼容 getName / getTitle 等命名。 */
    fun stringProperty(target: Any?, vararg names: String): String? {
        if (target == null) return null
        val method = noArgMethod(target, *names) ?: return null
        return runCatching { method.invoke(target) as? String }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }

    fun logFailure(log: PluginLog, action: String, error: Throwable?) {
        if (error != null) log.log("$action failed: ${log.describe(error)}")
    }
}
