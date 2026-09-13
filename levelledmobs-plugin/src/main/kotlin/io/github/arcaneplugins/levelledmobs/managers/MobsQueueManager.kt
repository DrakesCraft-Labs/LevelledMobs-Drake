package io.github.arcaneplugins.levelledmobs.managers

import java.util.concurrent.LinkedBlockingQueue
import io.github.arcaneplugins.levelledmobs.LevelledMobs
import io.github.arcaneplugins.levelledmobs.debug.DebugManager
import io.github.arcaneplugins.levelledmobs.debug.DebugType
import io.github.arcaneplugins.levelledmobs.misc.EvaluationException
import io.github.arcaneplugins.levelledmobs.misc.QueueItem
import io.github.arcaneplugins.levelledmobs.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask

/**
 * Queues up mob info so they can be processed in a background thread
 *
 * @author stumper66
 * @since 3.0.0
 */
class MobsQueueManager {
    @Volatile private var isRunning = false
    @Volatile private var doThread = false
    private val queue = LinkedBlockingQueue<QueueItem>()
    private val processingList = mutableListOf<UUID>()
    private val maxThreads = 3
    var ignoreMobsWithNoPlayerContext = false
    var queueTasks: MutableMap<Int, BukkitTask> = ConcurrentHashMap()
    private val threadsCount = AtomicInteger()
    private val queueLock = Any()
    private val nextWorkerId = AtomicInteger()
    // bukkit task id -> worker id, so taskChecker can find the worker behind a task
    private val workerIds = ConcurrentHashMap<Int, Int>()
    // worker id -> epoch millis of its last trip around the queue loop
    private val workerHeartbeats = ConcurrentHashMap<Int, Long>()
    // workers that taskChecker replaced; their loop exits on the next iteration
    private val retiredWorkers: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    fun start() {
        // folia will run directly
        if (LevelledMobs.instance.ver.isRunningFolia) return

        if (isRunning) {
            return
        }
        doThread = true
        isRunning = true
        queueTasks.clear()
        workerIds.clear()
        workerHeartbeats.clear()
        retiredWorkers.clear()
        threadsCount.set(0)

        if (!LevelledMobs.instance.ver.isRunningFolia) {
            repeat(
                maxThreads,
                action = { startAThread() }
            )
        }
    }

    private fun startAThread(){
        val workerId = nextWorkerId.incrementAndGet()
        // published before scheduling so a task that never gets to run still looks stale
        workerHeartbeats[workerId] = System.currentTimeMillis()

        val bgThread = Runnable {
            try {
                mainThread(workerId)
            } catch (_: InterruptedException) {
                // the scheduler is tearing the task down
            } catch (e: Exception) {
                Log.sev("Mob processing queue worker exited with error")
                e.printStackTrace()
            } finally {
                // single owner of the thread accounting: every exit path passes here
                // exactly once, including the ones that throw
                workerHeartbeats.remove(workerId)
                retiredWorkers.remove(workerId)
                doneWithThread()
            }
        }

        threadsCount.getAndIncrement()
        val task = Bukkit.getScheduler().runTaskAsynchronously(LevelledMobs.instance, bgThread)
        queueTasks[task.taskId] = task
        workerIds[task.taskId] = workerId
    }

    fun getNumberQueued(): Int{
        val size: Int
        synchronized(queueLock){
            size = queue.size
        }

        return size
    }

    fun clearQueue(){
        synchronized(queueLock){
            queue.clear()
        }
    }

    private fun doneWithThread(){
        if (threadsCount.decrementAndGet() > 0) return

        threadsCount.set(0)
        isRunning = false
        Log.inf("Mob processing queue Manager has exited")
    }

    fun stop() {
        doThread = false
    }

    fun taskChecker(){
        if (!doThread) return

        val now = System.currentTimeMillis()
        var threadsNeeded = 0
        val enumerator = queueTasks.iterator()

        while (enumerator.hasNext()){
            val taskEntry = enumerator.next()
            val taskId = taskEntry.key
            val task = taskEntry.value
            val workerId = workerIds[taskId]
            val lastHeartbeat = if (workerId == null) null else workerHeartbeats[workerId]
            val stalledForMs = if (lastHeartbeat == null) Long.MAX_VALUE else now - lastHeartbeat

            // a backlog only means the workers are busy, never that they died. The worker
            // stamps a heartbeat on every trip around its loop, so that is what decides
            if (!task.isCancelled && stalledForMs < WORKER_STALL_MS) continue

            val status = if (task.isCancelled) "cancelled"
            else if (lastHeartbeat == null) "not running"
            else "stalled for ${stalledForMs}ms, queue size was ${getNumberQueued()}"

            Log.war("Restarting mob processing queue task, status was $status")

            // cancel() does not interrupt an async task that is already running and
            // doThread stays true, so the old worker has to be retired explicitly or it
            // keeps draining the same queue next to its replacement
            if (workerId != null){
                retiredWorkers.add(workerId)
                workerHeartbeats.remove(workerId)
                workerIds.remove(taskId)
            }
            task.cancel()
            enumerator.remove()
            threadsNeeded++
        }

        if (threadsNeeded == 0) return

        // a restart may never push the pool past maxThreads
        val canStart = threadsNeeded.coerceAtMost(maxThreads - queueTasks.size)
        if (canStart < 1) return

        repeat(
            canStart,
            action = { startAThread() }
        )
    }

    fun addToQueue(item: QueueItem) {
        item.lmEntity.inUseCount.getAndIncrement()

        if (LevelledMobs.instance.ver.isRunningFolia) {
            processItem(item)
            item.lmEntity.free()
        }
        else {
            var offeredItem = false
            synchronized(queueLock){
                if (!processingList.contains(item.entityId)) {
                    queue.offer(item)
                    offeredItem = true
                }
            }

            if (!offeredItem) item.lmEntity.free()
        }
    }

    private fun mainThread(workerId: Int) {
        while (doThread && !retiredWorkers.contains(workerId)) {
            workerHeartbeats[workerId] = System.currentTimeMillis()
            val item: QueueItem?
            synchronized(queueLock){
                item = queue.poll()
                if (item != null)
                    processingList.add(item.entityId)
            }
            if (item == null) {
                Thread.sleep(2L)
                continue
            }

            try {
                processItem(item)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                synchronized(queueLock){
                    processingList.remove(item.entityId)
                }
                item.lmEntity.free()
            }
        }

        // the caller's finally block owns doneWithThread(); calling it here too
        // decremented the counter twice on every clean exit
    }

    private fun processItem(item: QueueItem) {
        if (!item.lmEntity.isPopulated) return

        if (ignoreMobsWithNoPlayerContext && item.lmEntity.associatedPlayer == null){
            DebugManager.log(DebugType.PLAYER_CONTEXT, item.lmEntity){
                val locationStr = "${item.lmEntity.location.blockX}, " +
                    "${item.lmEntity.location.blockY}, " +
                    "${item.lmEntity.location.blockZ} " +
                    "in ${item.lmEntity.location.world.name}"
                "ignoring mob ${item.lmEntity.nameIfBaby} due to no player context at $locationStr"
            }
            return
        }

        try{
            LevelledMobs.instance.levelManager.entitySpawnListener.processMob(item.lmEntity, item.event)
        }
        catch (_: EvaluationException){
            // this exception is manually thrown after logging and notifying op users
        }
        catch (_: TimeoutException){
            DebugManager.log(DebugType.APPLY_LEVEL_RESULT, item.lmEntity, false){
                "Timed out applying level to mob"
            }
        }
    }

    companion object {
        // taskChecker runs every 5s, so a worker that has not polled the queue in 30s is
        // genuinely stuck, not merely busy
        private const val WORKER_STALL_MS = 30000L
    }
}