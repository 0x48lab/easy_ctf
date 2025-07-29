package com.hacklab.ctf.listeners

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManager
import com.hacklab.ctf.shop.ShopManager
import com.hacklab.ctf.shop.ShopItem
import com.hacklab.ctf.shop.DeathBehavior
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.*
import org.bukkit.event.block.Action
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.entity.Item

class GameListenerNew(private val plugin: Main) : Listener {
    
    private val gameManager = plugin.gameManager as GameManager
    private val shopManager = plugin.shopManager

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        // 少し遅延させてからreconnect処理を実行
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            gameManager.handlePlayerReconnect(event.player)
        }, 20L) // 1秒後
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        gameManager.handlePlayerDisconnect(event.player)
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val player = event.player
        
        val item = event.itemDrop.itemStack
        
        // ショップアイテムのドロップを防止（ゲーム参加有無に関わらず）
        if (shopManager.isShopItem(item)) {
            event.isCancelled = true
            player.sendMessage(Component.text("ショップアイテムは捨てることができません！", NamedTextColor.RED))
            return
        }
        
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            if (isArmor(item.type)) {
                event.isCancelled = true
                player.sendMessage(Component.text("戦闘中は防具を捨てられません！", NamedTextColor.RED))
            }
        }
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state != GameState.RUNNING) return
        
        val playerTeam = game.getPlayerTeam(player.uniqueId) ?: return
        val playerLoc = player.location
        
        // 戦闘フェーズ中の旗取得チェック
        if (game.phase == GamePhase.COMBAT) {
            // 敵の旗の位置をチェック
            val enemyTeam = if (playerTeam == Team.RED) Team.BLUE else Team.RED
            val enemyFlagLocation = when (enemyTeam) {
                Team.RED -> game.getRedFlagLocation()
                Team.BLUE -> game.getBlueFlagLocation()
            } ?: return
            
            // 敵の旗に近づいたら取得（1.5ブロック以内）
            if (playerLoc.distance(enemyFlagLocation) < 1.5) {
                // 旗がまだ誰も持っていない場合
                val enemyCarrier = when (enemyTeam) {
                    Team.RED -> game.redFlagCarrier
                    Team.BLUE -> game.blueFlagCarrier
                }
                
                if (enemyCarrier == null) {
                    // 旗を取得
                    when (enemyTeam) {
                        Team.RED -> game.redFlagCarrier = player.uniqueId
                        Team.BLUE -> game.blueFlagCarrier = player.uniqueId
                    }
                
                    // ビーコンと色付きガラスを削除
                    enemyFlagLocation.block.type = Material.AIR
                    enemyFlagLocation.clone().add(0.0, 1.0, 0.0).block.type = Material.AIR
                    
                    // プレイヤーに発光効果
                    player.isGlowing = true
                    
                    // キャプチャーアシストリストに追加（旗を取った人をリストに追加）
                    game.captureAssists.getOrPut(playerTeam) { mutableSetOf() }.add(player.uniqueId)
                    
                    // 旗取得統計を記録
                    game.playerFlagPickups[player.uniqueId] = (game.playerFlagPickups[player.uniqueId] ?: 0) + 1
                    
                    // メッセージ
                    game.getAllPlayers().forEach {
                        it.sendMessage(Component.text("${player.name}が${enemyTeam.displayName}の旗を取得しました！", NamedTextColor.YELLOW))
                    }
                    
                    // 効果音
                    player.playSound(player.location, org.bukkit.Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.0f)
                }
            }
        }
        
        // 自陣の旗位置で敵の旗を持っている場合、キャプチャー
        val ownFlagLocation = when (playerTeam) {
            Team.RED -> game.getRedFlagLocation()
            Team.BLUE -> game.getBlueFlagLocation()
        } ?: return
        
        if (playerLoc.distance(ownFlagLocation) < 3.0) {
            game.captureFlag(player)
        }
        
        // ショップ使用可能範囲の通知（建築・戦闘フェーズ中のみ）
        if (game.phase == GamePhase.BUILD || game.phase == GamePhase.COMBAT) {
            val spawnLocation = when (playerTeam) {
                Team.RED -> game.getRedSpawnLocation() ?: game.getRedFlagLocation()
                Team.BLUE -> game.getBlueSpawnLocation() ?: game.getBlueFlagLocation()
            } ?: return
            
            val useRange = plugin.config.getDouble("shop.use-range", 15.0)
            val distance = player.location.distance(spawnLocation)
            
            val wasInRange = player.hasMetadata("shop_in_range")
            val isInRange = distance <= useRange
            
            if (isInRange && !wasInRange) {
                player.setMetadata("shop_in_range", org.bukkit.metadata.FixedMetadataValue(plugin, true))
                player.sendActionBar(Component.text("[ショップ使用可能]").color(NamedTextColor.GREEN).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            } else if (!isInRange && wasInRange) {
                player.removeMetadata("shop_in_range", plugin)
            }
        }
    }

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state != GameState.RUNNING || game.phase != GamePhase.COMBAT) return
        
        // 旗の拾得を試みる
        if (game.pickupFlag(player, event.item)) {
            // 旗の拾得に成功した場合、イベントをキャンセル（通常のアイテム取得を防ぐ）
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING) {
            // ビルドフェーズでの死亡処理
            if (game.phase == GamePhase.BUILD) {
                // アイテムをドロップしない
                event.drops.clear()
                event.keepInventory = true
                event.droppedExp = 0
                
                // 即座にリスポーン
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    player.spigot().respawn()
                    game.handleRespawn(player)
                }, 1L) // 1 tick後（ほぼ即座）
                
                return
            }
            
            // 戦闘フェーズでの死亡処理
            if (game.phase == GamePhase.COMBAT) {
                val team = game.getPlayerTeam(player.uniqueId) ?: return
                val match = gameManager.getMatch(game.name)
            
            // 死亡回数を増やす
            val deaths = (game.playerDeaths[player.uniqueId] ?: 0) + 1
            game.playerDeaths[player.uniqueId] = deaths
            
            // キルストリークをリセット
            game.killStreaks[player.uniqueId] = 0
            
            // キラーの取得
            val killer = player.killer
            if (killer != null) {
                val killerTeam = game.getPlayerTeam(killer.uniqueId)
                if (killerTeam != null && killerTeam != team) {
                    // キル数を増やす
                    val kills = (game.playerKills[killer.uniqueId] ?: 0) + 1
                    game.playerKills[killer.uniqueId] = kills
                    
                    // キルストリークを増やす
                    val streak = (game.killStreaks[killer.uniqueId] ?: 0) + 1
                    game.killStreaks[killer.uniqueId] = streak
                    
                    // 基本キル報酬
                    val isCarrier = player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier
                    val baseReward = if (isCarrier) {
                        // 旗キャリアキル（防衛）の統計を記録
                        game.playerFlagDefends[killer.uniqueId] = (game.playerFlagDefends[killer.uniqueId] ?: 0) + 1
                        plugin.config.getInt("currency.carrier-kill-reward", 20)
                    } else {
                        plugin.config.getInt("currency.kill-reward", 10)
                    }
                    
                    // キルストリークボーナス
                    val streakBonus = when (streak) {
                        2 -> plugin.config.getInt("currency.kill-streak-bonus.2-kills", 5)
                        3 -> plugin.config.getInt("currency.kill-streak-bonus.3-kills", 10)
                        4 -> plugin.config.getInt("currency.kill-streak-bonus.4-kills", 15)
                        in 5..Int.MAX_VALUE -> plugin.config.getInt("currency.kill-streak-bonus.5-plus-kills", 20)
                        else -> 0
                    }
                    
                    val totalReward = baseReward + streakBonus
                    
                    // マッチがある場合もない場合も通貨を追加
                    val message = if (streakBonus > 0) {
                        "${killer.name}が${player.name}をキル (${streak}連続キル!)"
                    } else {
                        "${killer.name}が${player.name}をキル"
                    }
                    game.addTeamCurrency(killerTeam, totalReward, message)
                    
                    // キルストリーク通知
                    if (streak >= 2) {
                        game.getAllPlayers().forEach { p ->
                            p.sendMessage(Component.text("${killer.name} は ${streak} 連続キル中！", NamedTextColor.GOLD))
                        }
                    }
                }
            }
            
            // アシスト報酬の処理
            val currentTime = System.currentTimeMillis()
            val assistTimeWindow = 5000L // 5秒以内のダメージをアシストとして扱う
            val damagers = game.damageTracking[player.uniqueId]
            
            if (damagers != null) {
                damagers.forEach { (assisterId, damageTime) ->
                    if (currentTime - damageTime <= assistTimeWindow && assisterId != killer?.uniqueId) {
                        val assister = Bukkit.getPlayer(assisterId)
                        if (assister != null) {
                            val assisterTeam = game.getPlayerTeam(assisterId)
                            if (assisterTeam != null && assisterTeam != team) {
                                // アシスト報酬
                                val isCarrierAssist = player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier
                                val assistReward = if (isCarrierAssist) {
                                    plugin.config.getInt("currency.carrier-kill-assist-reward", 10)
                                } else {
                                    plugin.config.getInt("currency.kill-assist-reward", 5)
                                }
                                
                                game.addTeamCurrency(assisterTeam, assistReward, "${assister.name}がキルアシスト")
                                assister.sendMessage(Component.text("キルアシスト! +${assistReward}G", NamedTextColor.GREEN))
                                
                                // アシスト統計を記録
                                game.playerAssists[assisterId] = (game.playerAssists[assisterId] ?: 0) + 1
                            }
                        }
                    }
                }
                // ダメージ記録をクリア
                game.damageTracking.remove(player.uniqueId)
            }
            
            // 死亡時はすべてのアイテムを保持（旗以外ドロップしない）
            val itemsToKeep = mutableListOf<ItemStack>()
            val leatherArmorToKeep = mutableListOf<ItemStack>()
            
            // インベントリ内のアイテムを分類
            for (item in player.inventory.contents) {
                if (item != null) {
                    if (isLeatherArmor(item.type)) {
                        // 革防具は別途保存
                        leatherArmorToKeep.add(item.clone())
                    } else {
                        // その他のアイテムは通常通り保持
                        itemsToKeep.add(item)
                    }
                }
            }
            
            // 装備中の革防具も保存
            player.equipment?.let { equipment ->
                listOf(
                    equipment.helmet,
                    equipment.chestplate,
                    equipment.leggings,
                    equipment.boots
                ).forEach { armorItem ->
                    if (armorItem != null && isLeatherArmor(armorItem.type)) {
                        leatherArmorToKeep.add(armorItem.clone())
                    }
                }
            }
            
            // アイテムはドロップしない
            event.drops.clear()
            
            // 保持するアイテムと革防具を記録
            player.setMetadata("ctf_items_to_keep", 
                org.bukkit.metadata.FixedMetadataValue(plugin, itemsToKeep))
            player.setMetadata("ctf_leather_armor_to_keep", 
                org.bukkit.metadata.FixedMetadataValue(plugin, leatherArmorToKeep))
            
            event.keepInventory = false
            event.droppedExp = 0
            
            // 死亡位置を記録（旗ドロップ前に）
            val deathLocation = player.location.clone()
            
            // 旗を持っていた場合の処理
            if (player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier) {
                val flagTeam = if (player.uniqueId == game.redFlagCarrier) Team.RED else Team.BLUE
                
                // 奈落死の判定（Y座標が-64以下、または0以下、または死因がVOID）
                val isVoidDeath = deathLocation.y <= -64 || 
                                 deathLocation.y <= 0 || 
                                 player.lastDamageCause?.cause == EntityDamageEvent.DamageCause.VOID
                
                if (isVoidDeath) {
                    // 奈落死の場合は即座に旗を返却（アイテムがドロップされないため）
                    plugin.logger.info("[Flag] Void death detected at Y=${deathLocation.y}, returning flag immediately")
                    
                    // キャリアをクリア
                    when (flagTeam) {
                        Team.RED -> game.redFlagCarrier = null
                        Team.BLUE -> game.blueFlagCarrier = null
                    }
                    
                    // グロー効果を解除
                    player.isGlowing = false
                    
                    // 旗を元の位置に戻す
                    val flagLocation = when (flagTeam) {
                        Team.RED -> game.getRedFlagLocation()
                        Team.BLUE -> game.getBlueFlagLocation()
                    } ?: return
                    
                    game.setupFlagBeacon(flagLocation, flagTeam)
                    
                    game.getAllPlayers().forEach {
                        it.sendMessage(Component.text("${flagTeam.displayName}の旗が元の位置に戻りました（奈落死のため）", flagTeam.color))
                    }
                } else {
                    // 通常の死亡の場合はドロップ処理
                    game.dropFlag(player, flagTeam, deathLocation)
                }
            }
            
            // リスポーン処理（死亡回数に応じた遅延）
            val baseDelay = plugin.config.getInt("default-game.respawn-delay-base", 10)
            val deathPenalty = plugin.config.getInt("default-game.respawn-delay-per-death", 2)
            val maxDelay = plugin.config.getInt("default-game.respawn-delay-max", 20)
            
            val respawnDelay = minOf(baseDelay + (deaths - 1) * deathPenalty, maxDelay)
            
            // 死亡メッセージにリスポーン時間を表示
            player.sendMessage(Component.text("リスポーンまで ${respawnDelay} 秒...", NamedTextColor.YELLOW))
            
            // 死亡位置を保存
            player.setMetadata("death_location", org.bukkit.metadata.FixedMetadataValue(plugin, deathLocation))
            
            // 即座にリスポーンしてスペクテーターモードに
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                player.spigot().respawn()
            }, 1L) // 1 tick後
            
            // 指定時間後にサバイバルモードで復活
            val respawnTask = object : BukkitRunnable() {
                override fun run() {
                    // ゲームが終了している場合はリスポーンしない
                    if (game.state != GameState.RUNNING) {
                        plugin.logger.info("[Respawn] Game not running, cancelling respawn for ${player.name}")
                        game.respawnTasks.remove(player.uniqueId)
                        return
                    }
                    
                    // プレイヤーがまだゲームに参加しているか確認
                    if (game.getPlayerTeam(player.uniqueId) == null) {
                        plugin.logger.info("[Respawn] Player not in game, cancelling respawn for ${player.name}")
                        game.respawnTasks.remove(player.uniqueId)
                        return
                    }
                    
                    game.handleRespawn(player)
                    player.gameMode = GameMode.SURVIVAL
                    
                    // 保持アイテムを再配布
                    val keptItems = player.getMetadata("ctf_items_to_keep")
                        .firstOrNull()?.value() as? List<ItemStack> ?: emptyList()
                    
                    for (item in keptItems) {
                        player.inventory.addItem(item)
                    }
                    
                    player.removeMetadata("ctf_items_to_keep", plugin)
                    
                    // タスクをマップから削除
                    game.respawnTasks.remove(player.uniqueId)
                }
            }
            
            // リスポーンタスクを記録
            game.respawnTasks[player.uniqueId] = respawnTask
            respawnTask.runTaskLater(plugin, respawnDelay * 20L) // 秒をticksに変換
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        
        // 攻撃者を取得（直接攻撃または発射物）
        val attacker: Player? = when (val damager = event.damager) {
            is Player -> damager
            is org.bukkit.entity.Projectile -> damager.shooter as? Player
            else -> null
        }
        
        attacker ?: return
        
        val victimGame = gameManager.getPlayerGame(victim)
        val attackerGame = gameManager.getPlayerGame(attacker)
        
        // 異なるゲームのプレイヤー同士はダメージなし
        if (victimGame != attackerGame) {
            event.isCancelled = true
            return
        }
        
        val game = victimGame ?: return
        
        if (game.state == GameState.RUNNING) {
            val victimTeam = game.getPlayerTeam(victim.uniqueId)
            val attackerTeam = game.getPlayerTeam(attacker.uniqueId)
            
            // 同じチームのプレイヤー同士はダメージなし
            if (victimTeam != null && attackerTeam != null && victimTeam == attackerTeam) {
                event.isCancelled = true
                attacker.sendMessage(Component.text("チームメイトを攻撃することはできません！", NamedTextColor.RED))
                return
            }
            
            // スポーン保護チェック
            if (game.isUnderSpawnProtection(victim)) {
                event.isCancelled = true
                attacker.sendMessage(Component.text("このプレイヤーはスポーン保護中です！", NamedTextColor.YELLOW))
                return
            }
            
            // 建築フェーズ中はPVP禁止
            if (game.phase == GamePhase.BUILD) {
                event.isCancelled = true
                attacker.sendMessage(Component.text("建築フェーズ中は戦闘できません！", NamedTextColor.RED))
                return
            }
            
            // 戦闘フェーズでPVPを強制的に有効化
            if (game.phase == GamePhase.COMBAT) {
                // PVPが無効でもダメージを通す
                if (event.isCancelled) {
                    event.isCancelled = false
                }
                
                // ダメージトラッキング（アシスト用）
                if (victimTeam != null && attackerTeam != null && victimTeam != attackerTeam) {
                    val attackerMap = game.damageTracking.getOrPut(victim.uniqueId) { mutableMapOf() }
                    attackerMap[attacker.uniqueId] = System.currentTimeMillis()
                    
                    // 被害者が旗キャリアの場合、攻撃者をキャプチャーアシストリストに追加
                    if (victim.uniqueId == game.redFlagCarrier || victim.uniqueId == game.blueFlagCarrier) {
                        game.captureAssists.getOrPut(attackerTeam) { mutableSetOf() }.add(attacker.uniqueId)
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        // デバッグログ
        plugin.logger.info("[BlockBreak] Player: ${player.name}, GameState: ${game.state}, Phase: ${game.phase}, BuildPhaseGameMode: ${game.buildPhaseGameMode}, PlayerGameMode: ${player.gameMode}")
        
        // ゲーム中でない場合は禁止（STARTINGとRUNNINGの両方を許可）
        if (game.state != GameState.RUNNING && game.state != GameState.STARTING) {
            event.isCancelled = true
            plugin.logger.info("[BlockBreak] Cancelled: Game state is ${game.state}")
            return
        }
        
        when (game.phase) {
            GamePhase.BUILD -> {
                // 建築フェーズ：ゲームモードに応じた制限
                val gameMode = GameMode.valueOf(game.buildPhaseGameMode)
                plugin.logger.info("[BlockBreak] Build phase - checking gamemode: $gameMode")
                when (gameMode) {
                    GameMode.ADVENTURE -> {
                        event.isCancelled = true
                        player.sendMessage(Component.text("アドベンチャーモードではブロックを破壊できません", NamedTextColor.RED))
                    }
                    GameMode.SURVIVAL, GameMode.CREATIVE -> {
                        // 旗とスポーン装飾は破壊不可
                        if (isProtectedBlock(game, event.block.location)) {
                            event.isCancelled = true
                            player.sendMessage(Component.text("ゲーム用のブロックは破壊できません", NamedTextColor.RED))
                            plugin.logger.info("[BlockBreak] Protected block at ${event.block.location}")
                        } else {
                            val blockType = event.block.type
                            
                            // チームカラーブロック（コンクリート、ガラス）と白いブロックはドロップしない
                            val isTeamColorBlock = blockType in listOf(
                                Material.RED_CONCRETE, Material.BLUE_CONCRETE,
                                Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS,
                                Material.WHITE_CONCRETE, Material.WHITE_STAINED_GLASS
                            )
                            
                            if (isTeamColorBlock) {
                                event.isDropItems = false
                                plugin.logger.info("[BlockBreak] Team color or white block - no drops")
                            }
                            
                            // 明示的に許可
                            event.isCancelled = false
                            plugin.logger.info("[BlockBreak] Allowing block break in $gameMode mode")
                            
                            // ブロック破壊を記録から削除
                            game.recordBlockBreak(event.block.location)
                        }
                    }
                    GameMode.SPECTATOR -> {
                        event.isCancelled = true
                    }
                }
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズ：旗とスポーン装飾は破壊不可
                if (isProtectedBlock(game, event.block.location)) {
                    event.isCancelled = true
                    player.sendMessage(Component.text("ゲーム用のブロックは破壊できません", NamedTextColor.RED))
                } else {
                    // 戦闘フェーズではアイテムをドロップしない
                    event.isDropItems = false
                    
                    // ブロック破壊を記録し、切断されたブロックを白く変換
                    game.recordBlockBreak(event.block.location)
                }
            }
            GamePhase.INTERMISSION -> {
                // 作戦会議フェーズ：全面的に破壊禁止
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        // デバッグログ
        plugin.logger.info("[BlockPlace] Player: ${player.name}, GameState: ${game.state}, Phase: ${game.phase}, BuildPhaseGameMode: ${game.buildPhaseGameMode}, PlayerGameMode: ${player.gameMode}")
        
        // ゲーム中でない場合は禁止（STARTINGとRUNNINGの両方を許可）
        if (game.state != GameState.RUNNING && game.state != GameState.STARTING) {
            event.isCancelled = true
            plugin.logger.info("[BlockPlace] Cancelled: Game state is ${game.state}")
            return
        }
        
        // 無限ブロックかどうかを事前にチェック（設置前のアイテム情報を保存）
        val itemInHand = event.itemInHand
        val originalItemType = itemInHand?.type
        val originalItemMeta = itemInHand?.itemMeta?.clone()
        val isInfiniteBlock = itemInHand?.itemMeta?.persistentDataContainer?.get(
            NamespacedKey(plugin, "infinite_block"),
            PersistentDataType.BOOLEAN
        ) ?: false
        
        // ビーコンの上にブロックを置けないようにする
        val blockLocation = event.block.location
        val blockBelow = blockLocation.clone().subtract(0.0, 1.0, 0.0).block
        if (blockBelow.type == Material.BEACON) {
            event.isCancelled = true
            player.sendMessage(Component.text("ビーコンの上にブロックを置くことはできません", NamedTextColor.RED))
            return
        }
        
        when (game.phase) {
            GamePhase.BUILD -> {
                // 建築フェーズ：ゲームモードに応じた制限
                val gameMode = GameMode.valueOf(game.buildPhaseGameMode)
                plugin.logger.info("[BlockPlace] Build phase - checking gamemode: $gameMode")
                when (gameMode) {
                    GameMode.ADVENTURE -> {
                        event.isCancelled = true
                        player.sendMessage(Component.text("アドベンチャーモードではブロックを設置できません", NamedTextColor.RED))
                    }
                    GameMode.SURVIVAL, GameMode.CREATIVE -> {
                        // ブロック設置制限をチェック
                        val block = event.block
                        val parent = game.canPlaceBlock(player, block)
                        if (parent == null) {
                            event.isCancelled = true
                            return
                        }
                        
                        // 明示的に許可
                        event.isCancelled = false
                        plugin.logger.info("[BlockPlace] Allowing block placement in $gameMode mode at ${block.location}")
                        
                        // ブロック設置を記録（ツリー構造で）
                        val team = game.getPlayerTeam(player.uniqueId)
                        if (team != null) {
                            game.recordBlockPlacement(team, block.location, parent)
                        }
                        
                        // ブロック設置統計を記録
                        game.playerBlocksPlaced[player.uniqueId] = (game.playerBlocksPlaced[player.uniqueId] ?: 0) + 1
                    }
                    GameMode.SPECTATOR -> {
                        event.isCancelled = true
                    }
                }
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズ：ブロック設置禁止
                event.isCancelled = true
                player.sendMessage(Component.text("戦闘フェーズ中はブロックを設置できません", NamedTextColor.RED))
            }
            GamePhase.INTERMISSION -> {
                // 作戦会議フェーズ：全面的に設置禁止
                event.isCancelled = true
            }
        }
        
        // 無限ブロックの処理（設置が成功した場合のみ）
        if (!event.isCancelled && isInfiniteBlock && originalItemType != null && originalItemType != Material.AIR) {
            plugin.logger.info("[InfiniteBlock] Processing infinite block placement - Hand: ${event.hand}, Item: ${originalItemType}")
            
            // 無限ブロックの場合、アイテムを補充
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                // 元のアイテムを復元
                val restoredItem = ItemStack(originalItemType).apply {
                    amount = 1
                    if (originalItemMeta != null) {
                        itemMeta = originalItemMeta
                    }
                }
                
                // 使用された手に応じてアイテムを補充
                when (event.hand) {
                    EquipmentSlot.HAND -> {
                        val currentItem = player.inventory.itemInMainHand
                        plugin.logger.info("[InfiniteBlock] Main hand - Current: ${currentItem.type}, Amount: ${currentItem.amount}")
                        if (currentItem.type == Material.AIR || currentItem.amount == 0) {
                            player.inventory.setItemInMainHand(restoredItem)
                            plugin.logger.info("[InfiniteBlock] Replenished main hand with ${restoredItem.type}")
                        }
                    }
                    EquipmentSlot.OFF_HAND -> {
                        val currentItem = player.inventory.itemInOffHand
                        plugin.logger.info("[InfiniteBlock] Off hand - Current: ${currentItem.type}, Amount: ${currentItem.amount}")
                        if (currentItem.type == Material.AIR || currentItem.amount == 0) {
                            player.inventory.setItemInOffHand(restoredItem)
                            plugin.logger.info("[InfiniteBlock] Replenished off hand with ${restoredItem.type}")
                        }
                    }
                    else -> {
                        plugin.logger.warning("[InfiniteBlock] Unknown hand type: ${event.hand}")
                    }
                }
            }, 1L)
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        // ビーコンをクリックした場合の処理（旗の情報表示のみ）
        if (event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_BLOCK) {
            val clickedBlock = event.clickedBlock
            if (clickedBlock != null && clickedBlock.type == Material.BEACON) {
                if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
                    event.isCancelled = true
                    
                    val blockLoc = clickedBlock.location
                    
                    // 旗の位置かチェック
                    val isRedFlag = game.getRedFlagLocation()?.let { it.distance(blockLoc) < 1.0 } ?: false
                    val isBlueFlag = game.getBlueFlagLocation()?.let { it.distance(blockLoc) < 1.0 } ?: false
                    
                    if (isRedFlag || isBlueFlag) {
                        val flagTeam = if (isRedFlag) Team.RED else Team.BLUE
                        player.sendMessage(Component.text("${flagTeam.displayName}の旗です。近づくと取得できます", flagTeam.color))
                    }
                }
            }
        }
        
        // ショップアイテムの処理
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            val item = event.item ?: return
            
            // ショップアイテムのチェック
            if (item.type == Material.EMERALD && item.hasItemMeta()) {
                val meta = item.itemMeta
                val displayName = meta.displayName()
                if (displayName != null && displayName.toString().contains("ショップ")) {
                    event.isCancelled = true
                    
                    val team = game.getPlayerTeam(player.uniqueId)
                    if (team == null) {
                        player.sendMessage(Component.text("チームが見つかりません", NamedTextColor.RED))
                        return
                    }
                    
                    // マッチがなくてもゲームがあればショップを使える
                    
                    // フェーズチェック
                    if (game.phase != GamePhase.BUILD && game.phase != GamePhase.COMBAT) {
                        player.sendMessage(Component.text("ショップは建築・戦闘フェーズ中のみ使用可能です", NamedTextColor.RED))
                        return
                    }
                    
                    // 距離制限を削除し、直接カテゴリーメニューを開く
                    shopManager.openCategoryMenu(player, game, team)
                }
            }
        }
    }
    
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = gameManager.getPlayerGame(player) ?: return
        
        // 防具の装備制限
        if (game.state == GameState.RUNNING) {
            val clickedItem = event.currentItem
            val cursor = event.cursor
            
            // 防具スロットへの装備チェック
            if (event.slot in 36..39) { // 防具スロット
                if (clickedItem != null && isArmor(clickedItem.type) && !clickedItem.type.name.startsWith("LEATHER_")) {
                    event.isCancelled = true
                    player.sendMessage(Component.text("革の防具以外は装備できません！", NamedTextColor.RED))
                    return
                }
                if (cursor != null && isArmor(cursor.type) && !cursor.type.name.startsWith("LEATHER_")) {
                    event.isCancelled = true
                    player.sendMessage(Component.text("革の防具以外は装備できません！", NamedTextColor.RED))
                    return
                }
            }
            
            // Shift+クリックでの防具装備チェック
            if (event.isShiftClick && clickedItem != null && isArmor(clickedItem.type) && !clickedItem.type.name.startsWith("LEATHER_")) {
                event.isCancelled = true
                player.sendMessage(Component.text("革の防具以外は装備できません！", NamedTextColor.RED))
                return
            }
            
            // 防具の取り外し禁止（戦闘フェーズのみ）
            if (game.phase == GamePhase.COMBAT) {
                val item = event.currentItem
                if (item != null && isArmor(item.type)) {
                    event.isCancelled = true
                    player.sendMessage(Component.text("戦闘中は防具を外せません！", NamedTextColor.RED))
                    return
                }
            }
        }
        
        // ショップUIのクリック処理
        val inventory = event.inventory
        val title = event.view.title()
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        
        if (inventory.holder == null && titleText.contains("ショップ")) {
            // すべてのクリックをキャンセル
            event.isCancelled = true
            
            // クリックされたインベントリがプレイヤーのものならreturn（アイテム移動防止）
            if (event.clickedInventory == player.inventory) {
                return
            }
            
            // ショップインベントリのアイテムをクリックした場合のみ処理
            val clickedItem = event.currentItem ?: return
            if (clickedItem.type == Material.AIR) return
            
            val team = game.getPlayerTeam(player.uniqueId) ?: return
            
            // カテゴリー選択画面の処理
            if (titleText.contains("カテゴリー選択")) {
                val meta = clickedItem.itemMeta ?: return
                val displayNameComponent = meta.displayName()
                if (displayNameComponent == null) return
                val displayName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayNameComponent)
                
                val page = when (displayName) {
                    "武器" -> 0
                    "消耗品" -> 1
                    "ブロック" -> 2
                    else -> return
                }
                
                shopManager.openShop(player, game, team, page)
                return
            }
            
            // アイテム名から購入処理
            val meta = clickedItem.itemMeta ?: return
            val displayNameComponent = meta.displayName()
            if (displayNameComponent == null) {
                player.sendMessage(Component.text("アイテム名を取得できませんでした", NamedTextColor.RED))
                return
            }
            // レガシーフォーマットを含むテキストを取得
            val displayName = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(displayNameComponent)
            plugin.logger.info("Clicked item displayName: '$displayName'")
            
            // ナビゲーションボタンのチェック（レガシーフォーマットを含む）
            when (displayName) {
                "§a§l← 前のページ" -> {
                    val currentPage = shopManager.getCurrentPage(player)
                    if (currentPage > 0) {
                        shopManager.openShop(player, game, team, currentPage - 1)
                    }
                    return
                }
                "§a§l次のページ →" -> {
                    val currentPage = shopManager.getCurrentPage(player)
                    shopManager.openShop(player, game, team, currentPage + 1)
                    return
                }
                "§6§lメインメニュー" -> {
                    // カテゴリー選択画面を開く
                    shopManager.openCategoryMenu(player, game, team)
                    return
                }
            }
            
            // 通常のアイテム購入
            if (shopManager.handlePurchase(player, displayName, game, team)) {
                // 購入成功時はUIを更新
                player.closeInventory()
                val currentPage = shopManager.getCurrentPage(player)
                shopManager.openShop(player, game, team, currentPage)
            }
        }
    }
    
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = gameManager.getPlayerGame(player) ?: return
        
        // ショップUIでのドラッグを防止
        val title = event.view.title()
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        
        if (event.inventory.holder == null && titleText.contains("ショップ")) {
            event.isCancelled = true
        }
    }
    
    private fun isArmor(material: Material): Boolean {
        val name = material.name
        return name.endsWith("_HELMET") || 
               name.endsWith("_CHESTPLATE") || 
               name.endsWith("_LEGGINGS") || 
               name.endsWith("_BOOTS")
    }
    
    private fun isLeatherArmor(material: Material): Boolean {
        return material == Material.LEATHER_HELMET ||
               material == Material.LEATHER_CHESTPLATE ||
               material == Material.LEATHER_LEGGINGS ||
               material == Material.LEATHER_BOOTS
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            // 戦闘フェーズ中の死亡リスポーン
            // 保存された死亡位置を取得（なければ現在位置を使用）
            val deathLocation = player.getMetadata("death_location")
                .firstOrNull()?.value() as? Location ?: player.location.clone()
            
            // メタデータをクリア
            player.removeMetadata("death_location", plugin)
            
            // 死亡位置がゲームワールドであることを確認
            val gameWorld = game.world
            if (deathLocation.world != gameWorld) {
                // ワールドが違う場合は、ゲームワールドの同じ座標に修正
                deathLocation.world = gameWorld
            }
            
            val spectatorLocation = game.findSafeSpectatorLocation(deathLocation)
            
            // リスポーン地点をゲームワールドの観戦位置に設定
            event.respawnLocation = spectatorLocation
            
            // スペクテーターモードに設定
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                player.gameMode = GameMode.SPECTATOR
                player.sendMessage(Component.text("スペクテーターモードで観戦中...", NamedTextColor.GRAY))
                
                // 死亡回数を取得してリスポーン遅延を計算
                val deaths = game.playerDeaths[player.uniqueId] ?: 1
                val baseDelay = plugin.config.getInt("default-game.respawn-delay-base", 10)
                val deathPenalty = plugin.config.getInt("default-game.respawn-delay-per-death", 2)
                val maxDelay = plugin.config.getInt("default-game.respawn-delay-max", 20)
                val respawnDelay = minOf(baseDelay + (deaths - 1) * deathPenalty, maxDelay)
                
                // 指定時間後にサバイバルモードで復活
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    game.handleRespawn(player)
                    player.gameMode = GameMode.SURVIVAL
                    
                    // 保持アイテムを再配布
                    val keptItems = player.getMetadata("ctf_items_to_keep")
                        .firstOrNull()?.value() as? List<ItemStack> ?: emptyList()
                    
                    for (item in keptItems) {
                        player.inventory.addItem(item)
                    }
                    
                    // 革防具を再装備
                    val keptArmor = player.getMetadata("ctf_leather_armor_to_keep")
                        .firstOrNull()?.value() as? List<ItemStack> ?: emptyList()
                    
                    player.equipment?.let { equipment ->
                        for (armor in keptArmor) {
                            when {
                                armor.type == Material.LEATHER_HELMET -> equipment.helmet = armor
                                armor.type == Material.LEATHER_CHESTPLATE -> equipment.chestplate = armor
                                armor.type == Material.LEATHER_LEGGINGS -> equipment.leggings = armor
                                armor.type == Material.LEATHER_BOOTS -> equipment.boots = armor
                            }
                        }
                    }
                    
                    player.removeMetadata("ctf_items_to_keep", plugin)
                    player.removeMetadata("ctf_leather_armor_to_keep", plugin)
                }, respawnDelay * 20L) // 秒をticksに変換
            }, 1L)
        } else if (game.state == GameState.RUNNING && game.phase == GamePhase.BUILD) {
            // 建築フェーズ中のリスポーン
            val team = game.getPlayerTeam(player.uniqueId) ?: return
            val spawnLocation = when (team) {
                Team.RED -> game.getRedSpawnLocation()
                Team.BLUE -> game.getBlueSpawnLocation()
            }
            if (spawnLocation != null) {
                event.respawnLocation = spawnLocation
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onItemDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity !is Item) return
        
        val item = entity.itemStack
        val itemName = item.itemMeta?.displayName() ?: return
        val nameText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(itemName)
        
        // 旗アイテムかチェック
        if (item.type == Material.BEACON && nameText.contains("の旗")) {
            plugin.logger.info("[Flag] Flag item taking damage: ${event.cause}")
            
            // ダメージの原因をチェック
            when (event.cause) {
                EntityDamageEvent.DamageCause.LAVA,
                EntityDamageEvent.DamageCause.FIRE,
                EntityDamageEvent.DamageCause.FIRE_TICK,
                EntityDamageEvent.DamageCause.VOID -> {
                    // 旗を即座に返却
                    event.isCancelled = true
                    entity.remove()
                    
                    // どちらのチームの旗か判定
                    val isRedFlag = nameText.contains("赤チーム")
                    val team = if (isRedFlag) Team.RED else Team.BLUE
                    
                    // 該当するゲームを探す
                    gameManager.getAllGames().values.forEach { game ->
                        if (game.state == GameState.RUNNING) {
                            val flagLocation = if (isRedFlag) game.getRedFlagLocation() else game.getBlueFlagLocation()
                            if (flagLocation != null) {
                                game.setupFlagBeacon(flagLocation, team)
                                game.getAllPlayers().forEach { player ->
                                    player.sendMessage(Component.text("${team.displayName}の旗が元の位置に戻りました（${getDamageCauseMessage(event.cause)}）", team.color))
                                }
                                plugin.logger.info("[Flag] Flag returned due to ${event.cause}")
                            }
                        }
                    }
                }
                else -> {
                    // その他のダメージは無効化
                    event.isCancelled = true
                }
            }
        }
    }
    
    @EventHandler
    fun onItemDespawn(event: ItemDespawnEvent) {
        val item = event.entity.itemStack
        val itemName = item.itemMeta?.displayName() ?: return
        val nameText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(itemName)
        
        // 旗アイテムの自然消滅を防ぐ
        if (item.type == Material.BEACON && nameText.contains("の旗")) {
            event.isCancelled = true
            plugin.logger.info("[Flag] Prevented flag despawn")
        }
    }
    
    @EventHandler
    fun onItemCombust(event: EntityCombustEvent) {
        val entity = event.entity
        if (entity !is Item) return
        
        val item = entity.itemStack
        val itemName = item.itemMeta?.displayName() ?: return
        val nameText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(itemName)
        
        // 旗アイテムが燃えそうになったら
        if (item.type == Material.BEACON && nameText.contains("の旗")) {
            event.isCancelled = true
            entity.remove()
            
            // どちらのチームの旗か判定
            val isRedFlag = nameText.contains("赤チーム")
            val team = if (isRedFlag) Team.RED else Team.BLUE
            
            // 該当するゲームを探す
            gameManager.getAllGames().values.forEach { game ->
                if (game.state == GameState.RUNNING) {
                    val flagLocation = if (isRedFlag) game.getRedFlagLocation() else game.getBlueFlagLocation()
                    if (flagLocation != null) {
                        game.setupFlagBeacon(flagLocation, team)
                        game.getAllPlayers().forEach { player ->
                            player.sendMessage(Component.text("${team.displayName}の旗が元の位置に戻りました（炎上のため）", team.color))
                        }
                        plugin.logger.info("[Flag] Flag returned due to combustion")
                    }
                }
            }
        }
    }
    
    private fun getDamageCauseMessage(cause: EntityDamageEvent.DamageCause): String {
        return when (cause) {
            EntityDamageEvent.DamageCause.LAVA -> "溶岩に落ちたため"
            EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK -> "炎上のため"
            EntityDamageEvent.DamageCause.VOID -> "奈落に落ちたため"
            else -> "ダメージを受けたため"
        }
    }

    @EventHandler
    fun onPlayerCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        val command = event.message.toLowerCase()
        
        // /clearコマンドを検出
        if (command.startsWith("/clear") || command.startsWith("/minecraft:clear")) {
            val game = gameManager.getPlayerGame(player)
            if (game != null && game.state == GameState.RUNNING) {
                // ゲーム中は/clearコマンドを無効化
                event.isCancelled = true
                player.sendMessage(Component.text("ゲーム中は/clearコマンドは使用できません！", NamedTextColor.RED))
                return
            }
            
            // ゲーム外でも、ショップアイテムを持っている場合は警告
            val hasShopItem = player.inventory.contents.any { item ->
                item != null && shopManager.isShopItem(item)
            }
            
            if (hasShopItem) {
                // 一旦clearを実行させて、その後ショップアイテムを再配布
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (!player.inventory.contains(Material.EMERALD)) {
                        player.inventory.addItem(shopManager.createShopItem())
                        player.sendMessage(Component.text("ショップアイテムは削除できません。再配布されました。", NamedTextColor.YELLOW))
                    }
                }, 1L)
            }
        }
    }
    
    private fun isProtectedBlock(game: com.hacklab.ctf.Game, location: org.bukkit.Location): Boolean {
        // 白いコンクリート（切断されたブロック）は誰でも破壊可能
        if (location.block.type == com.hacklab.ctf.Game.NEUTRAL_BLOCK) {
            return false
        }
        
        // 旗の位置チェック
        val flagLocations = listOf(game.getRedFlagLocation(), game.getBlueFlagLocation()).filterNotNull()
        for (flagLoc in flagLocations) {
            // 旗本体（ビーコン）
            if (location.blockX == flagLoc.blockX && 
                location.blockY == flagLoc.blockY && 
                location.blockZ == flagLoc.blockZ) {
                return true
            }
            
            // 色付きガラス（ビーコンの上）
            if (location.blockX == flagLoc.blockX && 
                location.blockY == flagLoc.blockY + 1 && 
                location.blockZ == flagLoc.blockZ) {
                return true
            }
            
            // 鉄ブロックベース（ビーコンの下3x3）
            for (x in -1..1) {
                for (z in -1..1) {
                    if (location.blockX == flagLoc.blockX + x && 
                        location.blockY == flagLoc.blockY - 1 && 
                        location.blockZ == flagLoc.blockZ + z) {
                        return true
                    }
                }
            }
            
            // 旗周辺3x3の縦方向全て（Y座標制限なし）
            if (kotlin.math.abs(location.blockX - flagLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - flagLoc.blockZ) <= 1) {
                return true
            }
        }
        
        // スポーン装飾チェック（スポーン地点が設定されている場合のみ）
        val spawnLocations = listOf(game.getRedSpawnLocation(), game.getBlueSpawnLocation()).filterNotNull()
        
        for (spawnLoc in spawnLocations) {
            // コンクリート床（スポーン地点の下3x3）
            for (x in -1..1) {
                for (z in -1..1) {
                    if (location.blockX == spawnLoc.blockX + x && 
                        location.blockY == spawnLoc.blockY - 1 && 
                        location.blockZ == spawnLoc.blockZ + z) {
                        return true
                    }
                }
            }
            
            // スポーン地点周辺3x3の縦方向全て（Y座標制限なし）
            if (kotlin.math.abs(location.blockX - spawnLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - spawnLoc.blockZ) <= 1) {
                return true
            }
        }
        
        return false
    }
}