package dev.etran.bedwars1058.arenamanagement.arena

import dev.etran.bedwars1058.arenamanagement.ArenaManagementPlugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.ArrayDeque

/**
 * Runs lists of BedWars1058 commands a few at a time (worlds load/unload on the main thread, so a
 * hundred at once would freeze the server), one job after another, then re-reads the arenas and
 * tells the player how many changes actually took effect.
 */
class BulkRunner(private val plugin: ArenaManagementPlugin) {

    class Job(
        val owner: Player,
        val title: String,
        val commands: List<String>,
        /** How many changes the job is expected to make (for the "confirmed X/Y" report). */
        val expected: Int,
        /** Counts how many of those changes are visible in a fresh snapshot. null = unverified. */
        val verify: ((ArenaRepository.Snapshot) -> Int)? = null
    )

    companion object {
        /** Commands starting with this are run by the console instead of the player. */
        const val CONSOLE = "console:"
    }

    private val queue = ArrayDeque<Job>()
    private var running = false

    fun submit(job: Job) {
        queue.add(job)
        if (!running) startNext()
    }

    private fun startNext() {
        val job = queue.poll()
        if (job == null) {
            running = false
            return
        }
        running = true
        plugin.msg(job.owner, "&7${job.title} &8(${job.commands.size} command${if (job.commands.size == 1) "" else "s"})")

        val iterator = job.commands.iterator()
        val perStep = plugin.settings.commandsPerStep
        object : BukkitRunnable() {
            override fun run() {
                if (!job.owner.isOnline) {
                    cancel()
                    plugin.logger.warning("Job '${job.title}' stopped: ${job.owner.name} went offline.")
                    finish(job)
                    return
                }
                var done = 0
                while (done < perStep && iterator.hasNext()) {
                    dispatch(job, iterator.next())
                    done++
                }
                if (!iterator.hasNext()) {
                    cancel()
                    finish(job)
                }
            }
        }.runTaskTimer(plugin, 1L, plugin.settings.ticksBetweenSteps)
    }

    private fun dispatch(job: Job, command: String) {
        try {
            if (command.startsWith(CONSOLE)) {
                // BedWars1058-MultiGroup's /bwmg is not tied to a player; the console always has its permission.
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.removePrefix(CONSOLE).removePrefix("/"))
            } else {
                // BedWars1058 refuses console for every management command, so jobs run as the player.
                plugin.ops.runAsPlayer(job.owner, command)
            }
        } catch (t: Throwable) {
            plugin.logger.warning("Command '$command' failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun finish(job: Job) {
        plugin.repository.invalidate()
        plugin.later(plugin.settings.verifyDelayTicks) {
            val snapshot = plugin.repository.snapshot(force = true)
            val verify = job.verify
            if (verify == null) {
                plugin.msg(job.owner, "&aFinished: &7${job.title}")
            } else {
                val confirmed = verify(snapshot)
                if (confirmed >= job.expected) {
                    plugin.msg(job.owner, "&aDone: &7${job.title} &8- &a$confirmed/${job.expected} confirmed")
                } else {
                    plugin.msg(
                        job.owner,
                        "&eFinished with problems: &7${job.title} &8- &e$confirmed/${job.expected} confirmed. " +
                            "&7Check the console for BedWars1058's error messages."
                    )
                }
            }
            startNext()
        }
    }
}
