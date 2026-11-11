package dev.etran.bedwars1058.arenamanagement

import dev.etran.bedwars1058.arenamanagement.arena.ArenaOps
import dev.etran.bedwars1058.arenamanagement.arena.ArenaRepository
import dev.etran.bedwars1058.arenamanagement.arena.BulkRunner
import dev.etran.bedwars1058.arenamanagement.bedwars.BedWarsHook
import dev.etran.bedwars1058.arenamanagement.command.CommandInterceptor
import dev.etran.bedwars1058.arenamanagement.gui.ArenaBrowserMenu
import dev.etran.bedwars1058.arenamanagement.gui.ArenaSelectorMenu
import dev.etran.bedwars1058.arenamanagement.gui.MenuListener
import dev.etran.bedwars1058.arenamanagement.session.ChatPrompts
import dev.etran.bedwars1058.arenamanagement.session.SessionManager
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

class ArenaManagementPlugin : JavaPlugin() {
    lateinit var settings: Settings
        private set
    lateinit var hook: BedWarsHook
        private set
    lateinit var repository: ArenaRepository
        private set
    lateinit var sessions: SessionManager
        private set
    lateinit var prompts: ChatPrompts
        private set
    lateinit var runner: BulkRunner
        private set
    lateinit var ops: ArenaOps
        private set
    lateinit var interceptor: CommandInterceptor
        private set

    /**
     * BedWars1058-MapLoader, if installed. It lists "parked" arenas in BedWars1058 without a loaded world and
     * wakes them through command / join hooks, so everything here goes through those hooks (see ArenaOps).
     */
    val mapLoader: org.bukkit.plugin.Plugin? get() = server.pluginManager.getPlugin("BedWars1058-MapLoader")

    /** BedWars1058-MultiGroup, if installed: it owns the variants of maps shared between groups. */
    val multiGroup: org.bukkit.plugin.Plugin? get() = server.pluginManager.getPlugin("BedWars1058-MultiGroup")

    /** `/bw gui` menus currently open; the refresh task keeps their player counts live. */
    val liveSelectors: MutableSet<ArenaSelectorMenu> = LinkedHashSet()

    private var refreshTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()
        settings = Settings(this)

        hook = BedWarsHook(this)
        if (!hook.connect()) {
            logger.severe("BedWars1058 is not installed - disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }

        repository = ArenaRepository(this)
        sessions = SessionManager()
        prompts = ChatPrompts(this)
        runner = BulkRunner(this)
        ops = ArenaOps(this)
        interceptor = CommandInterceptor(this)

        server.pluginManager.registerEvents(MenuListener(this), this)
        server.pluginManager.registerEvents(prompts, this)
        server.pluginManager.registerEvents(interceptor, this)

        startRefreshTask()
        logger.info("Enabled (server ${Bukkit.getBukkitVersion()}).")
    }

    override fun onDisable() {
        refreshTask?.cancel()
        // Close our menus so nobody is left holding a dead inventory after a reload.
        for (menu in liveSelectors.toList()) menu.player.closeInventory()
    }

    private fun startRefreshTask() {
        refreshTask?.cancel()
        refreshTask = server.scheduler.runTaskTimer(
            this,
            Runnable {
                for (menu in liveSelectors.toList()) {
                    if (!menu.player.isOnline) liveSelectors.remove(menu) else menu.live()
                }
            },
            settings.guiRefreshTicks,
            settings.guiRefreshTicks
        )
    }

    /** Runs [block] on the main thread after [ticks]. */
    fun later(ticks: Long, block: () -> Unit) {
        server.scheduler.runTaskLater(this, Runnable { block() }, ticks)
    }

    fun msg(target: CommandSender, text: String) {
        target.sendMessage(settings.prefix + dev.etran.bedwars1058.arenamanagement.util.Text.color(text))
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase()
        when (sub) {
            "reload" -> {
                if (!sender.hasPermission("arenamanagement.admin")) {
                    msg(sender, "&cYou do not have permission.")
                    return true
                }
                reloadConfig()
                repository.invalidate()
                startRefreshTask()
                msg(sender, "&aConfig reloaded.")
            }
            "menu", "gui" -> {
                if (sender !is Player) {
                    msg(sender, "&cOnly players can open menus.")
                    return true
                }
                if (sub == "menu") {
                    if (!sender.hasPermission(settings.permission)) {
                        msg(sender, "&cYou do not have permission.")
                        return true
                    }
                    ArenaBrowserMenu(this, sender).open()
                } else {
                    interceptor.openSelector(sender, args.getOrNull(1))
                }
            }
            else -> msg(sender, "&7Usage: &e/${label} <reload|menu|gui [group]>")
        }
        return true
    }
}
