package dev.etran.bedwars1058.arenamanagement.arena

import dev.etran.bedwars1058.arenamanagement.ArenaManagementPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Read-only view of BedWars1058-MultiGroup's map files (`plugins/BedWars1058-MultiGroup/maps/<map>.yml`).
 * MultiGroup owns the variants it generates (`<map>-<group>` arenas carrying a `multigroup:` marker), so when it
 * is installed the group tools change its files through its own `/bwmg` commands instead of copying maps.
 */
class MultiGroupLink(private val plugin: ArenaManagementPlugin) {
    val installed: Boolean get() = plugin.multiGroup != null

    class MapDef(
        val name: String,
        val groups: List<String>,
        /** Fixed choices: group -> configuration name or `base`. */
        val pins: Map<String, String>,
        /** Configuration name -> extra names it matches. */
        val configs: Map<String, List<String>>
    ) {
        fun listsGroup(group: String): Boolean = groups.any { it.equals(group, ignoreCase = true) }

        /** The configuration [group] plays this map with, or null when MultiGroup would pick the closest match itself. */
        fun configFor(group: String): String? {
            pins.entries.firstOrNull { it.key.equals(group, ignoreCase = true) }?.let { return it.value }
            return configs.entries.firstOrNull { (name, match) ->
                name.equals(group, ignoreCase = true) || match.any { it.equals(group, ignoreCase = true) }
            }?.key
        }

        fun hasConfig(name: String): Boolean = configs.keys.any { it.equals(name, ignoreCase = true) }
    }

    private fun mapsDir(): File? = plugin.multiGroup?.let { File(it.dataFolder, "maps") }?.takeIf { it.isDirectory }

    fun definitions(): List<MapDef> =
        mapsDir()?.listFiles { f -> f.isFile && f.name.endsWith(".yml", ignoreCase = true) }
            ?.mapNotNull { runCatching { read(it) }.getOrNull() }
            .orEmpty()

    fun definition(map: String): MapDef? = definitions().firstOrNull { it.name.equals(map, ignoreCase = true) }

    private fun read(file: File): MapDef {
        val yml = YamlConfiguration.loadConfiguration(file)
        val pins = LinkedHashMap<String, String>()
        yml.getConfigurationSection("group-configs")?.getKeys(false)?.forEach { key -> yml.getString("group-configs.$key")?.let { pins[key] = it } }
        val configs = LinkedHashMap<String, List<String>>()
        yml.getConfigurationSection("configs")?.getKeys(false)?.forEach { key -> configs[key] = yml.getStringList("configs.$key.match") }
        return MapDef(file.nameWithoutExtension, yml.getStringList("groups"), pins, configs)
    }

    /** True for an arena MultiGroup generated for one group of a shared map; those are never cloned or moved by hand. */
    fun isVariant(arena: String): Boolean {
        val file = File(plugin.hook.bedWarsPlugin?.dataFolder ?: return false, "Arenas/$arena.yml")
        if (!file.isFile) return false
        return runCatching { YamlConfiguration.loadConfiguration(file).isConfigurationSection("multigroup") }.getOrDefault(false)
    }
}
