package com.hacklab.ctf.combat

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameTaskScheduler
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.scheduler.BukkitRunnable
import java.util.*

/**
 * ダメージ・回復システムの管理。
 * 敵陣でのシールド減少・ダメージと、自陣での空腹度・シールド回復を行う。
 */
class DamageHealingSystem(
    private val game: Game,
    private val plugin: Main
) {

    /**
     * 敵陣シールドシステムタスクを開始
     */
    fun startSuffocationTask() {
        // プレイヤーごとのシールド値を管理（最大値: 100）
        val playerShield = mutableMapOf<UUID, Float>()
        val lastDamageTime = mutableMapOf<UUID, Long>()
        val lastFoodRecoveryTime = mutableMapOf<UUID, Long>() // 空腹度回復のタイマー

        val task = object : BukkitRunnable() {
            override fun run() {
                // ゲームまたはフェーズが終了したらタスクを停止
                if (game.state != GameState.RUNNING || game.phase != GamePhase.COMBAT) {
                    cancel()
                    game.taskScheduler.cancelNamed(GameTaskScheduler.SUFFOCATION)
                    return
                }

                try {
                    // プレイヤーリストをコピーして処理
                    val players = game.getAllPlayers().toList()

                    for (player in players) {
                        // プレイヤーが有効でない場合はスキップ
                        if (!player.isOnline || player.isDead) continue

                        // シールドシステムが無効の場合はスキップ
                        if (!plugin.config.getBoolean("shield.enabled", true)) continue

                        val playerTeam = game.getPlayerTeam(player.uniqueId)
                        if (playerTeam == null) continue

                        val blockBelow = player.location.clone().subtract(0.0, 0.5, 0.0).block
                        val playerLoc = player.location

                        // プレイヤーの下のブロックが敵チームの色ブロックかチェック
                        val isOnEnemyBlock = when (playerTeam) {
                            Team.RED -> blockBelow.type == Material.BLUE_CONCRETE
                            Team.BLUE -> blockBelow.type == Material.RED_CONCRETE
                            Team.SPECTATOR -> false // 観戦者は敵ブロックでダメージを受けない
                            else -> blockBelow.type != playerTeam.getConcreteMaterial() && Team.playableTeams().any { blockBelow.type == it.getConcreteMaterial() }
                        }

                        // ビーコン周囲9マス（3x3）のダメージエリアチェック
                        val isInEnemyBeaconArea = when (playerTeam) {
                            Team.RED -> {
                                // 青チームのビーコン周囲かチェック
                                game.blueFlagLocation?.let { beaconLoc ->
                                    if (playerLoc.world == beaconLoc.world) {
                                        val dx = kotlin.math.abs(playerLoc.blockX - beaconLoc.blockX)
                                        val dz = kotlin.math.abs(playerLoc.blockZ - beaconLoc.blockZ)
                                        val dy = kotlin.math.abs(playerLoc.blockY - (beaconLoc.blockY - 1))
                                        // ビーコンの鉄ブロックベース（Y-1）の3x3範囲内かチェック
                                        dx <= 1 && dz <= 1 && dy <= 2
                                    } else false
                                } ?: false
                            }
                            Team.BLUE -> {
                                // 赤チームのビーコン周囲かチェック
                                game.redFlagLocation?.let { beaconLoc ->
                                    if (playerLoc.world == beaconLoc.world) {
                                        val dx = kotlin.math.abs(playerLoc.blockX - beaconLoc.blockX)
                                        val dz = kotlin.math.abs(playerLoc.blockZ - beaconLoc.blockZ)
                                        val dy = kotlin.math.abs(playerLoc.blockY - (beaconLoc.blockY - 1))
                                        // ビーコンの鉄ブロックベース（Y-1）の3x3範囲内かチェック
                                        dx <= 1 && dz <= 1 && dy <= 2
                                    } else false
                                } ?: false
                            }
                            Team.SPECTATOR -> false
                            else -> {
                                // 敵チームのビーコン周囲かチェック（自チーム以外の全チーム）
                                game.flagLocations.entries
                                    .filter { it.key != playerTeam && it.key != Team.SPECTATOR }
                                    .any { (_, beaconLoc) ->
                                        if (playerLoc.world == beaconLoc.world) {
                                            val dx = kotlin.math.abs(playerLoc.blockX - beaconLoc.blockX)
                                            val dz = kotlin.math.abs(playerLoc.blockZ - beaconLoc.blockZ)
                                            val dy = kotlin.math.abs(playerLoc.blockY - (beaconLoc.blockY - 1))
                                            dx <= 1 && dz <= 1 && dy <= 2
                                        } else false
                                    }
                            }
                        }

                        // 敵陣ブロック上またはビーコン周囲にいる場合
                        val isInDamageArea = isOnEnemyBlock || isInEnemyBeaconArea

                        // 現在のシールド値を取得（初期値100）
                        val maxShield = plugin.config.getInt("shield.max-shield", 100).toFloat()
                        var shield = playerShield.getOrDefault(player.uniqueId, maxShield)

                        if (isInDamageArea) {
                            // 初めて敵陣エリアに入った時だけ警告
                            if (!player.hasMetadata("on_enemy_block")) {
                                player.setMetadata("on_enemy_block", org.bukkit.metadata.FixedMetadataValue(plugin, true))
                                val warningMessage = if (isInEnemyBeaconArea && !isOnEnemyBlock) {
                                    plugin.languageManager.getMessageAsComponent("shield.beacon-area")
                                } else {
                                    plugin.languageManager.getMessageAsComponent("shield.decreasing")
                                }
                                player.sendMessage(warningMessage)
                            }

                            // シールドを減らす（設定値に基づく）
                            val decreaseRate = plugin.config.getDouble("shield.decrease-rate", 2.0).toFloat()
                            shield = (shield - decreaseRate).coerceAtLeast(0f)

                            // シールドが少なくなったら警告
                            val warningThreshold = plugin.config.getInt("shield.warning-threshold", 40).toFloat()
                            val criticalThreshold = plugin.config.getInt("shield.critical-threshold", 20).toFloat()

                            if (shield == warningThreshold) {
                                player.sendMessage(plugin.languageManager.getMessageAsComponent("shield.weakening"))
                                player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f)
                            } else if (shield == criticalThreshold) {
                                player.sendMessage(plugin.languageManager.getMessageAsComponent("shield.critical"))
                                player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.3f)
                            }

                            // シールドがゼロになったらダメージ
                            if (shield == 0f) {
                                // 満腹度を少しずつ減らして自動回復を防ぐ
                                if (player.foodLevel > 17) {
                                    player.foodLevel = player.foodLevel - 1
                                }

                                val currentTime = System.currentTimeMillis()
                                val lastDamage = lastDamageTime[player.uniqueId] ?: 0
                                val damageInterval = plugin.config.getLong("shield.damage-interval", 1000)
                                val damageAmount = plugin.config.getDouble("shield.damage-amount", 1.5)

                                // 設定された間隔でダメージ
                                if (currentTime - lastDamage >= damageInterval) {
                                    if (player.health > 1.0) {
                                        // ダメージを与える
                                        val event = EntityDamageEvent(player, EntityDamageEvent.DamageCause.CUSTOM, damageAmount)
                                        Bukkit.getPluginManager().callEvent(event)
                                        if (!event.isCancelled) {
                                            player.health = (player.health - event.finalDamage).coerceAtLeast(1.0)
                                            player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_HURT, 0.5f, 1.0f)
                                        }
                                        lastDamageTime[player.uniqueId] = currentTime
                                    }
                                }
                            }
                        } else {
                            // 敵陣ブロック上でない場合
                            if (player.hasMetadata("on_enemy_block")) {
                                player.removeMetadata("on_enemy_block", plugin)
                                lastDamageTime.remove(player.uniqueId)
                            }

                            // 自陣ブロックの上にいる場合、空腹度を回復
                            val isOnOwnBlock = when (playerTeam) {
                                Team.RED -> blockBelow.type == Material.RED_CONCRETE
                                Team.BLUE -> blockBelow.type == Material.BLUE_CONCRETE
                                Team.SPECTATOR -> false
                                else -> blockBelow.type == playerTeam.getConcreteMaterial()
                            }

                            // 自陣ビーコン周囲のチェック
                            val isInOwnBeaconArea = when (playerTeam) {
                                Team.RED -> {
                                    game.redFlagLocation?.let { beaconLoc ->
                                        if (playerLoc.world == beaconLoc.world) {
                                            val dx = kotlin.math.abs(playerLoc.blockX - beaconLoc.blockX)
                                            val dz = kotlin.math.abs(playerLoc.blockZ - beaconLoc.blockZ)
                                            val dy = kotlin.math.abs(playerLoc.blockY - (beaconLoc.blockY - 1))
                                            dx <= 1 && dz <= 1 && dy <= 2
                                        } else false
                                    } ?: false
                                }
                                Team.BLUE -> {
                                    game.blueFlagLocation?.let { beaconLoc ->
                                        if (playerLoc.world == beaconLoc.world) {
                                            val dx = kotlin.math.abs(playerLoc.blockX - beaconLoc.blockX)
                                            val dz = kotlin.math.abs(playerLoc.blockZ - beaconLoc.blockZ)
                                            val dy = kotlin.math.abs(playerLoc.blockY - (beaconLoc.blockY - 1))
                                            dx <= 1 && dz <= 1 && dy <= 2
                                        } else false
                                    } ?: false
                                }
                                Team.SPECTATOR -> false
                                else -> {
                                    game.flagLocations[playerTeam]?.let { beaconLoc ->
                                        if (playerLoc.world == beaconLoc.world) {
                                            val dx = kotlin.math.abs(playerLoc.blockX - beaconLoc.blockX)
                                            val dz = kotlin.math.abs(playerLoc.blockZ - beaconLoc.blockZ)
                                            val dy = kotlin.math.abs(playerLoc.blockY - (beaconLoc.blockY - 1))
                                            dx <= 1 && dz <= 1 && dy <= 2
                                        } else false
                                    } ?: false
                                }
                            }

                            // 自陣ブロックまたは自陣ビーコン周囲にいる場合のみ処理
                            if (isOnOwnBlock || isInOwnBeaconArea) {
                                // シールドを回復（設定値に基づく）
                                val maxShield = plugin.config.getInt("shield.max-shield", 100).toFloat()
                                val recoveryRate = plugin.config.getDouble("shield.recovery-rate", 5.0).toFloat()

                                if (shield < maxShield) {
                                    shield = (shield + recoveryRate).coerceAtMost(maxShield)

                                    // 全回復したら通知
                                    if (shield == maxShield) {
                                        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f)
                                    }
                                }
                                // 空腹度とサチュレーションを回復
                                if (player.foodLevel < 20) {
                                    // 1秒に1ポイントの速度で回復（20ticksごと）
                                    val currentTime = System.currentTimeMillis()
                                    val lastFoodRecovery = lastFoodRecoveryTime.getOrDefault(player.uniqueId, 0L)

                                    if (currentTime - lastFoodRecovery >= 1000) { // 1秒ごと
                                        player.foodLevel = (player.foodLevel + 1).coerceAtMost(20)
                                        player.saturation = (player.saturation + 0.5f).coerceAtMost(20f)
                                        lastFoodRecoveryTime[player.uniqueId] = currentTime

                                        // 回復エフェクト（小さな音）
                                        if (player.foodLevel % 5 == 0) { // 5の倍数で音を鳴らす
                                            player.playSound(player.location, org.bukkit.Sound.ENTITY_GENERIC_EAT, 0.3f, 1.5f)
                                        }
                                    }
                                }
                            } else {
                                // 自陣ブロック上にいない場合は回復タイマーをリセット
                                lastFoodRecoveryTime.remove(player.uniqueId)
                            }
                        }

                        // シールド値を保存
                        playerShield[player.uniqueId] = shield

                        // アクションバーにシールド状態を表示
                        val maxShieldDisplay = plugin.config.getInt("shield.max-shield", 100).toFloat()
                        if (shield < maxShieldDisplay) {
                            val shieldBar = "█".repeat((shield * 10 / maxShieldDisplay).toInt()).padEnd(10, '░')
                            player.sendActionBar(plugin.languageManager.getMessageAsComponent("shield.status", "bar" to shieldBar, "percent" to shield.toInt().toString()))
                        }
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Error in oxygen task: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        // 10ティック（0.5秒）ごとに実行
        game.taskScheduler.scheduleRepeating(GameTaskScheduler.SUFFOCATION, 0L, 10L, task)
    }
}
