package dev.etran.bedwars1058.arenamanagement.arena

import dev.etran.bedwars1058.arenamanagement.ArenaManagementPlugin
import dev.etran.bedwars1058.arenamanagement.util.NaturalOrder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent

/**
 * Every management action, expressed as BedWars1058 commands (templates from config.yml). The
 * single-arena and bulk menus both go through here, so behaviour is identical for 1 map or 100.
 */
class ArenaOps(private val plugin: ArenaManagementPlugin) {
    private val repo get() = plugin.repository
    private val settings get() = plugin.settings

    private val validGroupName = Regex("[A-Za-z0-9_\\-]{1,32}")

    /** BedWars1058 refuses to set up maps with capital letters (and '+' is its group separator). */
    private val validArenaName = Regex("[a-z0-9_\\-]{1,32}")

    fun isValidName(name: String): Boolean = validGroupName.matches(name)
    fun isValidArenaName(name: String): Boolean = validArenaName.matches(name)

    private fun template(key: String, vararg values: Pair<String, String>): String {
        var command = settings.command(key)
        for ((placeholder, value) in values) command = command.replace("{$placeholder}", value)
        return command.trim().removePrefix("/")
    }

    /**
     * Runs a command exactly as if the player had typed it: the PlayerCommandPreprocessEvent is fired first,
     * so plugins that hook commands (BedWars1058-MapLoader waking a parked arena on `bw join` / `bw disableArena`,
     * permission plugins, ...) see it. Bukkit.dispatchCommand alone skips that event.
     * Returns false if BedWars1058 threw an exception.
     */
    fun runAsPlayer(player: Player, command: String): Boolean = try {
        val event = PlayerCommandPreprocessEvent(player, "/$command")
        Bukkit.getPluginManager().callEvent(event)
        if (!event.isCancelled) Bukkit.dispatchCommand(player, event.message.removePrefix("/"))
        true
    } catch (t: Throwable) {
        val cause = generateSequence<Throwable>(t) { it.cause }.last()
        plugin.logger.warning("BedWars1058 threw while running '/$command': ${cause.javaClass.simpleName}: ${cause.message}")
        plugin.msg(player, "&cBedWars1058 hit an internal error running &e/$command&c. See the console for details.")
        false
    }

    /**
     * An arena BedWars1058 lists but has no world for. With BedWars1058-MapLoader that is a normal "parked" arena
     * (its command hook handles disabling it). Without it BedWars1058 throws a NullPointerException when disabling.
     */
    private fun cannotHandle(info: ArenaInfo): Boolean = !info.worldLoaded && plugin.mapLoader == null

    private fun brokenMessage(player: Player, name: String) {
        plugin.msg(
            player,
            "&e$name&c is loaded in BedWars1058 but its world is not (the world folder is probably missing or failed to load), " +
                "so BedWars1058 can't disable or set it up. Fix or restore the world, then restart or reload the server."
        )
    }

    private fun submit(
        player: Player,
        title: String,
        commands: List<String>,
        expected: Int,
        verify: ((ArenaRepository.Snapshot) -> Int)?
    ) {
        plugin.runner.submit(BulkRunner.Job(player, title, commands, expected, verify))
    }

    // ---------------------------------------------------------------- setup

    /** Left-click behaviour: disable the arena if it is on, then start BedWars1058's setup. */
    fun setup(player: Player, name: String) {
        val info = repo.snapshot(force = true).find(name)
        if (info == null) {
            plugin.msg(player, "&cNo map named &e$name&c.")
            return
        }
        if (info.name != info.name.lowercase()) {
            plugin.msg(player, "&cBedWars1058 can't set up &e${info.name}&c: map names must be lowercase. Rename the world folder to &a${info.name.lowercase()}&c.")
            return
        }
        if (cannotHandle(info)) {
            brokenMessage(player, info.name)
            return
        }
        player.closeInventory()
        if (info.state == ArenaState.ENABLED) {
            plugin.msg(player, "&e${info.name}&7 is enabled - disabling it first, then starting setup...")
            if (!runAsPlayer(player, template("disable", "arena" to info.name))) return
            repo.invalidate()
            plugin.later(settings.setupDelayTicks) {
                if (player.isOnline) runAsPlayer(player, template("setup", "arena" to info.name))
            }
        } else {
            runAsPlayer(player, template("setup", "arena" to info.name))
        }
    }

    // ---------------------------------------------------------------- bulk-capable actions

    fun setGroup(player: Player, names: Collection<String>, group: String) {
        if (group.equals(settings.defaultGroup, ignoreCase = true)) {
            plugin.msg(player, "&cBedWars1058 can't assign the &e${settings.defaultGroup}&c group with a command - it is the group every un-grouped map already has. Pick or create another group.")
            return
        }
        if (!isValidName(group)) {
            plugin.msg(player, "&cGroup names may only contain letters, digits, _ and - (max 32).")
            return
        }
        val snapshot = repo.snapshot(force = true)
        val infos = names.mapNotNull { snapshot.find(it) }
        val targets = infos.filter { it.state != ArenaState.UNCONFIGURED }
        val skipped = names.size - targets.size
        if (skipped > 0) plugin.msg(player, "&7Skipping &e$skipped&7 map(s) that are not set up yet (set them up first).")
        if (targets.isEmpty()) return

        // BedWars1058 only accepts groups that exist, and compares names case-sensitively.
        val existing = snapshot.canonicalGroup(group)
        val finalGroup = existing ?: group
        val commands = ArrayList<String>()
        if (existing == null) commands += template("create-group", "group" to finalGroup)
        targets.forEach { commands += template("set-group", "arena" to it.name, "group" to finalGroup) }

        submit(player, "Moving ${targets.size} map(s) to group $finalGroup", commands, targets.size) { snap ->
            targets.count { snap.find(it.name)?.group.equals(finalGroup, ignoreCase = true) }
        }
    }

    fun enable(player: Player, names: Collection<String>) {
        val snapshot = repo.snapshot(force = true)
        val infos = names.mapNotNull { snapshot.find(it) }
        val targets = infos.filter { it.state == ArenaState.DISABLED }
        reportSkipped(player, infos, targets, "enable", "already enabled or not set up")
        if (targets.isEmpty()) return
        submit(player, "Enabling ${targets.size} map(s)", targets.map { template("enable", "arena" to it.name) }, targets.size) { snap ->
            targets.count { snap.find(it.name)?.state == ArenaState.ENABLED }
        }
    }

    fun disable(player: Player, names: Collection<String>) {
        val snapshot = repo.snapshot(force = true)
        val infos = names.mapNotNull { snapshot.find(it) }
        val targets = infos.filter { it.state == ArenaState.ENABLED && !cannotHandle(it) }
        infos.filter { cannotHandle(it) }.forEach { brokenMessage(player, it.name) }
        reportSkipped(player, infos.filter { !cannotHandle(it) }, targets, "disable", "already disabled or not set up")
        if (targets.isEmpty()) return
        submit(player, "Disabling ${targets.size} map(s)", targets.map { template("disable", "arena" to it.name) }, targets.size) { snap ->
            targets.count { snap.find(it.name)?.state != ArenaState.ENABLED }
        }
    }

    fun delete(player: Player, names: Collection<String>) {
        val snapshot = repo.snapshot(force = true)
        val infos = names.mapNotNull { snapshot.find(it) }
        val targets = infos.filter { it.state != ArenaState.UNCONFIGURED && !cannotHandle(it) }
        infos.filter { cannotHandle(it) }.forEach { brokenMessage(player, it.name) }
        reportSkipped(player, infos.filter { !cannotHandle(it) }, targets, "delete", "not set up, nothing to delete")
        if (targets.isEmpty()) return
        val commands = ArrayList<String>()
        for (target in targets) {
            // BedWars1058 refuses to delete a loaded arena, so switch it off first.
            if (target.state == ArenaState.ENABLED) commands += template("disable", "arena" to target.name)
            // delArena only deletes when it is typed a second time within 2 seconds.
            commands += template("delete", "arena" to target.name)
            commands += template("delete", "arena" to target.name)
        }
        submit(player, "Deleting ${targets.size} map(s)", commands, targets.size) { snap ->
            targets.count { val s = snap.find(it.name)?.state; s == null || s == ArenaState.UNCONFIGURED }
        }
    }

    /** Clones one map to [newName]. */
    fun clone(player: Player, source: String, newName: String) {
        cloneMany(player, listOf(source)) { newName }
    }

    /** Clones every map in [names], naming each copy `<name><suffix>`. */
    fun cloneWithSuffix(player: Player, names: Collection<String>, suffix: String) {
        cloneMany(player, names) { it + suffix }
    }

    private fun cloneMany(player: Player, names: Collection<String>, newNameFor: (String) -> String) {
        val snapshot = repo.snapshot(force = true)
        val pairs = ArrayList<Pair<ArenaInfo, String>>()
        val usedNames = HashSet<String>()
        for (info in names.mapNotNull { snapshot.find(it) }) {
            if (info.state == ArenaState.UNCONFIGURED) {
                plugin.msg(player, "&7Skipping &e${info.name}&7: not set up yet.")
                continue
            }
            val target = newNameFor(info.name)
            when {
                !isValidArenaName(target) -> plugin.msg(player, "&7Skipping &e${info.name}&7: &e$target&7 is not a valid map name (lowercase letters, digits, _ and - only).")
                snapshot.find(target) != null || !usedNames.add(target.lowercase()) -> plugin.msg(player, "&7Skipping &e${info.name}&7: a map named &e$target&7 already exists.")
                else -> pairs += info to target
            }
        }
        if (pairs.isEmpty()) return
        submit(player, "Cloning ${pairs.size} map(s)", pairs.map { (info, target) -> template("clone", "arena" to info.name, "new" to target) }, pairs.size) { snap ->
            pairs.count { (_, target) -> snap.find(target) != null }
        }
    }

    private fun reportSkipped(player: Player, all: List<ArenaInfo>, targets: List<ArenaInfo>, verb: String, why: String) {
        val skipped = all.size - targets.size
        if (skipped > 0) plugin.msg(player, "&7Not trying to $verb &e$skipped&7 map(s): $why.")
    }

    // ---------------------------------------------------------------- groups

    fun createGroup(player: Player, group: String) {
        if (!isValidName(group)) {
            plugin.msg(player, "&cGroup names may only contain letters, digits, _ and - (max 32).")
            return
        }
        if (repo.snapshot(force = true).hasGroup(group)) {
            plugin.msg(player, "&cThe group &e$group&c already exists.")
            return
        }
        runAsPlayer(player, template("create-group", "group" to group))
        repo.invalidate()
    }

    fun removeGroup(player: Player, group: String) {
        if (group.equals(settings.defaultGroup, ignoreCase = true)) {
            plugin.msg(player, "&cThe default group can not be removed.")
            return
        }
        runAsPlayer(player, template("remove-group", "group" to (repo.snapshot().canonicalGroup(group) ?: group)))
        repo.invalidate()
    }

    // ---------------------------------------------------------------- duplicate / combine groups

    private val multiGroup = MultiGroupLink(plugin)

    fun usesMultiGroup(): Boolean = multiGroup.installed

    /** Confirmation text shown before a duplication. */
    fun duplicateSummary(source: String, newGroup: String): List<String> {
        val count = repo.snapshot().inGroup(source).count { !multiGroup.isVariant(it.name) }
        return if (multiGroup.installed) listOf("&7Maps in $source: &f$count", "&7MultiGroup: maps are added to &f$newGroup", "&7and their configs copied. No worlds are copied.")
        else listOf("&7Maps in $source: &f$count", "&7Each is cloned (world + config) to &f<map>-${newGroup.lowercase()}", "&7and put in &f$newGroup&7. Enabled maps are switched", "&7off briefly while they are cloned.")
    }

    fun combineSummary(source: String, target: String): List<String> {
        val count = repo.snapshot().inGroup(source).count { !multiGroup.isVariant(it.name) }
        return listOf("&7Maps moved to &f$target&7: &f$count", "&7The group &f$source&7 is removed afterwards.")
    }

    private fun bwmg(vararg parts: String) = BulkRunner.CONSOLE + "bwmg " + parts.joinToString(" ")

    /**
     * Copies [source] into a new group called [newGroup].
     *
     *  - Without BedWars1058-MultiGroup every set-up map of the group is cloned (world and config, via `bw cloneArena`)
     *    to `<map>-<newgroup>` and the copy joins the new group. Enabled maps are switched off for the clone and back on.
     *  - With MultiGroup nothing on disk is copied: each map is added to the new group and the group's configuration
     *    is duplicated, so one world serves both groups.
     *
     * Everything goes through the same commands the menus use, so BedWars1058-MapLoader's hooks still run.
     */
    fun duplicateGroup(player: Player, source: String, newGroup: String) {
        val snapshot = repo.snapshot(force = true)
        val from = snapshot.canonicalGroup(source)
        if (from == null) {
            plugin.msg(player, "&cThere is no group called &e$source&c.")
            return
        }
        if (from.equals(settings.defaultGroup, ignoreCase = true)) {
            plugin.msg(player, "&cThe default group can't be duplicated: it is just every map without a group.")
            return
        }
        if (!isValidName(newGroup) || newGroup.equals(settings.defaultGroup, ignoreCase = true)) {
            plugin.msg(player, "&cGroup names may only contain letters, digits, _ and - (max 32), and can't be the default group.")
            return
        }
        if (snapshot.hasGroup(newGroup)) {
            plugin.msg(player, "&cThe group &e$newGroup&c already exists.")
            return
        }
        if (multiGroup.installed) duplicateWithMultiGroup(player, from, newGroup, snapshot) else duplicateWithClones(player, from, newGroup, snapshot)
    }

    private fun duplicateWithClones(player: Player, from: String, newGroup: String, snapshot: ArenaRepository.Snapshot) {
        val suffix = "-" + newGroup.lowercase()
        val commands = arrayListOf(template("create-group", "group" to newGroup))
        val copies = ArrayList<String>()
        for (info in snapshot.inGroup(from)) {
            if (multiGroup.isVariant(info.name)) continue
            val target = info.name + suffix
            when {
                cannotHandle(info) -> { brokenMessage(player, info.name); continue }
                !isValidArenaName(target) -> { plugin.msg(player, "&7Skipping &e${info.name}&7: &e$target&7 is not a valid map name (lowercase letters, digits, _ and - only, 32 max)."); continue }
                snapshot.find(target) != null -> { plugin.msg(player, "&7Skipping &e${info.name}&7: a map named &e$target&7 already exists."); continue }
            }
            val wasOn = info.state == ArenaState.ENABLED
            // BedWars1058 only clones a map that is switched off.
            if (wasOn) commands += template("disable", "arena" to info.name)
            commands += template("clone", "arena" to info.name, "new" to target)
            commands += template("set-group", "arena" to target, "group" to newGroup)
            if (wasOn) {
                commands += template("enable", "arena" to info.name)
                commands += template("enable", "arena" to target)
            }
            copies += target
        }
        if (copies.isEmpty()) {
            plugin.msg(player, "&cNothing to duplicate: &e$from&c has no maps that can be copied.")
            return
        }
        submit(player, "Duplicating group $from as $newGroup (${copies.size} map(s) cloned)", commands, copies.size) { snap ->
            copies.count { snap.find(it)?.group.equals(newGroup, ignoreCase = true) }
        }
    }

    private fun duplicateWithMultiGroup(player: Player, from: String, newGroup: String, snapshot: ArenaRepository.Snapshot) {
        val defs = multiGroup.definitions()
        // Maps whose own arena sits in the group, plus shared maps that MultiGroup lists the group for.
        val maps = LinkedHashSet<String>()
        snapshot.inGroup(from).filter { !multiGroup.isVariant(it.name) }.forEach { maps += it.name }
        defs.filter { it.listsGroup(from) }.forEach { maps += it.name }
        if (maps.isEmpty()) {
            plugin.msg(player, "&cNothing to duplicate: &e$from&c has no maps.")
            return
        }
        val commands = arrayListOf(template("create-group", "group" to newGroup))
        for (map in maps) {
            val def = defs.firstOrNull { it.name.equals(map, ignoreCase = true) }
            val baseIsSource = snapshot.find(map)?.group.equals(from, ignoreCase = true)
            if (def == null) commands += bwmg("create", map)
            commands += bwmg("group", "add", map, newGroup)
            // The new group plays the map the way the old one does: base arena settings, or a copy of its configuration.
            val config = if (baseIsSource) "base" else def?.configFor(from)
            when {
                config == null -> {}
                config.equals("base", ignoreCase = true) -> commands += bwmg("fix", map, newGroup, "base")
                else -> {
                    val copy = newGroup.lowercase()
                    if (def?.hasConfig(copy) != true) commands += bwmg("config", "create", map, copy, config)
                    commands += bwmg("fix", map, newGroup, copy)
                }
            }
        }
        commands += bwmg("reload")
        submit(player, "Duplicating group $from as $newGroup (${maps.size} map(s), configs only - MultiGroup shares the worlds)", commands, maps.size) { _ ->
            multiGroup.definitions().count { d -> maps.any { it.equals(d.name, ignoreCase = true) } && d.listsGroup(newGroup) }
        }
    }

    /**
     * Merges [sources] into [target]: every map of the source groups joins the target and the emptied source groups
     * are removed. With MultiGroup, shared maps are re-listed for the target and keep their configuration.
     */
    fun combineGroups(player: Player, sources: Collection<String>, target: String) {
        val snapshot = repo.snapshot(force = true)
        val dflt = settings.defaultGroup
        val finalTarget = snapshot.canonicalGroup(target) ?: target
        if (finalTarget.equals(dflt, ignoreCase = true) || !isValidName(finalTarget)) {
            plugin.msg(player, "&cBedWars1058 can't assign the default group by command. Pick another target group.")
            return
        }
        val from = sources.mapNotNull { snapshot.canonicalGroup(it) }
            .filter { !it.equals(dflt, ignoreCase = true) && !it.equals(finalTarget, ignoreCase = true) }.distinct()
        if (from.isEmpty()) {
            plugin.msg(player, "&cPick at least one group other than the target (and not the default group).")
            return
        }

        val commands = ArrayList<String>()
        if (!snapshot.hasGroup(finalTarget)) commands += template("create-group", "group" to finalTarget)
        val defs = if (multiGroup.installed) multiGroup.definitions() else emptyList()
        val moved = LinkedHashSet<String>()
        val shared = ArrayList<String>()
        var needsReload = false

        for (group in from) {
            for (info in snapshot.inGroup(group)) {
                if (multiGroup.isVariant(info.name)) continue
                commands += template("set-group", "arena" to info.name, "group" to finalTarget)
                moved += info.name
                // the base arena now lives in the target, so MultiGroup must not also list the target for it
                defs.firstOrNull { it.name.equals(info.name, ignoreCase = true) && it.listsGroup(finalTarget) }?.let {
                    commands += bwmg("group", "remove", it.name, finalTarget)
                    needsReload = true
                }
            }
            for (def in defs.filter { it.listsGroup(group) }) {
                commands += bwmg("group", "add", def.name, finalTarget)
                val config = def.configFor(group)
                if (config != null && def.configFor(finalTarget) == null) commands += bwmg("fix", def.name, finalTarget, config)
                commands += bwmg("fix", def.name, group, "auto")
                commands += bwmg("group", "remove", def.name, group)
                shared += def.name
                needsReload = true
            }
        }
        if (needsReload) commands += bwmg("reload")
        from.forEach { commands += template("remove-group", "group" to it) }

        val total = moved.size + shared.size
        submit(player, "Combining ${from.joinToString(", ")} into $finalTarget ($total map(s))", commands, total) { snap ->
            moved.count { snap.find(it)?.group.equals(finalTarget, ignoreCase = true) } +
                shared.count { name -> multiGroup.definition(name)?.listsGroup(finalTarget) == true }
        }
    }

    // ---------------------------------------------------------------- chat output

    /** Joins through `bw join <arena>`, so BedWars1058's own checks (and MapLoader's wake-up) apply. */
    fun join(player: Player, arena: String) {
        runAsPlayer(player, template("join", "arena" to arena))
    }

    fun joinRandom(player: Player) {
        runAsPlayer(player, template("join-random"))
    }

    fun sendArenaList(player: Player) {
        val snapshot = repo.snapshot(force = true)
        plugin.msg(
            player,
            "&7Maps: &f${snapshot.arenas.size} &8| &aEnabled ${snapshot.count(ArenaState.ENABLED)} &8| " +
                "&cDisabled ${snapshot.count(ArenaState.DISABLED)} &8| &7Not set up ${snapshot.count(ArenaState.UNCONFIGURED)} &8| " +
                "&eUnassigned ${snapshot.unassignedCount}"
        )
        val byGroup = snapshot.arenas.filter { it.state != ArenaState.UNCONFIGURED }.groupBy { it.group.lowercase() }
        for (group in snapshot.groups.sortedWith(NaturalOrder)) {
            val members = byGroup[group.lowercase()] ?: continue
            player.sendMessage(color("&e$group &8(${members.size}): " + members.joinToString("&7, ") { "${it.state.color}${it.name}" }))
        }
        val notSetUp = snapshot.arenas.filter { it.state == ArenaState.UNCONFIGURED }
        if (notSetUp.isNotEmpty()) {
            player.sendMessage(color("&7Not set up &8(${notSetUp.size}): &7" + notSetUp.joinToString(", ") { it.name }))
        }
    }

    fun sendGroupList(player: Player) {
        val snapshot = repo.snapshot(force = true)
        plugin.msg(player, "&7Groups &8(${snapshot.groups.size})&7:")
        for (group in snapshot.groups) {
            val members = snapshot.inGroup(group)
            val enabled = members.count { it.state == ArenaState.ENABLED }
            player.sendMessage(color("  &e$group &8- &f${members.size} map(s)&7, &a$enabled enabled"))
        }
    }

    fun sendCommandHelp(player: Player) {
        val lines = listOf(
            "&bArena management commands",
            "&e/bw setupArena &7- map manager (left-click set up, right-click options)",
            "&e/bw arenaGroup &7- group manager (create, remove, list, assign)",
            "&e/bw arenaList &7- list every map by group and state",
            "&e/bw gui [group] &7- the join menu players see",
            "&7Typing these with arguments (e.g. &e/bw setupArena MyMap&7) runs plain BedWars1058."
        )
        lines.forEach { player.sendMessage(color(it)) }
    }

    private fun color(text: String) = dev.etran.bedwars1058.arenamanagement.util.Text.color(text)
}
