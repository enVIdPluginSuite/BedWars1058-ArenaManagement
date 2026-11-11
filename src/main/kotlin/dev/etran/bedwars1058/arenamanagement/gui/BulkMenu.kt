package dev.etran.bedwars1058.arenamanagement.gui

import dev.etran.bedwars1058.arenamanagement.ArenaManagementPlugin
import dev.etran.bedwars1058.arenamanagement.arena.ArenaInfo
import dev.etran.bedwars1058.arenamanagement.arena.ArenaState
import dev.etran.bedwars1058.arenamanagement.arena.GroupChoice
import org.bukkit.entity.Player

/**
 * Pick maps (this page, everything matching the filters across all pages, every map, all
 * unassigned maps, or by hand with shift-click) then act on all of them at once.
 */
class BulkMenu(plugin: ArenaManagementPlugin, player: Player) : Menu(plugin, player) {
    override val rows = 5
    private val session = plugin.sessions.get(player)
    private val pageSize = 45

    override fun title() = "&8Selection (${session.selectedCount})"

    private fun filtered(): List<ArenaInfo> = plugin.repository.snapshot().arenas.filter { session.filter.matches(it) }

    override fun render() {
        val snapshot = plugin.repository.snapshot(force = true)
        val selected = session.resolve(snapshot)
        val filtered = filtered()
        val pageItems = filtered.drop(session.page * pageSize).take(pageSize)

        set(
            4,
            icon(
                "info", "&fSelected: &b${selected.size}",
                listOf(
                    "&aEnabled: &f${selected.count { it.state == ArenaState.ENABLED }}",
                    "&cDisabled: &f${selected.count { it.state == ArenaState.DISABLED }}",
                    "&7Not set up: &f${selected.count { it.state == ArenaState.UNCONFIGURED }}",
                    "&eUnassigned: &f${selected.count { it.unassigned }}",
                    "",
                    "&7Filters: " + if (session.filter.isActive) "&aactive" else "&7none"
                )
            )
        )

        // ---- selection tools (row 2)
        set(10, icon("select-page", "&aSelect this page", listOf("&7Adds the &f${pageItems.size}&7 maps on page ${session.page + 1}.")), {
            session.select(pageItems)
            refresh()
        })
        set(11, icon("select-page", "&aSelect all matching", listOf("&7Adds all &f${filtered.size}&7 maps that match the", "&7current filters, across every page.")), {
            session.select(filtered)
            refresh()
        })
        set(12, icon("select-page", "&aSelect every map", listOf("&7Adds all &f${snapshot.arenas.size}&7 maps, ignoring filters.")), {
            session.select(snapshot.arenas)
            refresh()
        })
        set(13, icon("select-page", "&eSelect unassigned", listOf("&7Adds the &f${snapshot.unassignedCount}&7 set-up maps that are", "&7still in the default group.")), {
            session.select(snapshot.arenas.filter { it.unassigned })
            refresh()
        })
        set(14, icon("filter", "&bChange filters", session.filter.describe() + listOf("", "&7Filter by letter, state or group,", "&7then use &fSelect all matching&7.", "", "&eClick to open")), {
            go(FilterMenu(plugin, player))
        })
        set(15, icon("cancel", "&cDeselect this page", listOf("&7Removes the maps on this page.")), {
            session.deselect(pageItems)
            refresh()
        })
        set(16, icon("cancel", "&cClear selection", listOf("&7Selected: &f${selected.size}")), {
            session.clear()
            refresh()
        })

        // ---- actions (row 4)
        val names = selected.map { it.name }
        val setUp = selected.filter { it.state != ArenaState.UNCONFIGURED }

        set(28, icon("action-group", "&bMove to group", listOf("&7Maps affected: &f${setUp.size}", "", "&eClick to choose a group")), {
            if (guard(setUp.isEmpty(), "None of the selected maps are set up.")) return@set
            go(
                GroupPickerMenu(
                    plugin, player, "Move ${setUp.size} map(s) to...", includeAll = false, includeUnassigned = false, allowCreate = true,
                    onPick = { choice ->
                        if (choice is GroupChoice.Named) plugin.ops.setGroup(player, names, choice.name)
                        player.closeInventory()
                    },
                    onBack = { go(BulkMenu(plugin, player)) }
                )
            )
        })

        set(33, icon("action-create", "&bAdd to another group", listOf("&7Maps affected: &f${setUp.size}", "&7Each also plays in the group, with its own config copy.", "", "&eClick to choose a group")), {
            if (guard(setUp.isEmpty(), "None of the selected maps are set up.")) return@set
            go(
                GroupPickerMenu(
                    plugin, player, "Also add ${setUp.size} map(s) to...", includeAll = false, includeUnassigned = false, allowCreate = true,
                    onPick = { choice ->
                        if (choice is GroupChoice.Named) plugin.ops.addToGroup(player, names, choice.name)
                        player.closeInventory()
                    },
                    onBack = { go(BulkMenu(plugin, player)) }
                )
            )
        })

        val enableCount = selected.count { it.state == ArenaState.DISABLED }
        set(29, icon("action-enable", "&aEnable", listOf("&7Maps affected: &f$enableCount", "&8(only disabled, set-up maps)")), {
            if (guard(enableCount == 0, "No selected map can be enabled.")) return@set
            plugin.ops.enable(player, names)
            player.closeInventory()
        })

        val disableCount = selected.count { it.state == ArenaState.ENABLED }
        set(30, icon("action-disable", "&cDisable", listOf("&7Maps affected: &f$disableCount", "&8(only enabled maps)")), {
            if (guard(disableCount == 0, "No selected map is enabled.")) return@set
            plugin.ops.disable(player, names)
            player.closeInventory()
        })

        set(31, icon("action-clone", "&bClone", listOf("&7Maps affected: &f${setUp.size}", "", "&eClick, then type a name suffix", "&8e.g. \"-copy\" makes Map -> Map-copy")), {
            if (guard(setUp.isEmpty(), "None of the selected maps are set up.")) return@set
            plugin.prompts.ask(player, "&eType the suffix to add to each copy's name (e.g. &f-copy&e):") { text ->
                plugin.ops.cloneWithSuffix(player, names, text)
            }
        })

        set(32, icon("action-delete", "&4Delete", listOf("&7Maps affected: &f${setUp.size}", "", "&cAsks for confirmation.")), {
            if (guard(setUp.isEmpty(), "None of the selected maps are set up.")) return@set
            val doDelete = {
                plugin.ops.delete(player, names)
                player.closeInventory()
            }
            if (plugin.settings.confirmDelete) {
                go(
                    ConfirmMenu(
                        plugin, player, "&cDelete ${setUp.size} map(s)?",
                        setUp.take(8).map { "&7- ${it.name}" } + if (setUp.size > 8) listOf("&7...and ${setUp.size - 8} more") else emptyList(),
                        onConfirm = doDelete,
                        onCancel = { go(BulkMenu(plugin, player)) }
                    )
                )
            } else {
                doDelete()
            }
        })

        set(40, icon("back", "&cBack to maps"), { go(ArenaBrowserMenu(plugin, player)) })
    }

    /** Sends [message] and returns true when [blocked]. */
    private fun guard(blocked: Boolean, message: String): Boolean {
        if (blocked) msg("&c$message")
        return blocked
    }
}
