package com.hacklab.ctf.listeners

import com.hacklab.ctf.Main
import com.hacklab.ctf.Game
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
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
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
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.Event
import org.bukkit.event.player.*
import org.bukkit.inventory.InventoryHolder
import org.bukkit.event.block.Action
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.entity.Item
import org.bukkit.scheduler.BukkitRunnable

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
            player.sendMessage(Component.text(plugin.languageManager.getMessage("shop.cannot-drop-item"), NamedTextColor.RED))
            return
        }
        
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            if (isArmor(item.type)) {
                event.isCancelled = true
                player.sendMessage(Component.text(plugin.languageManager.getMessage("armor.cannot-drop-combat"), NamedTextColor.RED))
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
                Team.SPECTATOR -> return  // Spectators cannot pick up flags
            } ?: return
            
            // 敵の旗に近づいたら取得（1.5ブロック以内）
            if (playerLoc.world == enemyFlagLocation.world && playerLoc.distance(enemyFlagLocation) < 1.5) {
                // 旗がまだ誰も持っていない場合
                val enemyCarrier = when (enemyTeam) {
                    Team.RED -> game.redFlagCarrier
                    Team.BLUE -> game.blueFlagCarrier
                    Team.SPECTATOR -> return  // Spectators cannot pick up flags
                }
                
                if (enemyCarrier == null) {
                    // 旗を取得
                    when (enemyTeam) {
                        Team.RED -> game.redFlagCarrier = player.uniqueId
                        Team.BLUE -> game.blueFlagCarrier = player.uniqueId
                        Team.SPECTATOR -> return  // Spectators cannot pick up flags
                    }
                
                    // ビーコンと色付きガラスを削除
                    enemyFlagLocation.block.type = Material.AIR
                    enemyFlagLocation.clone().add(0.0, 1.0, 0.0).block.type = Material.AIR
                    
                    // スポーン保護を解除（旗を取った時点で保護解除）
                    game.removeSpawnProtection(player)
                    
                    // プレイヤーに発光効果
                    player.isGlowing = true
                    
                    // キャプチャーアシストリストに追加（旗を取った人をリストに追加）
                    game.captureAssists.getOrPut(playerTeam) { mutableSetOf() }.add(player.uniqueId)
                    
                    // 旗取得統計を記録
                    game.playerFlagPickups[player.uniqueId] = (game.playerFlagPickups[player.uniqueId] ?: 0) + 1
                    
                    // タイトル通知
                    val lang = plugin.languageManager
                    val title = Component.text(lang.getMessage("game_events.flag_capture.title"), enemyTeam.color)
                    val subtitle = Component.text(lang.getMessage("game_events.flag_capture.subtitle")
                        .replace("{player}", player.name)
                        .replace("{team}", lang.getMessage("teams.${enemyTeam.name.lowercase()}")), NamedTextColor.WHITE)
                    game.sendEventNotification(
                        title,
                        subtitle,
                        sound = org.bukkit.Sound.ENTITY_ENDER_DRAGON_HURT,
                        soundPitch = 1.2f
                    )
                    
                    // メッセージ
                    game.getAllPlayers().forEach {
                        it.sendMessage(Component.text(plugin.languageManager.getMessage("flag.picked-up",
                            "player" to player.name,
                            "color" to enemyTeam.colorCode,
                            "team" to plugin.languageManager.getMessage("teams.${enemyTeam.name.lowercase()}")), NamedTextColor.YELLOW))
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
            Team.SPECTATOR -> return  // Spectators cannot capture flags
        } ?: return
        
        // 同じワールドでない場合は距離計算をスキップ
        if (playerLoc.world == ownFlagLocation.world && playerLoc.distance(ownFlagLocation) < 3.0) {
            game.captureFlag(player)
        }
        
        // ショップ使用可能範囲の通知（建築・戦闘フェーズ中のみ）
        if (game.phase == GamePhase.BUILD || game.phase == GamePhase.COMBAT) {
            val spawnLocation = when (playerTeam) {
                Team.RED -> game.getRedSpawnLocation() ?: game.getRedFlagLocation()
                Team.BLUE -> game.getBlueSpawnLocation() ?: game.getBlueFlagLocation()
                Team.SPECTATOR -> return  // Spectators don't have shop access
            } ?: return
            
            val useRange = plugin.config.getDouble("shop.use-range", 15.0)
            val distance = if (player.location.world == spawnLocation.world) {
                player.location.distance(spawnLocation)
            } else {
                Double.MAX_VALUE  // 異なるワールドの場合は非常に大きな距離とする
            }
            
            val wasInRange = player.hasMetadata("shop_in_range")
            val isInRange = distance <= useRange
            
            if (isInRange && !wasInRange) {
                player.setMetadata("shop_in_range", org.bukkit.metadata.FixedMetadataValue(plugin, true))
                player.sendActionBar(Component.text(plugin.languageManager.getMessage("game_events.shop.available")).color(NamedTextColor.GREEN).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            } else if (!isInRange && wasInRange) {
                player.removeMetadata("shop_in_range", plugin)
            }
        }
    }

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val game = gameManager.getPlayerGame(player) ?: return
        
        // 観戦者はアイテム拾得不可
        if (game.getPlayerTeam(player.uniqueId) == Team.SPECTATOR) {
            event.isCancelled = true
            return
        }
        
        // ブロックを拾った時の処理（全フェーズ共通）
        if (game.state == GameState.RUNNING) {
            val item = event.item.itemStack
            val team = game.getPlayerTeam(player.uniqueId) ?: return
            
            // チームブロックの変換処理
            val convertedMaterial = when (item.type) {
                // コンクリートブロック
                Material.WHITE_CONCRETE, Material.RED_CONCRETE, Material.BLUE_CONCRETE -> {
                    when (team) {
                        Team.RED -> Material.RED_CONCRETE
                        Team.BLUE -> Material.BLUE_CONCRETE
                        Team.SPECTATOR -> return
                    }
                }
                // ガラスブロック
                Material.WHITE_STAINED_GLASS, Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS -> {
                    when (team) {
                        Team.RED -> Material.RED_STAINED_GLASS
                        Team.BLUE -> Material.BLUE_STAINED_GLASS
                        Team.SPECTATOR -> return
                    }
                }
                else -> null
            }
            
            // 変換が必要な場合
            if (convertedMaterial != null && convertedMaterial != item.type) {
                // 元のアイテムをキャンセルして、変換されたアイテムを追加
                event.isCancelled = true
                event.item.remove()
                
                // 変換されたアイテムをインベントリに追加
                val convertedItem = ItemStack(convertedMaterial, item.amount)
                val remaining = player.inventory.addItem(convertedItem)
                
                // インベントリに入らなかった分はドロップ
                if (remaining.isNotEmpty()) {
                    remaining.values.forEach { leftover ->
                        player.world.dropItem(player.location, leftover)
                    }
                }
                
                // 拾い上げの音を再生
                player.playSound(player.location, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.0f)
                
                plugin.logger.info("[ItemPickup] Converted block to team color: ${item.type} -> $convertedMaterial")
                return
            }
        }
        
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
                    val lang = plugin.languageManager
                    val message = if (streakBonus > 0) {
                        lang.getMessage("game_events.kill_messages.with_streak")
                            .replace("{killer}", killer.name)
                            .replace("{victim}", player.name)
                            .replace("{streak}", streak.toString())
                    } else {
                        lang.getMessage("game_events.kill_messages.normal")
                            .replace("{killer}", killer.name)
                            .replace("{victim}", player.name)
                    }
                    game.addTeamCurrency(killerTeam, totalReward, message)
                    
                    // キルストリーク通知
                    if (streak >= 2) {
                        // タイトル通知
                        val streakTitle = when (streak) {
                            2 -> Component.text(lang.getMessage("game_events.kill_streaks.double"), NamedTextColor.YELLOW)
                            3 -> Component.text(lang.getMessage("game_events.kill_streaks.triple"), NamedTextColor.GOLD)
                            4 -> Component.text(lang.getMessage("game_events.kill_streaks.mega"), NamedTextColor.RED)
                            5 -> Component.text(lang.getMessage("game_events.kill_streaks.ultra"), NamedTextColor.DARK_RED)
                            else -> Component.text(lang.getMessage("game_events.kill_streaks.consecutive")
                                .replace("{count}", streak.toString()), NamedTextColor.DARK_PURPLE)
                        }
                        val streakSubtitle = Component.text(killer.name, killerTeam.color)
                        
                        game.sendEventNotification(
                            streakTitle,
                            streakSubtitle,
                            sound = org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                            soundPitch = 1.0f + (streak * 0.1f)
                        )
                        
                        game.getAllPlayers().forEach { p ->
                            p.sendMessage(Component.text(plugin.languageManager.getMessage("death.kill-streak-notification",
                                "player" to killer.name,
                                "count" to streak.toString()), NamedTextColor.GOLD))
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
                                
                                val lang = plugin.languageManager
                                game.addTeamCurrency(assisterTeam, assistReward, lang.getMessage("game_events.kill_messages.assist_by")
                                    .replace("{player}", assister.name))
                                assister.sendMessage(Component.text(lang.getMessage("game_events.kill_messages.assist")
                                    .replace("{reward}", assistReward.toString()), NamedTextColor.GREEN))
                                
                                // アシスト統計を記録
                                game.playerAssists[assisterId] = (game.playerAssists[assisterId] ?: 0) + 1
                            }
                        }
                    }
                }
                // ダメージ記録をクリア
                game.damageTracking.remove(player.uniqueId)
            }
            
            // 死亡時のアイテム処理
            val itemsToKeep = mutableListOf<ItemStack>()
            val itemsToDrop = mutableListOf<ItemStack>()
            val leatherArmorToKeep = mutableListOf<ItemStack>()
            
            // フェーズによる処理分岐
            if (game.phase == GamePhase.BUILD) {
                // 建築フェーズ：すべてのアイテムを保持
                for (item in player.inventory.contents) {
                    if (item != null) {
                        if (isLeatherArmor(item.type)) {
                            leatherArmorToKeep.add(item.clone())
                        } else {
                            itemsToKeep.add(item.clone())
                        }
                    }
                }
            } else if (game.phase == GamePhase.COMBAT) {
                // 戦闘フェーズ：ショップアイテムと革装備以外をドロップ
                var shopItemFound = false
                for ((index, item) in player.inventory.contents.withIndex()) {
                    if (item != null) {
                        when {
                            isLeatherArmor(item.type) -> {
                                // 革防具は保持
                                leatherArmorToKeep.add(item.clone())
                            }
                            shopManager.isShopItem(item) -> {
                                // ショップアイテム（エメラルド）は1つだけ保持
                                if (!shopItemFound) {
                                    itemsToKeep.add(item.clone())
                                    shopItemFound = true
                                }
                            }
                            item.itemMeta?.persistentDataContainer?.has(
                                NamespacedKey(plugin, "no_drop"),
                                PersistentDataType.BOOLEAN
                            ) == true -> {
                                // ドロップ不可アイテムは保持
                                itemsToKeep.add(item.clone())
                            }
                            else -> {
                                // それ以外はドロップ
                                itemsToDrop.add(item.clone())
                            }
                        }
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
            
            // ドロップするアイテムを設定
            event.drops.clear()
            event.drops.addAll(itemsToDrop)
            
            // 保持するアイテムをインベントリから削除（ドロップするアイテムのみ）
            for (item in itemsToDrop) {
                player.inventory.remove(item)
            }
            
            // 保持するアイテムはインベントリに残す
            event.keepInventory = true
            event.keepLevel = true
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
                        Team.SPECTATOR -> {}  // No-op for spectators
                    }
                    
                    // グロー効果を解除
                    player.isGlowing = false
                    
                    // 旗を元の位置に戻す
                    val flagLocation = when (flagTeam) {
                        Team.RED -> game.getRedFlagLocation()
                        Team.BLUE -> game.getBlueFlagLocation()
                        Team.SPECTATOR -> return  // Spectators don't have flags
                    } ?: return
                    
                    game.setupFlagBeacon(flagLocation, flagTeam)
                    
                    game.getAllPlayers().forEach {
                        it.sendMessage(Component.text(plugin.languageManager.getMessage("flag.returned-void",
                            "team" to plugin.languageManager.getMessage("teams.${flagTeam.name.lowercase()}")), flagTeam.color))
                    }
                } else {
                    // 通常の死亡の場合はドロップ処理
                    game.dropFlag(player, flagTeam, deathLocation)
                }
            }
            
            // リスポーン処理（即座にリスポーン）
            // 即座にリスポーンメッセージを表示
            player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.respawn-instant"), NamedTextColor.YELLOW))
            
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
                    
                    // ショップアイテムはスロット8に、その他はaddItemで追加
                    for (item in keptItems) {
                        if (plugin.shopManager.isShopItem(item)) {
                            // 既存のショップアイテムを削除してから配置
                            for (i in 0 until player.inventory.size) {
                                val existingItem = player.inventory.getItem(i)
                                if (existingItem != null && plugin.shopManager.isShopItem(existingItem)) {
                                    player.inventory.setItem(i, null)
                                }
                            }
                            player.inventory.setItem(8, item)
                        } else {
                            player.inventory.addItem(item)
                        }
                    }
                    
                    player.removeMetadata("ctf_items_to_keep", plugin)
                    
                    // タスクをマップから削除
                    game.respawnTasks.remove(player.uniqueId)
                }
            }
            
            // リスポーンタスクを記録
            game.respawnTasks[player.uniqueId] = respawnTask
            respawnTask.runTaskLater(plugin, 1L) // 即座にリスポーン（1tick後）
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
        
        // 観戦者の攻撃/被攻撃を無効化
        if (attackerGame != null && attackerGame.getPlayerTeam(attacker.uniqueId) == Team.SPECTATOR) {
            event.isCancelled = true
            return
        }
        
        if (victimGame != null && victimGame.getPlayerTeam(victim.uniqueId) == Team.SPECTATOR) {
            event.isCancelled = true
            return
        }
        
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
                attacker.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.cannot-attack-teammate"), NamedTextColor.RED))
                return
            }
            
            // スポーン保護チェック
            if (game.isUnderSpawnProtection(victim)) {
                event.isCancelled = true
                attacker.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.player-has-spawn-protection"), NamedTextColor.YELLOW))
                return
            }
            
            // 攻撃者がスポーン保護中の場合、保護を解除
            if (game.isUnderSpawnProtection(attacker)) {
                game.removeSpawnProtection(attacker)
            }
            
            // 建築フェーズ中はPVP禁止
            if (game.phase == GamePhase.BUILD) {
                event.isCancelled = true
                attacker.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.cannot-fight-build-phase"), NamedTextColor.RED))
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
        
        // 観戦者はブロック破壊不可
        if (game.getPlayerTeam(player.uniqueId) == Team.SPECTATOR) {
            event.isCancelled = true
            return
        }
        
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
                        player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.cannot-break-adventure"), NamedTextColor.RED))
                    }
                    GameMode.SURVIVAL, GameMode.CREATIVE -> {
                        // 旗とスポーン装飾は破壊不可
                        if (isProtectedBlock(game, event.block.location)) {
                            event.isCancelled = true
                            player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.cannot-break-game-blocks"), NamedTextColor.RED))
                            plugin.logger.info("[BlockBreak] Protected block at ${event.block.location}")
                        } else {
                            val blockType = event.block.type
                            val playerTeam = game.getPlayerTeam(player.uniqueId)
                            
                            // チームカラーブロック（コンクリート、ガラス）と白いブロックの処理
                            val isTeamColorBlock = blockType in listOf(
                                Material.RED_CONCRETE, Material.BLUE_CONCRETE,
                                Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS,
                                Material.WHITE_CONCRETE, Material.WHITE_STAINED_GLASS
                            )
                            
                            // 敵チームのブロック破壊には自陣ブロックの隣接が必要
                            if (isTeamColorBlock) {
                                val isEnemyBlock = when (blockType) {
                                    Material.RED_CONCRETE, Material.RED_STAINED_GLASS -> playerTeam != Team.RED
                                    Material.BLUE_CONCRETE, Material.BLUE_STAINED_GLASS -> playerTeam != Team.BLUE
                                    Material.WHITE_CONCRETE, Material.WHITE_STAINED_GLASS -> false // 白は中立なので破壊可能
                                    else -> false
                                }
                                
                                if (isEnemyBlock) {
                                    // 自チームのリスポーン地点またはビーコン周辺7ブロック以内なら制限なしで破壊可能
                                    val blockLocation = event.block.location
                                    var canBreakWithoutRestriction = false
                                    
                                    // 自チームの旗（ビーコン）からの距離をチェック
                                    val teamFlagLocation = when (playerTeam) {
                                        Team.RED -> game.getRedFlagLocation()
                                        Team.BLUE -> game.getBlueFlagLocation()
                                        else -> null
                                    }
                                    if (teamFlagLocation != null && blockLocation.distance(teamFlagLocation) <= 7.0) {
                                        canBreakWithoutRestriction = true
                                        plugin.logger.info("[BlockBreak] Within 7 blocks of team flag - unrestricted break allowed")
                                    }
                                    
                                    // 自チームのスポーン地点からの距離をチェック
                                    if (!canBreakWithoutRestriction) {
                                        val teamSpawnLocation = when (playerTeam) {
                                            Team.RED -> game.getRedSpawnLocation()
                                            Team.BLUE -> game.getBlueSpawnLocation()
                                            else -> null
                                        }
                                        if (teamSpawnLocation != null && blockLocation.distance(teamSpawnLocation) <= 7.0) {
                                            canBreakWithoutRestriction = true
                                            plugin.logger.info("[BlockBreak] Within 7 blocks of team spawn - unrestricted break allowed")
                                        }
                                    }
                                    
                                    // 制限なしで破壊できない場合は、隣接チェック
                                    if (!canBreakWithoutRestriction) {
                                        // 隣接する自陣ブロックがあるかチェック
                                        if (!game.hasAdjacentTeamBlock(event.block.location, playerTeam!!)) {
                                            event.isCancelled = true
                                            player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.need-adjacent-block"), NamedTextColor.RED))
                                            plugin.logger.info("[BlockBreak] No adjacent team block for breaking enemy block")
                                            return
                                        }
                                    }
                                }
                                
                                // SURVIVALモードなら白いブロックとしてドロップ、CREATIVEモードならドロップしない
                                if (gameMode == GameMode.CREATIVE) {
                                    event.isDropItems = false
                                    plugin.logger.info("[BlockBreak] Creative mode - no drops")
                                } else {
                                    // 通常のドロップをキャンセルして白いブロックをドロップ
                                    event.isDropItems = false
                                    
                                    // 自チームのブロックとしてドロップ
                                    val world = event.block.world
                                    val location = event.block.location.add(0.5, 0.5, 0.5)
                                    val playerTeam = game.getPlayerTeam(player.uniqueId)
                                    val teamBlock = when (blockType) {
                                        Material.RED_CONCRETE, Material.BLUE_CONCRETE -> {
                                            if (playerTeam == Team.RED) Material.RED_CONCRETE else Material.BLUE_CONCRETE
                                        }
                                        Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS -> {
                                            if (playerTeam == Team.RED) Material.RED_STAINED_GLASS else Material.BLUE_STAINED_GLASS
                                        }
                                        Material.WHITE_CONCRETE -> {
                                            if (playerTeam == Team.RED) Material.RED_CONCRETE else Material.BLUE_CONCRETE
                                        }
                                        Material.WHITE_STAINED_GLASS -> {
                                            if (playerTeam == Team.RED) Material.RED_STAINED_GLASS else Material.BLUE_STAINED_GLASS
                                        }
                                        else -> blockType
                                    }
                                    
                                    world.dropItemNaturally(location, ItemStack(teamBlock))
                                    plugin.logger.info("[BlockBreak] Dropping team block: $teamBlock")
                                }
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
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.cannot-break-game-blocks"), NamedTextColor.RED))
                } else {
                    val blockType = event.block.type
                    val playerTeam = game.getPlayerTeam(player.uniqueId)
                    
                    // チームカラーブロック（コンクリート、ガラス）と白いブロックの処理
                    val isTeamColorBlock = blockType in listOf(
                        Material.RED_CONCRETE, Material.BLUE_CONCRETE,
                        Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS,
                        Material.WHITE_CONCRETE, Material.WHITE_STAINED_GLASS
                    )
                    
                    // 戦闘フェーズでも敵チームのブロック破壊には自陣ブロックの隣接が必要
                    if (isTeamColorBlock) {
                        val isEnemyBlock = when (blockType) {
                            Material.RED_CONCRETE, Material.RED_STAINED_GLASS -> playerTeam != Team.RED
                            Material.BLUE_CONCRETE, Material.BLUE_STAINED_GLASS -> playerTeam != Team.BLUE
                            Material.WHITE_CONCRETE, Material.WHITE_STAINED_GLASS -> false // 白は中立なので破壊可能
                            else -> false
                        }
                        
                        if (isEnemyBlock) {
                            // 自チームのリスポーン地点またはビーコン周辺7ブロック以内なら制限なしで破壊可能
                            val blockLocation = event.block.location
                            var canBreakWithoutRestriction = false
                            
                            // 自チームの旗（ビーコン）からの距離をチェック
                            val teamFlagLocation = when (playerTeam) {
                                Team.RED -> game.getRedFlagLocation()
                                Team.BLUE -> game.getBlueFlagLocation()
                                else -> null
                            }
                            if (teamFlagLocation != null && blockLocation.distance(teamFlagLocation) <= 7.0) {
                                canBreakWithoutRestriction = true
                                plugin.logger.info("[BlockBreak] Within 7 blocks of team flag - unrestricted break allowed")
                            }
                            
                            // 自チームのスポーン地点からの距離をチェック
                            if (!canBreakWithoutRestriction) {
                                val teamSpawnLocation = when (playerTeam) {
                                    Team.RED -> game.getRedSpawnLocation()
                                    Team.BLUE -> game.getBlueSpawnLocation()
                                    else -> null
                                }
                                if (teamSpawnLocation != null && blockLocation.distance(teamSpawnLocation) <= 7.0) {
                                    canBreakWithoutRestriction = true
                                    plugin.logger.info("[BlockBreak] Within 7 blocks of team spawn - unrestricted break allowed")
                                }
                            }
                            
                            // 制限なしで破壊できない場合は、隣接チェック
                            if (!canBreakWithoutRestriction) {
                                // 隣接する自陣ブロックがあるかチェック
                                if (!game.hasAdjacentTeamBlock(event.block.location, playerTeam!!)) {
                                    event.isCancelled = true
                                    player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.need-adjacent-block"), NamedTextColor.RED))
                                    plugin.logger.info("[BlockBreak] No adjacent team block for breaking enemy block in combat phase")
                                    return
                                }
                            }
                        }
                        // 通常のドロップをキャンセルして白いブロックをドロップ
                        event.isDropItems = false
                        
                        // 自チームのブロックとしてドロップ
                        val world = event.block.world
                        val location = event.block.location.add(0.5, 0.5, 0.5)
                        val playerTeam = game.getPlayerTeam(player.uniqueId)
                        val teamBlock = when (blockType) {
                            Material.RED_CONCRETE, Material.BLUE_CONCRETE -> {
                                if (playerTeam == Team.RED) Material.RED_CONCRETE else Material.BLUE_CONCRETE
                            }
                            Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS -> {
                                if (playerTeam == Team.RED) Material.RED_STAINED_GLASS else Material.BLUE_STAINED_GLASS
                            }
                            Material.WHITE_CONCRETE -> {
                                if (playerTeam == Team.RED) Material.RED_CONCRETE else Material.BLUE_CONCRETE
                            }
                            Material.WHITE_STAINED_GLASS -> {
                                if (playerTeam == Team.RED) Material.RED_STAINED_GLASS else Material.BLUE_STAINED_GLASS
                            }
                            else -> blockType
                        }
                        
                        world.dropItemNaturally(location, ItemStack(teamBlock))
                        plugin.logger.info("[BlockBreak] Combat phase - dropping team block: $teamBlock")
                    } else {
                        // その他のブロックは通常通りドロップ
                        event.isDropItems = true
                    }
                    
                    // ブロック破壊を記録し、切断されたブロックを白く変換
                    game.recordBlockBreak(event.block.location)
                }
            }

        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        // 観戦者はブロック設置不可
        if (game.getPlayerTeam(player.uniqueId) == Team.SPECTATOR) {
            event.isCancelled = true
            return
        }
        
        // デバッグログ
        plugin.logger.info("[BlockPlace] Player: ${player.name}, GameState: ${game.state}, Phase: ${game.phase}, BuildPhaseGameMode: ${game.buildPhaseGameMode}, PlayerGameMode: ${player.gameMode}")
        
        // ゲーム中でない場合は禁止（STARTINGとRUNNINGの両方を許可）
        if (game.state != GameState.RUNNING && game.state != GameState.STARTING) {
            event.isCancelled = true
            plugin.logger.info("[BlockPlace] Cancelled: Game state is ${game.state}")
            return
        }
        
        // ブロック設置前のアイテム情報を保存
        val itemInHand = event.itemInHand
        
        // ビーコンの上にブロックを置けないようにする
        val blockLocation = event.block.location
        val blockBelow = blockLocation.clone().subtract(0.0, 1.0, 0.0).block
        if (blockBelow.type == Material.BEACON) {
            event.isCancelled = true
            player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.cannot-place-on-beacon"), NamedTextColor.RED))
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
                        player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.cannot-place-adventure"), NamedTextColor.RED))
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
                        
                        // ブロック設置を記録（チームカラーブロックのみツリー構造で記録）
                        val team = game.getPlayerTeam(player.uniqueId)
                        if (team != null && parent != block.location) {
                            // parentがblock.locationと同じ場合は非チームブロックなので記録しない
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
                // 戦闘フェーズ：ブロック設置可能（ただし制限あり）
                val block = event.block
                val parent = game.canPlaceBlock(player, block)
                if (parent == null) {
                    event.isCancelled = true
                    return
                }
                
                // スポーン地点付近（ショップエリア）への設置を制限
                val team = game.getPlayerTeam(player.uniqueId) ?: return
                val enemyTeam = if (team == Team.RED) Team.BLUE else Team.RED
                val enemySpawn = when (enemyTeam) {
                    Team.RED -> game.getRedSpawnLocation() ?: game.getRedFlagLocation()
                    Team.BLUE -> game.getBlueSpawnLocation() ?: game.getBlueFlagLocation()
                    Team.SPECTATOR -> null
                }
                
                // チームカラーブロック（戦略的建築）は敵スポーン近くでも許可
                val isTeamColorBlock = when (team) {
                    Team.RED -> itemInHand.type == Material.RED_CONCRETE || itemInHand.type == Material.RED_STAINED_GLASS
                    Team.BLUE -> itemInHand.type == Material.BLUE_CONCRETE || itemInHand.type == Material.BLUE_STAINED_GLASS
                    Team.SPECTATOR -> false
                }
                
                // 設定ファイルから敵スポーン保護半径を取得（デフォルト: 5ブロック）
                val protectionRadius = plugin.config.getDouble("mechanics.enemy-spawn-protection-radius", 5.0)
                
                if (!isTeamColorBlock && enemySpawn != null && block.location.world == enemySpawn.world && block.location.distance(enemySpawn) < protectionRadius) {
                    event.isCancelled = true
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.cannot-place-near-spawn"), NamedTextColor.RED))
                    return
                }
                
                // 明示的に許可
                event.isCancelled = false
                plugin.logger.info("[BlockPlace] Allowing block placement in combat phase at ${block.location}")
                
                // ブロック設置を記録（チームカラーブロックのみツリー構造で記録）
                if (parent != block.location) {
                    // parentがblock.locationと同じ場合は非チームブロックなので記録しない
                    game.recordBlockPlacement(team, block.location, parent)
                }
                
                // ブロック設置統計を記録
                game.playerBlocksPlaced[player.uniqueId] = (game.playerBlocksPlaced[player.uniqueId] ?: 0) + 1
            }

        }
        
        // 無限ブロックシステムは削除されました
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
                    val isRedFlag = game.getRedFlagLocation()?.let { 
                        it.world == blockLoc.world && it.distance(blockLoc) < 1.0 
                    } ?: false
                    val isBlueFlag = game.getBlueFlagLocation()?.let { 
                        it.world == blockLoc.world && it.distance(blockLoc) < 1.0 
                    } ?: false
                    
                    if (isRedFlag || isBlueFlag) {
                        val flagTeam = if (isRedFlag) Team.RED else Team.BLUE
                        player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.flag-info",
                            "team" to plugin.languageManager.getMessage("teams.${flagTeam.name.lowercase()}")), flagTeam.color))
                    }
                }
            }
        }
        
        // ショップアイテムの処理
        if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
            val item = event.item ?: return
            
            // 観戦者アイテムのチェック
            val spectatorItemType = item.itemMeta?.persistentDataContainer?.get(
                NamespacedKey(plugin, "spectator_item"),
                PersistentDataType.STRING
            )
            
            if (spectatorItemType != null) {
                event.isCancelled = true
                
                when (spectatorItemType) {
                    "stats" -> showStatsMenu(player, game)
                    "track" -> showTrackingMenu(player, game)
                    "flags" -> showFlagMenu(player, game)
                    "spawn_red" -> {
                        val spawn = game.getRandomSpawnLocation(Team.RED)
                        if (spawn != null) {
                            player.teleport(spawn.clone().add(0.0, 5.0, 0.0))
                            player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.red_spawn"), NamedTextColor.RED))
                        }
                    }
                    "spawn_blue" -> {
                        val spawn = game.getRandomSpawnLocation(Team.BLUE)
                        if (spawn != null) {
                            player.teleport(spawn.clone().add(0.0, 5.0, 0.0))
                            player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.blue_spawn"), NamedTextColor.BLUE))
                        }
                    }
                }
                return
            }
            
            // ショップアイテムのチェック
            if (plugin.shopManager.isShopItem(item)) {
                    event.isCancelled = true
                    
                    val team = game.getPlayerTeam(player.uniqueId)
                    if (team == null) {
                        player.sendMessage(Component.text(plugin.languageManager.getMessage("shop.team-not-found"), NamedTextColor.RED))
                        return
                    }
                    
                    // マッチがなくてもゲームがあればショップを使える
                    
                    // フェーズチェック
                    if (game.phase != GamePhase.BUILD && game.phase != GamePhase.COMBAT) {
                        player.sendMessage(Component.text(plugin.languageManager.getMessage("shop.shop-only-during-game"), NamedTextColor.RED))
                        return
                    }
                    
                    // 距離制限を削除し、直接カテゴリーメニューを開く
                    shopManager.openShop(player, game)
                }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val inventory = event.inventory
        val title = event.view.title()
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        val lang = plugin.languageManager
        
        // ショップまたはカテゴリーメニューが開いているかチェック
        val isShopUI = titleText.contains(lang.getMessage("shop.title")) || 
                        titleText.contains("Shop") ||
                        titleText.contains(lang.getMessage("shop.category_menu.title")) ||
                        titleText.contains("ショップ") ||
                        titleText.contains("カテゴリー選択")
        
        if (isShopUI) {
            // すべてのクリックをまずキャンセル
            event.isCancelled = true
            
            // プレイヤーインベントリのクリックは無視
            if (event.rawSlot >= inventory.size) {
                return
            }
            
            // クリックしたアイテムを取得
            val clickedItem = event.currentItem
            if (clickedItem == null || clickedItem.type == Material.AIR) {
                return
            }
            
            // 左クリックのみ処理
            if (event.click != org.bukkit.event.inventory.ClickType.LEFT) {
                return
            }
            
            val game = gameManager.getPlayerGame(player)
            val team = game?.getPlayerTeam(player.uniqueId)
            
            if (game == null || team == null) {
                player.closeInventory()
                return
            }
            
            // カテゴリー選択画面の処理
            if (titleText.contains(lang.getMessage("shop.category_menu.title")) || 
                titleText.contains("カテゴリー選択")) {
                val meta = clickedItem.itemMeta ?: return
                val displayNameComponent = meta.displayName() ?: return
                val displayName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayNameComponent)
                
                val page = when {
                    displayName.contains(lang.getMessage("shop.category_menu.weapons.title")) || 
                    displayName.contains("武器") -> 0
                    displayName.contains(lang.getMessage("shop.category_menu.consumables.title")) || 
                    displayName.contains("消耗品") -> 1
                    displayName.contains(lang.getMessage("shop.category_menu.blocks.title")) || 
                    displayName.contains("ブロック") -> 2
                    else -> return
                }
                
                shopManager.handleInventoryClick(event, game)
                return
            }
            
            // ショップアイテムのクリック処理
            val meta = clickedItem.itemMeta ?: return
            val displayNameComponent = meta.displayName()
            if (displayNameComponent == null) {
                return
            }
            
            val displayName = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(displayNameComponent)
            
            // すべてのショップ関連処理をShopManagerに委譲
            shopManager.handleInventoryClick(event, game)
            return
        }
        
        // ゲームに参加していない場合はここで終了
        val game = gameManager.getPlayerGame(player) ?: return
        
        // 観戦者メニューの処理
        val clickedItem = event.currentItem
        if (clickedItem != null) {
            // プレイヤー追跡
            val trackPlayerId = clickedItem.itemMeta?.persistentDataContainer?.get(
                NamespacedKey(plugin, "track_player"),
                PersistentDataType.STRING
            )
            if (trackPlayerId != null) {
                event.isCancelled = true
                val targetUuid = java.util.UUID.fromString(trackPlayerId)
                val target = Bukkit.getPlayer(targetUuid)
                if (target != null) {
                    player.teleport(target)
                    player.closeInventory()
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.to_player")
                        .replace("{player}", target.name), NamedTextColor.GREEN))
                }
                return
            }
            
            // 旗テレポート
            val flagTeleport = clickedItem.itemMeta?.persistentDataContainer?.get(
                NamespacedKey(plugin, "flag_teleport"),
                PersistentDataType.STRING
            )
            if (flagTeleport != null) {
                event.isCancelled = true
                when (flagTeleport) {
                    "red" -> {
                        when {
                            game.redFlagCarrier != null -> {
                                val carrier = Bukkit.getPlayer(game.redFlagCarrier!!)
                                if (carrier != null) {
                                    player.teleport(carrier)
                                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.red_flag_carrier"), NamedTextColor.RED))
                                }
                            }
                            game.isRedFlagDropped -> {
                                // ドロップされた旗の位置にテレポート
                                game.getRedFlagLocation()?.let { loc ->
                                    player.teleport(loc)
                                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.dropped_red_flag"), NamedTextColor.RED))
                                }
                            }
                            else -> {
                                game.getRedFlagLocation()?.let { loc ->
                                    player.teleport(loc)
                                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.red_flag_base"), NamedTextColor.RED))
                                }
                            }
                        }
                    }
                    "blue" -> {
                        when {
                            game.blueFlagCarrier != null -> {
                                val carrier = Bukkit.getPlayer(game.blueFlagCarrier!!)
                                if (carrier != null) {
                                    player.teleport(carrier)
                                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.blue_flag_carrier"), NamedTextColor.BLUE))
                                }
                            }
                            game.isBlueFlagDropped -> {
                                // ドロップされた旗の位置にテレポート
                                game.getBlueFlagLocation()?.let { loc ->
                                    player.teleport(loc)
                                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.dropped_blue_flag"), NamedTextColor.BLUE))
                                }
                            }
                            else -> {
                                game.getBlueFlagLocation()?.let { loc ->
                                    player.teleport(loc)
                                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game_events.teleport.blue_flag_base"), NamedTextColor.BLUE))
                                }
                            }
                        }
                    }
                }
                player.closeInventory()
                return
            }
        }
        
        // ショップアイテム（エメラルド）の移動制限
        if (game.state == GameState.RUNNING) {
            val hotbarSlot = event.hotbarButton
            
            // スロット8（9番目）のアイテムをクリックした場合
            if (event.slot == 8 && clickedItem != null && shopManager.isShopItem(clickedItem)) {
                event.isCancelled = true
                return
            }
            
            // スロット8へアイテムを移動しようとした場合
            if (event.slot == 8 && event.cursor != null) {
                event.isCancelled = true
                return
            }
            
            // 数字キーでスロット8のアイテムを移動しようとした場合
            if (hotbarSlot == 8 && event.clickedInventory == player.inventory) {
                event.isCancelled = true
                return
            }
            
            // Shift+クリックでショップアイテムを移動しようとした場合
            if (event.isShiftClick && clickedItem != null && shopManager.isShopItem(clickedItem)) {
                event.isCancelled = true
                return
            }
        }
        
        // 防具の装備制限
        if (game.state == GameState.RUNNING) {
            val cursor = event.cursor
            
            // 防具スロットへの装備チェック
            if (event.slot in 36..39) { // 防具スロット
                if (clickedItem != null && isArmor(clickedItem.type) && !clickedItem.type.name.startsWith("LEATHER_")) {
                    event.isCancelled = true
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("armor.only-leather-allowed"), NamedTextColor.RED))
                    return
                }
                if (cursor != null && isArmor(cursor.type) && !cursor.type.name.startsWith("LEATHER_")) {
                    event.isCancelled = true
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("armor.only-leather-allowed"), NamedTextColor.RED))
                    return
                }
            }
            
            // Shift+クリックでの防具装備チェック
            if (event.isShiftClick && clickedItem != null && isArmor(clickedItem.type) && !clickedItem.type.name.startsWith("LEATHER_")) {
                event.isCancelled = true
                player.sendMessage(Component.text(plugin.languageManager.getMessage("armor.only-leather-allowed"), NamedTextColor.RED))
                return
            }
            
            // 防具の取り外し禁止（戦闘フェーズのみ）
            if (game.phase == GamePhase.COMBAT) {
                val item = event.currentItem
                if (item != null && isArmor(item.type)) {
                    event.isCancelled = true
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("armor.cannot-remove-combat"), NamedTextColor.RED))
                    return
                }
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val title = event.view.title()
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        val lang = plugin.languageManager
        
        // ショップUIでのドラッグを完全に防止
        val isShopUI = titleText.contains(lang.getMessage("shop.title")) || 
                        titleText.contains("Shop") ||
                        titleText.contains(lang.getMessage("shop.category_menu.title")) ||
                        titleText.contains("ショップ") ||
                        titleText.contains("カテゴリー選択")
        
        if (isShopUI) {
            event.isCancelled = true
            event.result = Event.Result.DENY
            return
        }
        
        val game = gameManager.getPlayerGame(player) ?: return
        
        // ショップアイテムのドラッグ制限
        if (game.state == GameState.RUNNING) {
            // スロット8が含まれている場合はキャンセル
            if (event.rawSlots.contains(8) || event.inventorySlots.contains(8)) {
                event.isCancelled = true
                return
            }
            
            // ドラッグしているアイテムがショップアイテムの場合はキャンセル
            if (shopManager.isShopItem(event.oldCursor)) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        // ショップインベントリへの/からのアイテム移動を防止
        // ホッパーやドロッパーなどによる自動移動も防ぐ
        val source = event.source
        val destination = event.destination
        
        // インベントリのホルダーがnullの場合はカスタムGUIの可能性
        if (source.holder == null || destination.holder == null) {
            event.isCancelled = true
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryCreative(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        
        // ショップUIでのクリエイティブアイテム生成を防止
        val title = event.view.title()
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        val lang = plugin.languageManager
        
        if (event.inventory.holder == null && (
            titleText.contains(lang.getMessage("shop.title")) || 
            titleText.contains("Shop") ||
            titleText.contains(lang.getMessage("shop.category_menu.title"))
        )) {
            event.isCancelled = true
            event.result = Event.Result.DENY
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryClickFirst(event: InventoryClickEvent) {
        // 最初にショップUIかどうかをチェック（最高優先度で処理）
        val title = event.view.title()
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        val lang = plugin.languageManager
        
        val isShopUI = titleText.contains(lang.getMessage("shop.title")) || 
                        titleText.contains("Shop") ||
                        titleText.contains(lang.getMessage("shop.category_menu.title")) ||
                        titleText.contains("ショップ") ||
                        titleText.contains("カテゴリー選択")
        
        if (isShopUI) {
            // ショップUIの場合は即座にキャンセル
            event.isCancelled = true
            event.result = Event.Result.DENY
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClickMonitor(event: InventoryClickEvent) {
        // 最後にもう一度チェック（確実にキャンセル）
        if (event.isCancelled) return
        
        val title = event.view.title()
        val titleText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
        val lang = plugin.languageManager
        
        val isShopUI = titleText.contains(lang.getMessage("shop.title")) || 
                        titleText.contains("Shop") ||
                        titleText.contains(lang.getMessage("shop.category_menu.title")) ||
                        titleText.contains("ショップ") ||
                        titleText.contains("カテゴリー選択")
        
        if (isShopUI) {
            // 何らかの理由でキャンセルされていない場合、強制的にキャンセル
            event.isCancelled = true
            event.result = Event.Result.DENY
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
    
    private fun showStatsMenu(player: Player, game: Game) {
        val lang = plugin.languageManager
        val inventory = Bukkit.createInventory(null, 54, Component.text(lang.getMessage("game_events.spectator_menu.stats_title"), NamedTextColor.GOLD))
        
        // チーム別統計
        var slot = 0
        
        // 赤チーム
        val redPlayers = game.getAllPlayers().filter { p -> game.getPlayerTeam(p.uniqueId) == Team.RED }
        redPlayers.sortedByDescending { p -> game.playerKills[p.uniqueId] ?: 0 }.forEach { p ->
            val kills = game.playerKills[p.uniqueId] ?: 0
            val deaths = game.playerDeaths[p.uniqueId] ?: 0
            val captures = game.playerCaptures[p.uniqueId] ?: 0
            
            val skull = ItemStack(Material.PLAYER_HEAD).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text(p.name, NamedTextColor.RED))
                    lore(listOf(
                        Component.text(lang.getMessage("game_events.spectator_menu.kills").replace("{count}", kills.toString()), NamedTextColor.WHITE),
                        Component.text(lang.getMessage("game_events.spectator_menu.deaths").replace("{count}", deaths.toString()), NamedTextColor.WHITE),
                        Component.text(lang.getMessage("game_events.spectator_menu.captures").replace("{count}", captures.toString()), NamedTextColor.WHITE),
                        Component.text(lang.getMessage("game_events.spectator_menu.kd_ratio").replace("{ratio}", if (deaths == 0) kills.toDouble().toString() else String.format("%.2f", kills.toDouble() / deaths)), NamedTextColor.YELLOW)
                    ))
                    val skullMeta = this as? org.bukkit.inventory.meta.SkullMeta
                    skullMeta?.owningPlayer = p
                }
            }
            inventory.setItem(slot++, skull)
        }
        
        // 区切り
        slot = 27
        
        // 青チーム
        val bluePlayers = game.getAllPlayers().filter { p -> game.getPlayerTeam(p.uniqueId) == Team.BLUE }
        bluePlayers.sortedByDescending { p -> game.playerKills[p.uniqueId] ?: 0 }.forEach { p ->
            val kills = game.playerKills[p.uniqueId] ?: 0
            val deaths = game.playerDeaths[p.uniqueId] ?: 0
            val captures = game.playerCaptures[p.uniqueId] ?: 0
            
            val skull = ItemStack(Material.PLAYER_HEAD).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text(p.name, NamedTextColor.BLUE))
                    lore(listOf(
                        Component.text(lang.getMessage("game_events.spectator_menu.kills").replace("{count}", kills.toString()), NamedTextColor.WHITE),
                        Component.text(lang.getMessage("game_events.spectator_menu.deaths").replace("{count}", deaths.toString()), NamedTextColor.WHITE),
                        Component.text(lang.getMessage("game_events.spectator_menu.captures").replace("{count}", captures.toString()), NamedTextColor.WHITE),
                        Component.text(lang.getMessage("game_events.spectator_menu.kd_ratio").replace("{ratio}", if (deaths == 0) kills.toDouble().toString() else String.format("%.2f", kills.toDouble() / deaths)), NamedTextColor.YELLOW)
                    ))
                    val skullMeta = this as? org.bukkit.inventory.meta.SkullMeta
                    skullMeta?.owningPlayer = p
                }
            }
            inventory.setItem(slot++, skull)
        }
        
        player.openInventory(inventory)
    }
    
    private fun showTrackingMenu(player: Player, game: Game) {
        val lang = plugin.languageManager
        val inventory = Bukkit.createInventory(null, 27, Component.text(lang.getMessage("game_events.spectator_menu.tracking_title"), NamedTextColor.AQUA))
        
        var slot = 0
        game.getAllPlayers().filter { p -> p != player && game.getPlayerTeam(p.uniqueId) != Team.SPECTATOR }.forEach { p ->
            val team = game.getPlayerTeam(p.uniqueId) ?: return@forEach
            val teamColor = if (team == Team.RED) NamedTextColor.RED else NamedTextColor.BLUE
            
            val skull = ItemStack(Material.PLAYER_HEAD).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text(p.name, teamColor))
                    lore(listOf(
                        Component.text(lang.getMessage("game_events.spectator_menu.click_teleport"), NamedTextColor.GRAY),
                        Component.text(lang.getMessage("game_events.spectator_menu.team").replace("{team}", lang.getMessage("teams.${team.name.lowercase()}")), NamedTextColor.WHITE)
                    ))
                    val skullMeta = this as? org.bukkit.inventory.meta.SkullMeta
                    skullMeta?.owningPlayer = p
                    persistentDataContainer.set(
                        NamespacedKey(plugin, "track_player"),
                        PersistentDataType.STRING,
                        p.uniqueId.toString()
                    )
                }
            }
            inventory.setItem(slot++, skull)
        }
        
        player.openInventory(inventory)
    }
    
    private fun showFlagMenu(player: Player, game: Game) {
        val lang = plugin.languageManager
        val inventory = Bukkit.createInventory(null, 9, Component.text(lang.getMessage("game_events.spectator_menu.flag_title"), NamedTextColor.YELLOW))
        
        // 赤旗
        val redFlagItem = ItemStack(Material.RED_BANNER).apply {
            itemMeta = itemMeta?.apply {
                displayName(Component.text(lang.getMessage("spectator.red-flag"), NamedTextColor.RED))
                val status = when {
                    game.redFlagCarrier != null -> {
                        val carrier = Bukkit.getPlayer(game.redFlagCarrier!!)
                        listOf(
                            Component.text(lang.getMessage("game_events.spectator_menu.flag_status.carried"), NamedTextColor.YELLOW),
                            Component.text(lang.getMessage("game_events.spectator_menu.flag_status.carrier")
                                .replace("{player}", carrier?.name ?: "不明"), NamedTextColor.WHITE)
                        )
                    }
                    game.isRedFlagDropped -> listOf(Component.text(lang.getMessage("game_events.spectator_menu.flag_status.dropped"), NamedTextColor.GRAY))
                    else -> listOf(Component.text(lang.getMessage("game_events.spectator_menu.flag_status.home"), NamedTextColor.GREEN))
                }
                lore(status + Component.text(lang.getMessage("game_events.spectator_menu.click_teleport"), NamedTextColor.GRAY))
                persistentDataContainer.set(
                    NamespacedKey(plugin, "flag_teleport"),
                    PersistentDataType.STRING,
                    "red"
                )
            }
        }
        
        // 青旗
        val blueFlagItem = ItemStack(Material.BLUE_BANNER).apply {
            itemMeta = itemMeta?.apply {
                displayName(Component.text(lang.getMessage("spectator.blue-flag"), NamedTextColor.BLUE))
                val status = when {
                    game.blueFlagCarrier != null -> {
                        val carrier = Bukkit.getPlayer(game.blueFlagCarrier!!)
                        listOf(
                            Component.text(lang.getMessage("game_events.spectator_menu.flag_status.carried"), NamedTextColor.YELLOW),
                            Component.text(lang.getMessage("game_events.spectator_menu.flag_status.carrier")
                                .replace("{player}", carrier?.name ?: "不明"), NamedTextColor.WHITE)
                        )
                    }
                    game.isBlueFlagDropped -> listOf(Component.text(lang.getMessage("game_events.spectator_menu.flag_status.dropped"), NamedTextColor.GRAY))
                    else -> listOf(Component.text(lang.getMessage("game_events.spectator_menu.flag_status.home"), NamedTextColor.GREEN))
                }
                lore(status + Component.text(lang.getMessage("game_events.spectator_menu.click_teleport"), NamedTextColor.GRAY))
                persistentDataContainer.set(
                    NamespacedKey(plugin, "flag_teleport"),
                    PersistentDataType.STRING,
                    "blue"
                )
            }
        }
        
        inventory.setItem(2, redFlagItem)
        inventory.setItem(6, blueFlagItem)
        
        player.openInventory(inventory)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            // 戦闘フェーズ中の死亡リスポーン
            // チームのスポーン地点へ即座にリスポーン
            val team = game.getPlayerTeam(player.uniqueId) ?: return
            val spawnLocation = when (team) {
                Team.RED -> game.getRedSpawnLocation()
                Team.BLUE -> game.getBlueSpawnLocation()
                Team.SPECTATOR -> game.getCenterLocation()
            }
            if (spawnLocation != null) {
                event.respawnLocation = spawnLocation
            }
            
            // 即座にサバイバルモードで復活
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                game.handleRespawn(player)
                player.gameMode = GameMode.SURVIVAL
                // アイテムはkeepInventory=trueで保持されているので再配布不要
            }, 1L)
        } else if (game.state == GameState.RUNNING && game.phase == GamePhase.BUILD) {
            // 建築フェーズ中のリスポーン
            val team = game.getPlayerTeam(player.uniqueId) ?: return
            val spawnLocation = when (team) {
                Team.RED -> game.getRedSpawnLocation()
                Team.BLUE -> game.getBlueSpawnLocation()
                Team.SPECTATOR -> game.getCenterLocation()
            }
            if (spawnLocation != null) {
                event.respawnLocation = spawnLocation
            }
            
            // 建築フェーズのゲームモードと飛行許可を再設定
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val buildPhaseGameMode = game.buildPhaseGameMode
                player.gameMode = GameMode.valueOf(buildPhaseGameMode)
                player.allowFlight = true
                player.isFlying = false
                
                // 建築フェーズのアイテムを再付与
                game.giveBuildPhaseItems(player, team)
            }, 1L)
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
                    // Check persistent data for team information
                    val itemMeta = (entity as? org.bukkit.entity.Item)?.itemStack?.itemMeta
                    val container = itemMeta?.persistentDataContainer
                    val teamName = container?.get(org.bukkit.NamespacedKey(plugin, "flag_team"), org.bukkit.persistence.PersistentDataType.STRING)
                    val team = when (teamName) {
                        "red" -> Team.RED
                        "blue" -> Team.BLUE
                        else -> if (nameText.contains(plugin.languageManager.getMessage("teams.red"))) Team.RED else Team.BLUE
                    }
                    
                    // 該当するゲームを探す
                    gameManager.getAllGames().values.forEach { game ->
                        if (game.state == GameState.RUNNING) {
                            val flagLocation = if (team == Team.RED) game.getRedFlagLocation() else game.getBlueFlagLocation()
                            if (flagLocation != null) {
                                game.setupFlagBeacon(flagLocation, team)
                                game.getAllPlayers().forEach { player ->
                                    player.sendMessage(Component.text(plugin.languageManager.getMessage("flag.returned-damage",
                                        "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}"),
                                        "cause" to getDamageCauseMessage(event.cause)), team.color))
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
            // Check persistent data for team information
            val itemMeta = item.itemMeta
            val container = itemMeta?.persistentDataContainer
            val teamName = container?.get(org.bukkit.NamespacedKey(plugin, "flag_team"), org.bukkit.persistence.PersistentDataType.STRING)
            val team = when (teamName) {
                "red" -> Team.RED
                "blue" -> Team.BLUE
                else -> if (nameText.contains(plugin.languageManager.getMessage("teams.red"))) Team.RED else Team.BLUE
            }
            
            // 該当するゲームを探す
            gameManager.getAllGames().values.forEach { game ->
                if (game.state == GameState.RUNNING) {
                    val flagLocation = if (team == Team.RED) game.getRedFlagLocation() else game.getBlueFlagLocation()
                    if (flagLocation != null) {
                        game.setupFlagBeacon(flagLocation, team)
                        game.getAllPlayers().forEach { player ->
                            player.sendMessage(Component.text(plugin.languageManager.getMessage("flag.returned-fire",
                                "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")), team.color))
                        }
                        plugin.logger.info("[Flag] Flag returned due to combustion")
                    }
                }
            }
        }
    }
    
    private fun getDamageCauseMessage(cause: EntityDamageEvent.DamageCause): String {
        return when (cause) {
            EntityDamageEvent.DamageCause.LAVA -> plugin.languageManager.getMessage("death.cause-lava")
            EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK -> plugin.languageManager.getMessage("death.cause-fire")
            EntityDamageEvent.DamageCause.VOID -> plugin.languageManager.getMessage("death.cause-void")
            else -> plugin.languageManager.getMessage("death.cause-damage")
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
                player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.clear-command-disabled"), NamedTextColor.RED))
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
                        player.sendMessage(Component.text(plugin.languageManager.getMessage("gameplay.shop-item-redistributed"), NamedTextColor.YELLOW))
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