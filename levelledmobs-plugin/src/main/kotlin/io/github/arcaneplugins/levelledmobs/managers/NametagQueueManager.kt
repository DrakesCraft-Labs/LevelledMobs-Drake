package io.github.arcaneplugins.levelledmobs.managers

import java.time.Instant
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import io.github.arcaneplugins.levelledmobs.LevelledMobs
import io.github.arcaneplugins.levelledmobs.misc.NametagTimerChecker
import io.github.arcaneplugins.levelledmobs.misc.QueueItem
import io.github.arcaneplugins.levelledmobs.nametag.NametagSender
import io.github.arcaneplugins.levelledmobs.nametag.NametagSenderHandler
import io.github.arcaneplugins.levelledmobs.result.NametagResult
import io.github.arcaneplugins.levelledmobs.enums.NametagVisibilityEnum
import io.github.arcaneplugins.levelledmobs.util.LibsDisguisesUtils
import io.github.arcaneplugins.levelledmobs.util.Log
import io.github.arcaneplugins.levelledmobs.util.MessageUtils
import io.github.arcaneplugins.levelledmobs.util.Utils
import io.github.arcaneplugins.levelledmobs.wrappers.LivingEntityWrapper
import io.github.arcaneplugins.levelledmobs.wrappers.SchedulerWrapper
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/**
 * Queues up mob nametag updates so they can be applied in a background thread
 *
 * @author stumper66
 * @since 3.0.0
 */
class NametagQueueManager {
    @Volatile private var isRunning = false
    @Volatile private var doThread = false
    private var nametagSender: NametagSender? = null
    private var hasLibsDisguisesInstalled = false
    var disableNametagJava = false
    var disableNametagBedrock = false
    var queueTask: BukkitTask? = null
    private val queue = LinkedBlockingQueue<QueueItem>()
    val nametagSenderHandler = NametagSenderHandler()
    private val queueLock = Any()
    private val startLock = Any()
    // bumped on every start(); a worker whose generation is no longer the current one
    // has been replaced and leaves its loop
    private val workerGeneration = AtomicInteger()
    // epoch millis of the worker's last trip around the queue loop
    @Volatile private var lastWorkerHeartbeat = 0L

    fun load(){
        hasLibsDisguisesInstalled = ExternalCompatibilityManager.hasLibsDisguisesInstalled
        this.nametagSender = nametagSenderHandler.getCurrentUtil()
        onLoadOrReload()
    }

    fun onLoadOrReload(){
        this.disableNametagJava = LevelledMobs.instance.helperSettings.getBoolean(
            "disable-nametag-java", false
        )
        this.disableNametagBedrock = LevelledMobs.instance.helperSettings.getBoolean(
            "disable-nametag-bedrock", false
        )
    }

    val hasNametagSupport: Boolean
        get() = this.nametagSender != null

    fun start() {
        // folia will run directly
        doThread = true
        if (LevelledMobs.instance.ver.isRunningFolia) return

        synchronized(startLock) {
            if (isRunning) return

            isRunning = true
            val generation = workerGeneration.incrementAndGet()
            lastWorkerHeartbeat = System.currentTimeMillis()

            val scheduler = SchedulerWrapper {
                var hadError = false
                try {
                    mainThread(generation)
                } catch (e: Exception) {
                    if (e !is InterruptedException){
                        hadError = true
                        e.printStackTrace()
                    }
                }
                if (hadError)
                    Log.sev("Nametag update queue Manager has exited with error")
                else
                    Log.inf("Nametag update queue Manager has exited")

                // only the current worker owns the flag, otherwise a worker that was
                // replaced would advertise its live successor as dead
                if (workerGeneration.get() == generation) isRunning = false
            }
            scheduler.run()
            this.queueTask = scheduler.bukkitTask
        }
    }

    fun stop() {
        doThread = false
    }

    fun taskChecker(){
        val qt = queueTask ?: return
        if (!doThread) return

        val stalledForMs = System.currentTimeMillis() - lastWorkerHeartbeat

        // a backlog only means the worker is busy, never that it died. The worker stamps
        // a heartbeat on every trip around its loop, so that is what decides
        if (isRunning && !qt.isCancelled && stalledForMs < WORKER_STALL_MS) return

        val queueSize = getNumberQueued()
        val status = if (qt.isCancelled) "cancelled"
        else if (!isRunning) "not running"
        else "stalled for ${stalledForMs}ms, queue size was $queueSize"

        Log.war("Restarting Nametag Queue Manager task, status was $status")
        // cancel() does not interrupt an async task that is already running; start()
        // bumps the generation, which is what actually retires the old worker
        qt.cancel()
        isRunning = false
        start()
    }

    fun addToQueue(item: QueueItem) {
        if (!doThread) return
        if (!item.lmEntity.shouldShowLMNametag) return
        if (Bukkit.getOnlinePlayers().isEmpty()) return
        if (item.lmEntity.nametagVisibilityEnum.contains(NametagVisibilityEnum.DISABLED))
            return

        item.lmEntity.inUseCount.getAndIncrement()

        if (LevelledMobs.instance.ver.isRunningFolia){
            // folia runs directly
            preProcessItem(item)
        }
        else{
            synchronized(queueLock){
                queue.offer(item)
            }
        }
    }

    fun getNumberQueued(): Int{
        val size: Int
        synchronized(queueLock){
            size = queue.size
        }

        return size
    }

    private fun mainThread(generation: Int) {
        while (doThread && workerGeneration.get() == generation) {
            lastWorkerHeartbeat = System.currentTimeMillis()
            val item: QueueItem?

            synchronized(queueLock){
                item = queue.poll()
            }
            if (item == null) {
                Thread.sleep(2L)
                continue
            }

            val scheduler = SchedulerWrapper(
                item.lmEntity.livingEntity
            ) {
                preProcessItem(item)
                item.lmEntity.free()
            }

            scheduler.runDirectlyInBukkit = true
            scheduler.entity = item.lmEntity.livingEntity
            item.lmEntity.inUseCount.getAndIncrement()
            scheduler.run()
        }

        if (workerGeneration.get() == generation) isRunning = false
    }

    private fun preProcessItem(item: QueueItem) {
        if (!item.lmEntity.isPopulated) {
            item.lmEntity.free()
            return
        }

        var lastEntityType: String? = null
        try {
            lastEntityType = item.lmEntity.nameIfBaby
            processItem(item)
        } catch (ex: Exception) {
            val entityName = lastEntityType ?: "Unknown Entity"

            Log.sev("Unable to process nametag update for '$entityName'. ")
            ex.printStackTrace()
        } finally {
            item.lmEntity.free()
        }
    }

    private fun processItem(item: QueueItem) {
        if (this.nametagSender == null) {
            // this would happen if the Minecraft version isn't supported directly by NMS
            return
        }

        val nametagTimerResetTime = item.lmEntity.getNametagCooldownTime()

        if (nametagTimerResetTime > 0L && !item.nametag!!.isNullOrEmpty) {
            synchronized(NametagTimerChecker.nametagTimer_Lock) {
                val nametagCooldownQueue: Map<Player, WeakHashMap<LivingEntity, Instant>> =
                    LevelledMobs.instance.nametagTimerChecker.nametagCooldownQueue
                if (item.lmEntity.playersNeedingNametagCooldownUpdate != null) {
                    // record which players should get the cooldown for this mob
                    // public Map<Player, WeakHashMap<LivingEntity, Instant>> nametagCooldownQueue;
                    for (player in item.lmEntity.playersNeedingNametagCooldownUpdate!!) {
                        if (!nametagCooldownQueue.containsKey(player))
                            continue

                        nametagCooldownQueue[player]?.set(item.lmEntity.livingEntity, Instant.now())
                        LevelledMobs.instance.nametagTimerChecker.cooldownTimes[item.lmEntity.livingEntity] =
                            item.lmEntity.getNametagCooldownTime()
                    }

                    // if any players already have a cooldown on this mob then don't remove the cooldown
                    for ((player, value) in nametagCooldownQueue) {
                        if (item.lmEntity.playersNeedingNametagCooldownUpdate!!.contains(player))
                            continue

                        if (value.containsKey(item.lmEntity.livingEntity))
                            item.lmEntity.playersNeedingNametagCooldownUpdate!!.add(player)
                    }
                } else {
                    // if there's any existing cooldowns we'll use them
                    for ((key, value) in nametagCooldownQueue) {
                        if (value.containsKey(item.lmEntity.livingEntity)) {
                            if (item.lmEntity.playersNeedingNametagCooldownUpdate == null)
                                item.lmEntity.playersNeedingNametagCooldownUpdate = HashSet()

                            item.lmEntity.playersNeedingNametagCooldownUpdate!!.add(key)
                        }
                    }
                }
            }
        }
        else if (item.lmEntity.playersNeedingNametagCooldownUpdate != null)
            item.lmEntity.playersNeedingNametagCooldownUpdate = null

        val main = LevelledMobs.instance
        synchronized(NametagTimerChecker.entityTarget_Lock) {
            if (main.nametagTimerChecker.entityTargetMap.containsKey(
                    item.lmEntity.livingEntity
                )
            ) {
                if (item.lmEntity.playersNeedingNametagCooldownUpdate == null)
                    item.lmEntity.playersNeedingNametagCooldownUpdate = mutableSetOf()

                item.lmEntity.playersNeedingNametagCooldownUpdate!!.add(
                    main.nametagTimerChecker.entityTargetMap[item.lmEntity.livingEntity]!!
                )
            }
        }

        if (!item.lmEntity.isPopulated)
            return

        if (main.helperSettings.getBoolean(
                "assert-entity-validity-with-nametag-packets"
            ) && !item.lmEntity.livingEntity.isValid
        ) {
            return
        }

        updateNametag(item.lmEntity, item.nametag!!, item.players!!)
    }

    @Suppress("DEPRECATION")
    private fun updateNametag(
        lmEntity: LivingEntityWrapper,
        nametag: NametagResult,
        players: MutableList<Player>
    ) {
        val loopCount = if (lmEntity.playersNeedingNametagCooldownUpdate == null) 1 else 2

        for (i in 0 until loopCount) {
            // will loop again to update with nametag cooldown for only the specified players

            val nametagVisibilityEnum = lmEntity.nametagVisibilityEnum
            val doAlwaysVisible = i == 1 || !nametag.isNullOrEmpty && lmEntity.livingEntity.isCustomNameVisible ||
                    nametagVisibilityEnum.contains(NametagVisibilityEnum.ALWAYS_ON)

            if (i == 0) {
                // these players are not getting always on nametags unless always on has been configured for the mob
                for (player in players) {
                    if (lmEntity.playersNeedingNametagCooldownUpdate != null
                        && lmEntity.playersNeedingNametagCooldownUpdate!!.contains(player)
                    ) {
                        continue
                    }
                    
                    /** Disable if Java or Bedrock */
                    if (disableNametagBedrock && isBedrock(player)) return
                    if (disableNametagJava && !isBedrock(player)) return

                    nametagSender!!.sendNametag(
                        lmEntity.livingEntity, nametag, player,
                        doAlwaysVisible
                    )
                }
            } else {
                // these players are getting always on nametags
                for (player in lmEntity.playersNeedingNametagCooldownUpdate!!) {

                    /** Disable if Java or Bedrock */
                    if (disableNametagBedrock && isBedrock(player)) return
                    if (disableNametagJava && !isBedrock(player)) return


                    nametagSender!!.sendNametag(lmEntity.livingEntity, nametag, player, true)
                }
            }

            if (hasLibsDisguisesInstalled && LibsDisguisesUtils.isMobUsingLibsDisguises(lmEntity)) {
                var useNametag: String? = null
                if (nametag.nametag != null){
                    useNametag = MessageUtils.colorizeAll(nametag.nametagNonNull
                        .replace("{DisplayName}", Utils.capitalize(lmEntity.typeName.replace("_", " ")))
                        .replace("{CustomName}", lmEntity.livingEntity.customName ?: ""))
                }

                LibsDisguisesUtils.updateLibsDisguiseNametag(lmEntity, useNametag)
            }
        }
    }

    private fun isBedrock(player: Player) : Boolean {
        return player.uniqueId.mostSignificantBits == 0L
    }

    companion object {
        // taskChecker runs every 5s, so a worker that has not polled the queue in 30s is
        // genuinely stuck, not merely busy
        private const val WORKER_STALL_MS = 30000L
    }
}