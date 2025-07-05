package com.hacklab.ctf.listeners

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManagerNew
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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
import org.bukkit.event.player.*
import org.bukkit.event.block.Action
import org.bukkit.inventory.ItemStack

class GameListenerNew(private val plugin: Main) : Listener {
    
    private val gameManager = plugin.gameManager as GameManagerNew

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        gameManager.handlePlayerReconnect(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        gameManager.handlePlayerDisconnect(event.player)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            val item = event.currentItem
            if (item != null && isArmor(item.type)) {
                event.isCancelled = true
                player.sendMessage(Component.text("戦闘中は防具を外せません！", NamedTextColor.RED))
            }
        }
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            val item = event.itemDrop.itemStack
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
        
        if (game.state != GameState.RUNNING || game.phase != GamePhase.COMBAT) return
        
        val playerTeam = game.getPlayerTeam(player.uniqueId) ?: return
        val playerLoc = player.location
        
        // 敵の旗の位置をチェック
        val enemyTeam = if (playerTeam == Team.RED) Team.BLUE else Team.RED
        val enemyFlagLocation = when (enemyTeam) {
            Team.RED -> game.redFlagLocation
            Team.BLUE -> game.blueFlagLocation
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
                
                // メッセージ
                game.getAllPlayers().forEach {
                    it.sendMessage(Component.text("${player.name}が${enemyTeam.displayName}の旗を取得しました！", NamedTextColor.YELLOW))
                }
                
                // 効果音
                player.playSound(player.location, org.bukkit.Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.0f)
            }
        }
        
        // 自陣の旗位置で敵の旗を持っている場合、キャプチャー
        val ownFlagLocation = when (playerTeam) {
            Team.RED -> game.redFlagLocation
            Team.BLUE -> game.blueFlagLocation
        } ?: return
        
        if (playerLoc.distance(ownFlagLocation) < 3.0) {
            game.captureFlag(player)
        }
    }

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state != GameState.RUNNING || game.phase != GamePhase.COMBAT) return
        
        game.pickupFlag(player, event.item)
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            event.keepInventory = true
            event.drops.clear()
            event.droppedExp = 0
            
            // 旗を持っていた場合ドロップ
            val team = game.getPlayerTeam(player.uniqueId) ?: return
            if (player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier) {
                val flagTeam = if (player.uniqueId == game.redFlagCarrier) Team.RED else Team.BLUE
                game.dropFlag(player, flagTeam)
            }
            
            // リスポーン処理
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                player.spigot().respawn()
                game.handleRespawn(player)
            }, 1L)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager as? Player ?: return
        
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
            }
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        // ゲーム中でない場合は禁止
        if (game.state != GameState.RUNNING) {
            event.isCancelled = true
            return
        }
        
        when (game.phase) {
            GamePhase.BUILD -> {
                // 建築フェーズ：ゲームモードに応じた制限
                when (game.buildPhaseGameMode) {
                    org.bukkit.GameMode.ADVENTURE -> {
                        event.isCancelled = true
                        player.sendMessage(Component.text("アドベンチャーモードではブロックを破壊できません", NamedTextColor.RED))
                    }
                    org.bukkit.GameMode.SURVIVAL, org.bukkit.GameMode.CREATIVE -> {
                        // 旗とスポーン装飾は破壊不可
                        if (isProtectedBlock(game, event.block.location)) {
                            event.isCancelled = true
                            player.sendMessage(Component.text("ゲーム用のブロックは破壊できません", NamedTextColor.RED))
                        }
                    }
                    org.bukkit.GameMode.SPECTATOR -> {
                        event.isCancelled = true
                    }
                }
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズ：全面的に破壊禁止
                event.isCancelled = true
                player.sendMessage(Component.text("戦闘フェーズ中はブロックを破壊できません", NamedTextColor.RED))
            }
            GamePhase.RESULT -> {
                // リザルトフェーズ：全面的に破壊禁止
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = gameManager.getPlayerGame(player) ?: return
        
        // ゲーム中でない場合は禁止
        if (game.state != GameState.RUNNING) {
            event.isCancelled = true
            return
        }
        
        when (game.phase) {
            GamePhase.BUILD -> {
                // 建築フェーズ：ゲームモードに応じた制限
                when (game.buildPhaseGameMode) {
                    org.bukkit.GameMode.ADVENTURE -> {
                        event.isCancelled = true
                        player.sendMessage(Component.text("アドベンチャーモードではブロックを設置できません", NamedTextColor.RED))
                    }
                    org.bukkit.GameMode.SURVIVAL, org.bukkit.GameMode.CREATIVE -> {
                        // 設置は許可
                    }
                    org.bukkit.GameMode.SPECTATOR -> {
                        event.isCancelled = true
                    }
                }
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズ：全面的に設置禁止
                event.isCancelled = true
                player.sendMessage(Component.text("戦闘フェーズ中はブロックを設置できません", NamedTextColor.RED))
            }
            GamePhase.RESULT -> {
                // リザルトフェーズ：全面的に設置禁止
                event.isCancelled = true
            }
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
                    val isRedFlag = game.redFlagLocation?.let { it.distance(blockLoc) < 1.0 } ?: false
                    val isBlueFlag = game.blueFlagLocation?.let { it.distance(blockLoc) < 1.0 } ?: false
                    
                    if (isRedFlag || isBlueFlag) {
                        val flagTeam = if (isRedFlag) Team.RED else Team.BLUE
                        player.sendMessage(Component.text("${flagTeam.displayName}の旗です。近づくと取得できます", flagTeam.color))
                    }
                }
            }
        }
    }

    private fun isArmor(material: Material): Boolean {
        val name = material.name
        return name.endsWith("_HELMET") || 
               name.endsWith("_CHESTPLATE") || 
               name.endsWith("_LEGGINGS") || 
               name.endsWith("_BOOTS")
    }

    private fun isProtectedBlock(game: com.hacklab.ctf.Game, location: org.bukkit.Location): Boolean {
        // 旗の位置チェック
        val flagLocations = listOf(game.redFlagLocation, game.blueFlagLocation).filterNotNull()
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
        }
        
        // スポーン装飾チェック（スポーン地点が設定されている場合のみ）
        val spawnLocations = listOf(game.redSpawnLocation, game.blueSpawnLocation).filterNotNull()
        
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
        }
        
        return false
    }
}