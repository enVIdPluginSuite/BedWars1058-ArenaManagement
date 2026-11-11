package dev.etran.bedwars1058.arenamanagement.gui

import dev.etran.bedwars1058.arenamanagement.ArenaManagementPlugin
import dev.etran.bedwars1058.arenamanagement.arena.ArenaOps
import org.bukkit.entity.Player

/** Shown when a map plays in several groups: pick which group's version to set up. */
class SetupGroupMenu(
    plugin: ArenaManagementPlugin,
    player: Player,
    private val map: String,
    private val memberships: List<ArenaOps.Membership>
) : Menu(plugin, player) {
    override val rows get() = ((memberships.size + 8) / 9 + 2).coerceIn(3, 6)

    override fun title() = "&8Set up $map: which group?"

    override fun render() {
        val slots = (rows - 2) * 9
        memberships.take(slots).forEachIndexed { index, m ->
            val lore = ArrayList<String>()
            when (m.kind) {
                ArenaOps.Kind.BASE -> lore += "&7The map itself (arena &f${m.arena}&7)."
                ArenaOps.Kind.COPY -> lore += "&7Its own copy: arena &f${m.arena}&7."
                ArenaOps.Kind.SHARED -> {
                    lore += "&7Shared by MultiGroup (same world)."
                    lore += "&7Configuration: &f${m.config ?: "closest match"}"
                }
            }
            lore += ""
            lore += if (m.kind == ArenaOps.Kind.SHARED) "&eClick for how to edit this group's config" else "&eClick to start setup"
            set(index, icon("group", "&a${m.group}", lore)) { choose(m) }
        }
        set(rows * 9 - 5, icon("back", "&cBack"), { go(ArenaOptionsMenu(plugin, player, map)) })
    }

    private fun choose(m: ArenaOps.Membership) {
        when (m.kind) {
            ArenaOps.Kind.SHARED -> {
                player.closeInventory()
                val cfg = m.config ?: "<config>"
                msg("&e${m.group}&7 plays &e$map&7 with its own MultiGroup configuration &f$cfg&7. BedWars1058's setup only edits the base arena, so change this group with:")
                msg("&f/bwmg set $map $cfg <path> <value>  &7or  &f/bwmg setloc $map $cfg <path>")
                msg("&7Then &f/bwmg reload&7. Set up the base arena by choosing its own group here.")
            }
            else -> plugin.ops.setupDirect(player, m.arena)
        }
    }
}
