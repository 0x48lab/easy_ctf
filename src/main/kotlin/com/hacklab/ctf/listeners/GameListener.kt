package com.hacklab.ctf.listeners

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManager
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.Team
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack

class GameListener(private val plugin: Main) : Listener {
    
    private val gameManager: GameManager = plugin.gameManager

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        
        if (gameManager.getGameState() == GameState.RUNNING && gameManager.getPlayerTeam(player) != null) {
            val item = event.currentItem
            if (item != null && isArmor(item.type)) {
                event.setCancelled(true)
                player.sendMessage("${ChatColor.RED}You cannot remove your armor during the game!")
            }
        }
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (gameManager.getGameState() == GameState.RUNNING && 
            gameManager.getPlayerTeam(event.player) != null) {
            
            val item = event.itemDrop.itemStack
            if (isArmor(item.type)) {
                event.setCancelled(true)
                event.player.sendMessage("${ChatColor.RED}You cannot drop armor during the game!")
            }
        }
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (gameManager.getGameState() != GameState.RUNNING) return
        
        val player = event.player
        val playerTeam = gameManager.getPlayerTeam(player) ?: return
        
        val playerLoc = player.location
        
        Team.values().forEach { flagTeam ->
            val flagLoc = gameManager.getFlagLocation(flagTeam) ?: return@forEach
            
            if (playerLoc.distance(flagLoc) < 2.0) {
                if (flagTeam != playerTeam) {
                    // Enemy flag - try to pick up
                    if (gameManager.getFlagCarrier(flagTeam) == null) {
                        gameManager.pickupFlag(player, flagTeam)
                        flagLoc.block.type = Material.AIR
                    }
                } else {
                    // Own flag base - try to capture enemy flag
                    val enemyTeam = if (flagTeam == Team.RED) Team.BLUE else Team.RED
                    val carrierId = gameManager.getFlagCarrier(enemyTeam)
                    if (carrierId != null && carrierId == player.uniqueId) {
                        gameManager.captureFlag(player, enemyTeam)
                    }
                }
            }
        }
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        
        if (gameManager.getGameState() == GameState.RUNNING && gameManager.getCurrentPhase() == GamePhase.COMBAT) {
            event.setKeepInventory(true)
            event.drops.clear()
            event.setDroppedExp(0)
            
            // キル/デス統計の記録
            val killer = player.killer
            if (killer != null && killer != player) {
                gameManager.recordKill(killer, player)
            } else {
                // 自殺や環境による死亡の場合
                gameManager.recordDeath(player)
            }
            
            gameManager.dropFlag(player)
            
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val team = gameManager.getPlayerTeam(player)
                if (team != null) {
                    val spawn = gameManager.teamSpawns[team]
                    if (spawn != null) {
                        player.spigot().respawn()
                        player.teleport(spawn)
                    }
                }
            }, 1L)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        
        // 少し遅延してチーム情報を表示（プレイヤーの読み込み完了後）
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val (redSize, blueSize) = gameManager.getTeamSizes()
            val maxPlayersPerTeam = plugin.config.getInt("game.max-players-per-team", 10)
            
            player.sendMessage(plugin.languageManager.getTeamsMessage("welcome-title"))
            player.sendMessage(plugin.languageManager.getTeamsMessage("welcome-status"))
            player.sendMessage(plugin.languageManager.getTeamsMessage("red-team-size", 
                "size" to redSize.toString(), 
                "max" to maxPlayersPerTeam.toString()
            ))
            player.sendMessage(plugin.languageManager.getTeamsMessage("blue-team-size", 
                "size" to blueSize.toString(), 
                "max" to maxPlayersPerTeam.toString()
            ))
            player.sendMessage(plugin.languageManager.getTeamsMessage("welcome-instruction"))
        }, 20L) // 1秒後
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        gameManager.leaveTeam(event.player)
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        
        // ゲーム中でない場合はそのまま許可
        if (gameManager.getGameState() != GameState.RUNNING) {
            return
        }
        
        // プレイヤーがチームに所属していない場合は禁止
        if (gameManager.getPlayerTeam(player) == null) {
            event.setCancelled(true)
            return
        }
        
        // スポーン保護エリア内での破壊を禁止
        if (gameManager.isInSpawnProtection(event.block.location)) {
            event.setCancelled(true)
            val protectedTeam = gameManager.getSpawnProtectionTeam(event.block.location)
            if (protectedTeam != null) {
                val teamColor = if (protectedTeam == Team.RED) ChatColor.RED else ChatColor.BLUE
                val teamName = if (protectedTeam == Team.RED) "RED" else "BLUE"
                player.sendMessage("${ChatColor.RED}You cannot break blocks in the ${teamColor}${teamName}${ChatColor.RED} team spawn area!")
            } else {
                player.sendMessage("${ChatColor.RED}You cannot break blocks in spawn protected areas!")
            }
            return
        }
        
        when (gameManager.getCurrentPhase()) {
            GamePhase.BUILD -> {
                // 建築フェーズでは、プレイヤーが配置したブロックのみ破壊可能
                if (gameManager.isPlayerPlacedBlock(event.block.location)) {
                    // プレイヤーが配置したブロックなので破壊を許可し、追跡から削除
                    gameManager.removePlacedBlock(event.block.location)
                } else {
                    // 元からあるブロックなので破壊を禁止
                    event.setCancelled(true)
                    player.sendMessage("${ChatColor.RED}You can only break blocks you placed!")
                }
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズでは破壊を禁止
                event.setCancelled(true)
                player.sendMessage("${ChatColor.RED}You cannot break blocks during combat phase!")
            }
            GamePhase.RESULT -> {
                // リザルトフェーズでは破壊を禁止
                event.setCancelled(true)
                player.sendMessage("${ChatColor.RED}You cannot break blocks during result phase!")
            }
        }
    }

    @EventHandler
    fun onPlayerDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager as? Player ?: return
        
        if (gameManager.getGameState() == GameState.RUNNING) {
            val victimTeam = gameManager.getPlayerTeam(victim)
            val attackerTeam = gameManager.getPlayerTeam(attacker)
            
            if (victimTeam != null && attackerTeam != null && victimTeam == attackerTeam) {
                event.setCancelled(true)
                attacker.sendMessage("${ChatColor.RED}You cannot damage teammates!")
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

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        
        // ゲーム中でない場合はそのまま許可
        if (gameManager.getGameState() != GameState.RUNNING) {
            return
        }
        
        // プレイヤーがチームに所属していない場合は禁止
        if (gameManager.getPlayerTeam(player) == null) {
            event.setCancelled(true)
            return
        }
        
        // スポーン保護エリア内での設置を禁止
        if (gameManager.isInSpawnProtection(event.block.location)) {
            event.setCancelled(true)
            val protectedTeam = gameManager.getSpawnProtectionTeam(event.block.location)
            if (protectedTeam != null) {
                val teamColor = if (protectedTeam == Team.RED) ChatColor.RED else ChatColor.BLUE
                val teamName = if (protectedTeam == Team.RED) "RED" else "BLUE"
                player.sendMessage("${ChatColor.RED}You cannot place blocks in the ${teamColor}${teamName}${ChatColor.RED} team spawn area!")
            } else {
                player.sendMessage("${ChatColor.RED}You cannot place blocks in spawn protected areas!")
            }
            return
        }
        
        when (gameManager.getCurrentPhase()) {
            GamePhase.BUILD -> {
                // 建築フェーズでは配置を許可し、追跡する
                gameManager.addPlacedBlock(event.block.location, player)
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズでは配置を禁止
                event.setCancelled(true)
                player.sendMessage("${ChatColor.RED}You cannot place blocks during combat phase!")
            }
            GamePhase.RESULT -> {
                // リザルトフェーズでは配置を禁止
                event.setCancelled(true)
                player.sendMessage("${ChatColor.RED}You cannot place blocks during result phase!")
            }
        }
    }

}