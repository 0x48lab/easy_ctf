package com.hacklab.ctf.listeners

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Openable
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageAbortEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * チーム専用ドア
 *
 * ゲーム中にプレイヤーが設置した鉄のドア／鉄のトラップドアは、
 * 設置チームだけが右クリックで開閉できる。レッドストーンでは動かない。
 * 「壊せなくて詰む」ことを防ぐため、誰でも殴り続ければ一定秒数で破壊できる
 * （素手の鉄ドアは本来 25 秒かかる）。
 *
 * 設置チームの記録は Game.placedNonTeamBlocks（BlockPlaceEvent で登録済み）を流用する。
 * マップ由来のドアは記録がないためバニラ挙動のまま。
 */
class TeamDoorListener(private val plugin: Main) : Listener {

    private val gameManager = plugin.gameManager
    private val breakTasks = mutableMapOf<UUID, BukkitTask>()
    private val breakTargets = mutableMapOf<UUID, Location>()

    companion object {
        val DOOR_MATERIALS = setOf(Material.IRON_DOOR, Material.IRON_TRAPDOOR)
    }

    private fun enabled() = plugin.config.getBoolean("team-door.enabled", true)

    /** ドアは下半分、トラップドアはそのままを基準ブロックとする */
    private fun baseBlock(block: Block): Block {
        if (block.type == Material.IRON_DOOR) {
            val data = block.blockData as? Bisected
            if (data?.half == Bisected.Half.TOP) return block.getRelative(BlockFace.DOWN)
        }
        return block
    }

    private fun ownerOf(game: Game, block: Block): Team? =
        game.placedNonTeamBlocks[baseBlock(block).location]

    private fun findGameByDoor(block: Block): Game? {
        val key = baseBlock(block).location
        return gameManager.getAllGames().values.firstOrNull {
            it.state == GameState.RUNNING && it.placedNonTeamBlocks.containsKey(key)
        }
    }

    private fun setOpen(base: Block, open: Boolean) {
        val data = base.blockData as? Openable ?: return
        data.isOpen = open
        base.blockData = data
        if (base.type == Material.IRON_DOOR) {
            val top = base.getRelative(BlockFace.UP)
            val topData = top.blockData as? Openable
            if (top.type == Material.IRON_DOOR && topData != null) {
                topData.isOpen = open
                top.blockData = topData
            }
        }
    }

    // ===== 開閉 =====

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.type !in DOOR_MATERIALS) return
        if (!enabled()) return

        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        if (game.state != GameState.RUNNING) return
        val owner = ownerOf(game, block) ?: return  // マップ由来のドアはバニラ挙動
        val team = game.getPlayerTeam(player.uniqueId) ?: return

        event.isCancelled = true

        if (team != owner) {
            player.sendMessage(Component.text(
                plugin.languageManager.getMessage("gameplay.team-door-locked"), NamedTextColor.RED))
            player.playSound(block.location, Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f)
            return
        }

        val base = baseBlock(block)
        val nowOpen = !((base.blockData as? Openable)?.isOpen ?: false)
        setOpen(base, nowOpen)
        val sound = if (base.type == Material.IRON_DOOR) {
            if (nowOpen) Sound.BLOCK_IRON_DOOR_OPEN else Sound.BLOCK_IRON_DOOR_CLOSE
        } else {
            if (nowOpen) Sound.BLOCK_IRON_TRAPDOOR_OPEN else Sound.BLOCK_IRON_TRAPDOOR_CLOSE
        }
        base.world.playSound(base.location, sound, 1.0f, 1.0f)
    }

    /** レッドストーンでは動かさない（敵がボタンを貼り付けて開ける手を塞ぐ） */
    @EventHandler(priority = EventPriority.HIGH)
    fun onRedstone(event: BlockRedstoneEvent) {
        if (event.block.type !in DOOR_MATERIALS) return
        if (!enabled()) return
        if (findGameByDoor(event.block) == null) return
        event.newCurrent = event.oldCurrent
    }

    // ===== 時間制破壊 =====

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockDamage(event: BlockDamageEvent) {
        if (event.instaBreak) return  // 自陣コアの即時撤去はそちらに任せる
        val block = event.block
        if (block.type !in DOOR_MATERIALS) return
        if (!enabled()) return

        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        if (game.state != GameState.RUNNING) return
        if (game.phase == GamePhase.BUILD && GameMode.valueOf(game.buildPhaseGameMode) == GameMode.ADVENTURE) return
        val team = game.getPlayerTeam(player.uniqueId) ?: return
        if (team == Team.SPECTATOR) return
        ownerOf(game, block) ?: return

        val seconds = plugin.config.getDouble("team-door.break-seconds", 3.0)
        startTimedBreak(player, baseBlock(block), seconds)
    }

    @EventHandler
    fun onBlockDamageAbort(event: BlockDamageAbortEvent) {
        cancelBreak(event.player, resetCrack = true)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cancelBreak(event.player, resetCrack = false)
    }

    /** 破壊されたら設置記録を消す（マップ由来ドアとの誤判定を防ぐ） */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.block.type !in DOOR_MATERIALS) return
        val game = gameManager.getPlayerGame(event.player) ?: return
        game.placedNonTeamBlocks.remove(baseBlock(event.block).location)
    }

    private fun startTimedBreak(player: Player, base: Block, seconds: Double) {
        cancelBreak(player, resetCrack = false)
        val loc = base.location
        val type = base.type
        val totalTicks = (seconds * 20).toInt().coerceAtLeast(1)
        var ticks = 0

        val task = object : BukkitRunnable() {
            override fun run() {
                val stillValid = player.isOnline && !player.isDead &&
                    base.type == type &&
                    player.world == base.world &&
                    player.location.distanceSquared(loc) <= 36.0
                if (!stillValid) {
                    cancelBreak(player, resetCrack = true)
                    return
                }
                ticks++
                player.sendBlockDamage(loc, (ticks.toFloat() / totalTicks).coerceAtMost(1.0f))
                if (ticks >= totalTicks) {
                    breakTasks.remove(player.uniqueId)
                    breakTargets.remove(player.uniqueId)
                    player.sendBlockDamage(loc, 0.0f)
                    // BlockBreakEvent を経由させ、既存の破壊ルール（保護ブロック等）を通す
                    player.breakBlock(base)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)

        breakTasks[player.uniqueId] = task
        breakTargets[player.uniqueId] = loc
    }

    private fun cancelBreak(player: Player, resetCrack: Boolean) {
        breakTasks.remove(player.uniqueId)?.cancel()
        val loc = breakTargets.remove(player.uniqueId) ?: return
        if (resetCrack && player.isOnline) {
            player.sendBlockDamage(loc, 0.0f)
        }
    }
}
