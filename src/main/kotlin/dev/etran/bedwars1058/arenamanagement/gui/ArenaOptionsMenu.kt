package dev.etran.bedwars1058.arenamanagement.gui

import dev.etran.bedwars1058.arenamanagement.ArenaManagementPlugin
import dev.etran.bedwars1058.arenamanagement.arena.ArenaState
import dev.etran.bedwars1058.arenamanagement.arena.GroupChoice
import org.bukkit.entity.Player

/** Right-click on a map: everything you can do to that one map. */
class ArenaOptionsMenu(plugin: ArenaManagementPlugin, player: Player, private val arenaName: String) : Menu(plugin, player) {
    override val rows = 3

    override fun title() = "&8Options: $arenaName"

    override fun render() {
        val arena = plugin.repository.snapshot(force = true).find(arenaName)
        if (arena == null) {
            set(13, icon("info", "&cThis map no longer exists"))
            set(22, icon("back", "&cBack"), { go(ArenaBrowserMenu(plugin, player)) })
            return
        }
        val configured = arena.state != ArenaState.UNCONFIGURED
        val enabled = arena.state == ArenaState.ENABLED
        val groupText = if (configured) arena.group + if (arena.unassigned) " (unassigned)" else "" else "-"

        set(
            4,
            icon(
                "info", "&f${arena.name}",
                listOf("&7State: ${arena.state.color}${arena.state.label}", "&7Group: &f$groupText") +
                    (plugin.ops.membershipsOf(arena.name).drop(1).takeIf { it.isNotEmpty() }?.let { listOf("&7Also in: &f" + it.joinToString(", ") { m -> m.group }) } ?: emptyList()) +
                    if (!arena.worldLoaded) (if (plugin.mapLoader != null) listOf("", "&bParked by MapLoader: loads when played.") else listOf("", "&4World not loaded in BedWars1058.", "&cDisable / set up won't work until it is fixed.")) else emptyList()
            )
        )

        set(
            10,
            icon("action-setup", "&aSet up", listOf("&7Start BedWars1058 setup for this map.") + if (enabled) listOf("&8(disables the arena first)") else emptyList()),
            { plugin.ops.setup(player, arena.name) }
        )

        if (configured) {
            set(11, icon("action-group", "&bChange group", listOf("&7Current: &f$groupText", "", "&eClick to choose")), {
                go(
                    GroupPickerMenu(
                        plugin, player, "Move ${arena.name} to...", includeAll = false, includeUnassigned = false, allowCreate = true,
                        onPick = { choice ->
                            if (choice is GroupChoice.Named) plugin.ops.setGroup(player, listOf(arena.name), choice.name)
                            player.closeInventory()
                        },
                        onBack = { go(ArenaOptionsMenu(plugin, player, arena.name)) }
                    )
                )
            })
            set(20, icon("action-create", "&bAdd to another group", listOf("&7The map also plays in the group you pick,", "&7with its own copy of the config.", if (plugin.ops.usesMultiGroup()) "&7(MultiGroup: the world is shared)" else "&7(Cloned to <map>-<group>)", "", "&eClick to choose a group")), {
                go(
                    GroupPickerMenu(
                        plugin, player, "Also add ${arena.name} to...", includeAll = false, includeUnassigned = false, allowCreate = true,
                        onPick = { choice ->
                            if (choice is GroupChoice.Named) plugin.ops.addToGroup(player, listOf(arena.name), choice.name)
                            player.closeInventory()
                        },
                        onBack = { go(ArenaOptionsMenu(plugin, player, arena.name)) }
                    )
                )
            })
            set(12, icon("action-clone", "&bClone", listOf("&7Copy this map under a new name.", "", "&eClick, then type the new name")), {
                plugin.prompts.ask(player, "&eType the name for the copy of &f${arena.name}&e:") { text ->
                    plugin.ops.clone(player, arena.name, text)
                }
            })
        } else {
            set(11, icon("unavailable", "&7Change group", listOf("&cSet this map up first.")))
            set(12, icon("unavailable", "&7Clone", listOf("&cSet this map up first.")))
        }

        if (arena.state == ArenaState.DISABLED) {
            set(13, icon("action-enable", "&aEnable", listOf("&7Load this arena so players can join.")), {
                plugin.ops.enable(player, listOf(arena.name))
                player.closeInventory()
            })
        } else {
            set(13, icon("unavailable", "&7Enable", listOf(if (enabled) "&cAlready enabled." else "&cSet this map up first.")))
        }

        if (enabled) {
            set(14, icon("action-disable", "&cDisable", listOf("&7Unload this arena.")), {
                plugin.ops.disable(player, listOf(arena.name))
                player.closeInventory()
            })
        } else {
            set(14, icon("unavailable", "&7Disable", listOf(if (configured) "&cAlready disabled." else "&cSet this map up first.")))
        }

        if (configured) {
            set(15, icon("action-delete", "&4Delete", listOf("&7Permanently remove this arena.", "", "&cAsks for confirmation.")), {
                val doDelete = {
                    plugin.ops.delete(player, listOf(arena.name))
                    player.closeInventory()
                }
                if (plugin.settings.confirmDelete) {
                    go(
                        ConfirmMenu(
                            plugin, player, "&cDelete ${arena.name}?", listOf("&7This can not be undone."),
                            onConfirm = doDelete,
                            onCancel = { go(ArenaOptionsMenu(plugin, player, arena.name)) }
                        )
                    )
                } else {
                    doDelete()
                }
            })
        } else {
            set(15, icon("unavailable", "&7Delete", listOf("&cNothing to delete - not set up.")))
        }

        val session = plugin.sessions.get(player)
        val selected = session.isSelected(arena.name)
        set(16, icon("select", if (selected) "&eRemove from selection" else "&aAdd to selection", listOf("&7For bulk actions.", "&7Selected: &f${session.selectedCount}")), {
            session.toggle(arena.name)
            refresh()
        })

        set(22, icon("back", "&cBack to maps"), { go(ArenaBrowserMenu(plugin, player)) })
    }
}
