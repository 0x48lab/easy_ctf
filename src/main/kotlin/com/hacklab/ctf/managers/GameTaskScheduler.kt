package com.hacklab.ctf.managers

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BukkitRunnable タスクの一元管理。
 * 名前付きタスク・プレイヤー別タスク・リスト形式タスクをすべてここで登録・キャンセルする。
 */
class GameTaskScheduler(private val plugin: JavaPlugin) {

    // 名前付きタスク（gameLoop, suffocation, actionBar, autoStart 等）
    private val namedTasks = ConcurrentHashMap<String, BukkitRunnable>()

    // プレイヤー別タスク（spawnProtection, respawn 等）
    private val playerTasks = ConcurrentHashMap<String, ConcurrentHashMap<UUID, BukkitRunnable>>()

    // リスト形式タスク（eventChest 等）
    private val listTasks = ConcurrentHashMap<String, MutableList<BukkitRunnable>>()

    // --- 名前付きタスク ---

    /**
     * 名前付き繰り返しタスクを登録。既存の同名タスクは自動キャンセル。
     */
    fun scheduleRepeating(id: String, delay: Long, period: Long, task: BukkitRunnable): BukkitRunnable {
        cancelNamed(id)
        task.runTaskTimer(plugin, delay, period)
        namedTasks[id] = task
        return task
    }

    /**
     * 名前付き遅延タスクを登録。既存の同名タスクは自動キャンセル。
     */
    fun scheduleDelayed(id: String, delay: Long, task: BukkitRunnable): BukkitRunnable {
        cancelNamed(id)
        task.runTaskLater(plugin, delay)
        namedTasks[id] = task
        return task
    }

    /**
     * 名前付きタスクをキャンセル。
     */
    fun cancelNamed(id: String) {
        namedTasks.remove(id)?.let { runnable ->
            try {
                runnable.cancel()
            } catch (_: IllegalStateException) {
                // タスクが既にキャンセル済みまたは未スケジュール
            }
        }
    }

    /**
     * 名前付きタスクを取得。
     */
    fun getNamed(id: String): BukkitRunnable? = namedTasks[id]

    // --- プレイヤー別タスク ---

    /**
     * プレイヤー別タスクを登録。同一カテゴリ・同一プレイヤーの既存タスクは自動キャンセル。
     */
    fun schedulePlayerTask(category: String, playerId: UUID, delay: Long, task: BukkitRunnable): BukkitRunnable {
        cancelPlayerTask(category, playerId)
        task.runTaskLater(plugin, delay)
        playerTasks.getOrPut(category) { ConcurrentHashMap() }[playerId] = task
        return task
    }

    /**
     * プレイヤー別繰り返しタスクを登録。
     */
    fun schedulePlayerRepeating(category: String, playerId: UUID, delay: Long, period: Long, task: BukkitRunnable): BukkitRunnable {
        cancelPlayerTask(category, playerId)
        task.runTaskTimer(plugin, delay, period)
        playerTasks.getOrPut(category) { ConcurrentHashMap() }[playerId] = task
        return task
    }

    /**
     * プレイヤー別タスクをキャンセル。
     */
    fun cancelPlayerTask(category: String, playerId: UUID) {
        playerTasks[category]?.remove(playerId)?.let { runnable ->
            try {
                runnable.cancel()
            } catch (_: IllegalStateException) {
                // タスクが既にキャンセル済みまたは未スケジュール
            }
        }
    }

    /**
     * カテゴリ内のプレイヤー別タスクを全キャンセル。
     */
    fun cancelAllPlayerTasks(category: String) {
        playerTasks.remove(category)?.forEach { (_, runnable) ->
            try {
                runnable.cancel()
            } catch (_: IllegalStateException) {
                // タスクが既にキャンセル済みまたは未スケジュール
            }
        }
    }

    /**
     * プレイヤー別タスクのマップを取得（読み取り用）。
     */
    fun getPlayerTasks(category: String): Map<UUID, BukkitRunnable> =
        playerTasks[category] ?: emptyMap()

    /**
     * 特定プレイヤーのタスクを削除（キャンセルなし）。タスク内で自身を除去する場合用。
     */
    fun removePlayerTask(category: String, playerId: UUID) {
        playerTasks[category]?.remove(playerId)
    }

    // --- リスト形式タスク ---

    /**
     * リスト形式タスクに追加。
     */
    fun addListTask(category: String, delay: Long, task: BukkitRunnable): BukkitRunnable {
        task.runTaskLater(plugin, delay)
        listTasks.getOrPut(category) { mutableListOf() }.add(task)
        return task
    }

    /**
     * リスト形式タスクを全キャンセル。
     */
    fun cancelAllListTasks(category: String) {
        listTasks.remove(category)?.forEach { runnable ->
            try {
                runnable.cancel()
            } catch (_: IllegalStateException) {
                // タスクが既にキャンセル済みまたは未スケジュール
            }
        }
    }

    // --- 一括操作 ---

    /**
     * 全タスクをキャンセル。ゲーム停止時に呼び出す。
     */
    fun cancelAll() {
        // 名前付きタスク
        namedTasks.values.forEach { runnable ->
            try {
                runnable.cancel()
            } catch (_: IllegalStateException) {}
        }
        namedTasks.clear()

        // プレイヤー別タスク
        playerTasks.values.forEach { map ->
            map.values.forEach { runnable ->
                try {
                    runnable.cancel()
                } catch (_: IllegalStateException) {}
            }
        }
        playerTasks.clear()

        // リスト形式タスク
        listTasks.values.forEach { list ->
            list.forEach { runnable ->
                try {
                    runnable.cancel()
                } catch (_: IllegalStateException) {}
            }
        }
        listTasks.clear()
    }

    companion object {
        // 名前付きタスクID定数
        const val GAME_LOOP = "gameLoop"
        const val SUFFOCATION = "suffocation"
        const val SCOREBOARD_UPDATE = "scoreboardUpdate"
        const val ACTION_BAR = "actionBar"
        const val AUTO_START = "autoStart"

        // プレイヤー別タスクカテゴリ定数
        const val SPAWN_PROTECTION = "spawnProtection"
        const val RESPAWN = "respawn"

        // リスト形式タスクカテゴリ定数
        const val EVENT_CHEST = "eventChest"
    }
}
