package com.hacklab.ctf.listeners

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManagerNew
import com.hacklab.ctf.shop.ShopManager
import com.hacklab.ctf.shop.ShopItem
import com.hacklab.ctf.shop.DeathBehavior
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
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

class GameListenerNew(private val plugin: Main) : Listener {
    
    private val gameManager = plugin.gameManager as GameManagerNew
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
        
        game.pickupFlag(player, event.item)
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val game = gameManager.getPlayerGame(player) ?: return
        
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            val team = game.getPlayerTeam(player.uniqueId) ?: return
            val match = gameManager.getMatch(game.name)
            
            // キラーの取得
            val killer = player.killer
            if (killer != null) {
                val killerTeam = game.getPlayerTeam(killer.uniqueId)
                if (killerTeam != null && killerTeam != team) {
                    // キル報酬
                    val isCarrier = player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier
                    val killReward = if (isCarrier) {
                        plugin.config.getInt("currency.carrier-kill-reward", 20)
                    } else {
                        plugin.config.getInt("currency.kill-reward", 10)
                    }
                    
                    // マッチがある場合もない場合も通貨を追加
                    game.addTeamCurrency(killerTeam, killReward, "${killer.name}が${player.name}をキル")
                }
            }
            
            // 購入アイテムの処理
            val itemsToKeep = mutableListOf<ItemStack>()
            val itemsToDrop = mutableListOf<ItemStack>()
            
            // インベントリ内のアイテムを分類
            for (item in player.inventory.contents) {
                if (item == null) continue
                
                if (shopManager.isShopItem(item)) {
                    val deathBehavior = shopManager.getItemDeathBehavior(item)
                    when (deathBehavior) {
                        DeathBehavior.KEEP -> itemsToKeep.add(item)
                        DeathBehavior.DROP -> itemsToDrop.add(item)
                        DeathBehavior.DESTROY -> {} // 何もしない
                        null -> itemsToKeep.add(item) // デフォルトは保持
                    }
                } else {
                    // デフォルトアイテムは保持
                    itemsToKeep.add(item)
                }
            }
            
            // 防具の処理
            val armorContents = player.inventory.armorContents
            for (armor in armorContents) {
                if (armor == null) continue
                
                // 革の防具（チーム色）は常に保持
                if (armor.type.name.startsWith("LEATHER_")) {
                    itemsToKeep.add(armor)
                    continue
                }
                
                if (shopManager.isShopItem(armor)) {
                    val deathBehavior = shopManager.getItemDeathBehavior(armor)
                    when (deathBehavior) {
                        DeathBehavior.KEEP -> itemsToKeep.add(armor)
                        DeathBehavior.DROP -> itemsToDrop.add(armor)
                        DeathBehavior.DESTROY -> {} // 何もしない
                        null -> itemsToKeep.add(armor) // デフォルトは保持
                    }
                }
            }
            
            // アイテムをドロップ
            event.drops.clear()
            for (item in itemsToDrop) {
                player.world.dropItem(player.location, item)
            }
            
            // 保持するアイテムを記録
            player.setMetadata("ctf_items_to_keep", 
                org.bukkit.metadata.FixedMetadataValue(plugin, itemsToKeep))
            
            event.keepInventory = false
            event.droppedExp = 0
            
            // 旗を持っていた場合ドロップ
            if (player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier) {
                val flagTeam = if (player.uniqueId == game.redFlagCarrier) Team.RED else Team.BLUE
                game.dropFlag(player, flagTeam)
            }
            
            // リスポーン処理
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                player.spigot().respawn()
                game.handleRespawn(player)
                
                // 保持アイテムを再配布
                val keptItems = player.getMetadata("ctf_items_to_keep")
                    .firstOrNull()?.value() as? List<ItemStack> ?: emptyList()
                
                for (item in keptItems) {
                    player.inventory.addItem(item)
                }
                
                player.removeMetadata("ctf_items_to_keep", plugin)
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
                val gameMode = GameMode.valueOf(game.buildPhaseGameMode)
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
                        }
                    }
                    GameMode.SPECTATOR -> {
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
                val gameMode = GameMode.valueOf(game.buildPhaseGameMode)
                when (gameMode) {
                    GameMode.ADVENTURE -> {
                        event.isCancelled = true
                        player.sendMessage(Component.text("アドベンチャーモードではブロックを設置できません", NamedTextColor.RED))
                    }
                    GameMode.SURVIVAL, GameMode.CREATIVE -> {
                        // 設置は許可
                    }
                    GameMode.SPECTATOR -> {
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
                    
                    // スポーン地点からの距離チェック
                    val spawnLocation = when (team) {
                        Team.RED -> game.getRedSpawnLocation() ?: game.getRedFlagLocation()
                        Team.BLUE -> game.getBlueSpawnLocation() ?: game.getBlueFlagLocation()
                    } ?: return
                    
                    val useRange = plugin.config.getDouble("shop.use-range", 15.0)
                    if (player.location.distance(spawnLocation) > useRange) {
                        player.sendMessage(Component.text("ショップは自陣スポーン地点から${useRange}ブロック以内でのみ使用可能です", NamedTextColor.RED))
                        return
                    }
                    
                    // カテゴリーメニューを開く
                    shopManager.openCategoryMenu(player, game, team)
                }
            }
        }
    }
    
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val game = gameManager.getPlayerGame(player) ?: return
        
        // 防具の取り外し禁止
        if (game.state == GameState.RUNNING && game.phase == GamePhase.COMBAT) {
            val item = event.currentItem
            if (item != null && isArmor(item.type)) {
                event.isCancelled = true
                player.sendMessage(Component.text("戦闘中は防具を外せません！", NamedTextColor.RED))
                return
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
                    "防具" -> 1
                    "消耗品" -> 2
                    "ブロック" -> 3
                    else -> return
                }
                
                shopManager.openShop(player, game, team, page)
                return
            }
            
            // アイテム名から購入処理
            val meta = clickedItem.itemMeta ?: return
            val displayNameComponent = meta.displayName()
            if (displayNameComponent == null) return
            // PlainTextComponentSerializerを使って純粋なテキストを取得
            val displayName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayNameComponent)
            
            // ナビゲーションボタンのチェック
            when (displayName) {
                "← 前のページ" -> {
                    val currentPage = shopManager.getCurrentPage(player)
                    if (currentPage > 0) {
                        shopManager.openShop(player, game, team, currentPage - 1)
                    }
                    return
                }
                "次のページ →" -> {
                    val currentPage = shopManager.getCurrentPage(player)
                    shopManager.openShop(player, game, team, currentPage + 1)
                    return
                }
                "メインメニュー" -> {
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

    private fun isProtectedBlock(game: com.hacklab.ctf.Game, location: org.bukkit.Location): Boolean {
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
        }
        
        return false
    }
}