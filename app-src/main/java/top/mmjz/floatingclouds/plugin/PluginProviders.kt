package top.mmjz.floatingclouds.plugin

import android.util.Log

object PluginProviders {
    private val registry = mutableMapOf<String, IPlugin>()

    fun register(name: String, plugin: IPlugin) {
        registry[name] = plugin
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IPlugin> from(clazz: Class<T>): T? {
        val result = registry.values.firstOrNull { clazz.isInstance(it) }
        if (result == null) {
            Log.w("PluginProviders", "Plugin not found: ${clazz.simpleName}")
        }
        return result as? T
    }
}
