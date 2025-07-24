package com.hacklab.ctf

import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import com.hacklab.ctf.utils.MatchMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import java.time.Duration
import java.util.*

class Game(
    val gameName: String,
    val plugin: Main,
    val world: World
) {
    var state = GameState.WAITING
    var phase = GamePhase.BUILD
    
    // マッチシステム参照
    private var match: Match? = null
    private var gameEndCallback: ((Team?) -> Unit)? = null
    
    // チーム管理
    val redTeam = mutableSetOf<UUID>()
    val blueTeam = mutableSetOf<UUID>()
    val disconnectedPlayers = mutableMapOf<UUID, Team>() // 切断中のプレイヤー
    
    // 位置設定
    private var redFlagLocation: Location? = null
    private var blueSpawnLocation: Location? = null
    private var redSpawnLocation: Location? = null
    private var blueFlagLocation: Location? = null
    
    // 設定
    var minPlayers = plugin.config.getInt("default-game.min-players", 2)
    var maxPlayersPerTeam = plugin.config.getInt("default-game.max-players-per-team", 10)
    var respawnDelay = plugin.config.getInt("default-game.respawn-delay", 5)
    var buildDuration = plugin.config.getInt("default-phases.build-duration", 300)
    var combatDuration = plugin.config.getInt("default-phases.combat-duration", 600)
    var resultDuration = plugin.config.getInt("default-phases.result-duration", 60)
    var buildPhaseGameMode = plugin.config.getString("default-phases.build-phase-gamemode", "ADVENTURE")!!
    
    // ゲーム状態
    var score = mutableMapOf(Team.RED to 0, Team.BLUE to 0)
    var currentPhaseTime = 0
    var autoStartCountdown = -1
    
    // 通貨管理（マッチがない場合用）
    private val teamCurrency = mutableMapOf(Team.RED to 0, Team.BLUE to 0)
    
    // 旗管理
    var redFlagCarrier: UUID? = null
    var blueFlagCarrier: UUID? = null
    val droppedFlags = mutableMapOf<Location, Pair<Team, Long>>() // 位置 -> (チーム, ドロップ時刻)
    
    // UI要素
    var scoreboard: Scoreboard? = null
    var objective: Objective? = null
    var bossBar: BossBar? = null
    private var lastScoreboardUpdate: Long = 0
    
    // スポーン保護
    val spawnProtection = mutableMapOf<UUID, Long>() // プレイヤー -> 保護終了時刻
    
    // ActionBarメッセージのクールダウン管理
    private val actionBarCooldown = mutableMapOf<UUID, Long>() // プレイヤー -> 次回表示可能時刻
    private val actionBarErrorDisplay = mutableMapOf<UUID, Pair<String, Long>>() // プレイヤー -> (エラーメッセージ, 表示終了時刻)
    
    // タスク
    private var gameTask: BukkitRunnable? = null
    
    // ゲッター
    val name: String get() = gameName
    
    fun setMatchContext(match: Match) {
        this.match = match
    }
    
    fun setGameEndCallback(callback: (Team?) -> Unit) {
        this.gameEndCallback = callback
    }
    
    fun getPlayers(): Set<Player> = getAllPlayers()
    fun getPlayerTeam(player: Player): Team? = getPlayerTeam(player.uniqueId)
    fun getTeamPlayers(team: Team): Set<Player> {
        val uuids = when (team) {
            Team.RED -> redTeam
            Team.BLUE -> blueTeam
        }
        return uuids.mapNotNull { Bukkit.getPlayer(it) }.toSet()
    }
    
    fun getRedScore(): Int = score[Team.RED] ?: 0
    fun getBlueScore(): Int = score[Team.BLUE] ?: 0
    fun getWinner(): Team? {
        return when {
            getRedScore() > getBlueScore() -> Team.RED
            getBlueScore() > getRedScore() -> Team.BLUE
            else -> null
        }
    }
    
    fun getRedFlagLocation(): Location? = redFlagLocation
    fun getBlueFlagLocation(): Location? = blueFlagLocation
    fun getRedSpawnLocation(): Location? = redSpawnLocation
    fun getBlueSpawnLocation(): Location? = blueSpawnLocation
    
    fun setRedFlagLocation(location: Location) { redFlagLocation = location }
    fun setBlueFlagLocation(location: Location) { blueFlagLocation = location }
    fun setRedSpawnLocation(location: Location) { redSpawnLocation = location }
    fun setBlueSpawnLocation(location: Location) { blueSpawnLocation = location }
    
    fun addPlayer(player: Player, team: Team? = null): Boolean {
        if (state != GameState.WAITING) {
            plugin.logger.warning("Player ${player.name} cannot join game $name: state is $state")
            when (state) {
                GameState.STARTING, GameState.RUNNING -> {
                    player.sendMessage(Component.text("このゲームは既に開始されています", NamedTextColor.RED))
                }
                GameState.ENDING -> {
                    player.sendMessage(Component.text("このゲームは終了中です", NamedTextColor.RED))
                }
                GameState.WAITING -> {
                    // このケースは発生しないはず
                }
            }
            return false
        }
        
        val selectedTeam = team ?: selectTeamForPlayer()
        
        if (selectedTeam == Team.RED) {
            if (redTeam.size >= maxPlayersPerTeam) {
                player.sendMessage(Component.text("赤チームが満員です", NamedTextColor.RED))
                return false
            }
            redTeam.add(player.uniqueId)
        } else {
            if (blueTeam.size >= maxPlayersPerTeam) {
                player.sendMessage(Component.text("青チームが満員です", NamedTextColor.RED))
                return false
            }
            blueTeam.add(player.uniqueId)
        }
        
        // スコアボード表示
        setupScoreboard(player)
        updateScoreboard()
        
        player.sendMessage(Component.text("ゲーム '$gameName' の${selectedTeam.displayName}に参加しました", selectedTeam.color))
        
        // マッチに追加
        match?.addPlayerToMatch(player, this)
        
        // 自動開始チェック
        checkAutoStart()
        
        return true
    }
    
    fun removePlayer(player: Player) {
        redTeam.remove(player.uniqueId)
        blueTeam.remove(player.uniqueId)
        disconnectedPlayers.remove(player.uniqueId)
        
        // UI削除
        player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        bossBar?.removePlayer(player)
        
        // 旗を持っていた場合
        if (redFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.RED)
        } else if (blueFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.BLUE)
        }
        
        player.sendMessage(Component.text("ゲーム '$gameName' から退出しました", NamedTextColor.YELLOW))
        
        // マッチから削除
        match?.removePlayerFromMatch(player)
    }
    
    fun handleDisconnect(player: Player) {
        val team = getPlayerTeam(player.uniqueId) ?: return
        disconnectedPlayers[player.uniqueId] = team
        
        // 旗を持っていた場合
        if (redFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.RED)
        } else if (blueFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.BLUE)
        }
    }
    
    fun handleReconnect(player: Player) {
        val team = disconnectedPlayers.remove(player.uniqueId) ?: return
        
        // プレイヤーを再度チームに追加
        when (team) {
            Team.RED -> redTeam.add(player.uniqueId)
            Team.BLUE -> blueTeam.add(player.uniqueId)
        }
        
        // UIの再設定
        setupScoreboard(player)
        bossBar?.addPlayer(player)
        
        // 現在のフェーズに応じた装備を配布
        when (phase) {
            GamePhase.BUILD -> giveBuildPhaseItems(player, team)
            GamePhase.COMBAT -> {
                // 戦闘フェーズ中の途中参加の場合は初期装備を配布
                giveCombatPhaseItems(player, team)
            }
            GamePhase.RESULT -> {}
        }
        
        // スポーン地点に転送
        teleportToSpawn(player, team)
    }
    
    private fun selectTeamForPlayer(): Team {
        return when {
            redTeam.size < blueTeam.size -> Team.RED
            blueTeam.size < redTeam.size -> Team.BLUE
            else -> if (Random().nextBoolean()) Team.RED else Team.BLUE
        }
    }
    
    fun getPlayerTeam(uuid: UUID): Team? {
        return when {
            redTeam.contains(uuid) -> Team.RED
            blueTeam.contains(uuid) -> Team.BLUE
            else -> null
        }
    }
    
    fun getAllPlayers(): Set<Player> {
        return (redTeam + blueTeam).mapNotNull { Bukkit.getPlayer(it) }.toSet()
    }
    
    private fun checkAutoStart() {
        if (state != GameState.WAITING) return
        if (redTeam.size + blueTeam.size < minPlayers) {
            if (autoStartCountdown > 0) {
                autoStartCountdown = -1
                getAllPlayers().forEach {
                    it.sendMessage(Component.text("人数不足のため自動開始がキャンセルされました", NamedTextColor.YELLOW))
                }
            }
            return
        }
        
        if (autoStartCountdown < 0) {
            autoStartCountdown = 30
            object : BukkitRunnable() {
                override fun run() {
                    if (autoStartCountdown <= 0 || state != GameState.WAITING) {
                        cancel()
                        if (autoStartCountdown == 0) {
                            start()
                        }
                        return
                    }
                    
                    if (redTeam.size + blueTeam.size < minPlayers) {
                        autoStartCountdown = -1
                        cancel()
                        getAllPlayers().forEach {
                            it.sendMessage(Component.text("人数不足のため自動開始がキャンセルされました", NamedTextColor.YELLOW))
                        }
                        return
                    }
                    
                    if (autoStartCountdown % 10 == 0 || autoStartCountdown <= 5) {
                        getAllPlayers().forEach {
                            it.sendMessage(Component.text("ゲーム開始まで: ${autoStartCountdown}秒", NamedTextColor.GREEN))
                        }
                    }
                    
                    autoStartCountdown--
                }
            }.runTaskTimer(plugin, 0L, 20L)
        }
    }
    
    fun start(): Boolean {
        if (state != GameState.WAITING) {
            plugin.logger.warning("Game $name cannot start: current state is $state")
            getAllPlayers().forEach {
                it.sendMessage(Component.text("ゲームは既に${state}状態です", NamedTextColor.RED))
            }
            return false
        }
        
        // 最小人数チェック
        if (redTeam.size + blueTeam.size < 2) {
            getAllPlayers().forEach {
                it.sendMessage(Component.text("ゲームを開始するには最低2名必要です", NamedTextColor.RED))
            }
            return false
        }
        
        // 必須設定チェック
        if (redFlagLocation == null || blueFlagLocation == null) {
            getAllPlayers().forEach {
                it.sendMessage(Component.text("両チームの旗位置を設定する必要があります", NamedTextColor.RED))
            }
            return false
        }
        
        state = GameState.STARTING
        phase = GamePhase.BUILD
        currentPhaseTime = buildDuration
        
        // 通貨を初期化
        initializeCurrency()
        
        // BossBar作成
        bossBar = Bukkit.createBossBar(
            "建築フェーズ - 残り時間: ${formatTime(currentPhaseTime)}",
            BarColor.GREEN,
            BarStyle.SOLID
        )
        
        // プレイヤーの準備
        getAllPlayers().forEach { player ->
            val team = getPlayerTeam(player.uniqueId)!!
            
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード設定
            player.gameMode = GameMode.valueOf(buildPhaseGameMode)
            
            // スポーン地点に転送
            teleportToSpawn(player, team)
            
            // 建築フェーズアイテム配布
            giveBuildPhaseItems(player, team)
            
            // BossBar追加
            bossBar?.addPlayer(player)
            
            // タイトル表示
            player.showTitle(Title.title(
                Component.text("ゲーム開始！", NamedTextColor.GREEN),
                Component.text("建築フェーズ - 防御を構築しよう", NamedTextColor.YELLOW),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(500)
                )
            ))
        }
        
        // 旗とスポーン地点の設置
        setupFlags()
        setupSpawnAreas()
        
        // ショップの購入履歴をリセット
        plugin.shopManager.resetGamePurchases(name)
        
        state = GameState.RUNNING
        
        // ゲームループ開始
        startGameLoop()
        
        return true
    }
    
    private fun startGameLoop() {
        gameTask = object : BukkitRunnable() {
            override fun run() {
                if (state != GameState.RUNNING) {
                    cancel()
                    return
                }
                
                // 時間更新
                currentPhaseTime--
                
                // ドロップした旗のチェック
                checkDroppedFlags()
                
                // ドロップした旗への接触チェック（戦闘フェーズのみ）
                if (phase == GamePhase.COMBAT) {
                    checkDroppedFlagTouch()
                }
                
                // フェーズ遷移チェック
                if (currentPhaseTime <= 0) {
                    when (phase) {
                        GamePhase.BUILD -> transitionToCombatPhase()
                        GamePhase.COMBAT -> transitionToResultPhase()
                        GamePhase.RESULT -> endGame()
                    }
                }
                
                // UI更新
                updateBossBar()
                updateScoreboard()
                updateActionBarGuides()
            }
        }
        gameTask?.runTaskTimer(plugin, 0L, 20L)
    }
    
    private fun transitionToCombatPhase() {
        phase = GamePhase.COMBAT
        currentPhaseTime = combatDuration
        
        bossBar?.setTitle("戦闘フェーズ - 残り時間: ${formatTime(currentPhaseTime)}")
        bossBar?.color = BarColor.RED
        
        getAllPlayers().forEach { player ->
            val team = getPlayerTeam(player.uniqueId)!!
            
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード変更
            player.gameMode = GameMode.SURVIVAL
            
            // スポーン地点に転送
            teleportToSpawn(player, team)
            
            // 戦闘フェーズアイテム配布
            giveCombatPhaseItems(player, team)
            
            // タイトル表示
            player.showTitle(Title.title(
                Component.text("戦闘フェーズ開始！", NamedTextColor.RED),
                Component.text("敵の旗を奪取せよ！", NamedTextColor.YELLOW),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(500)
                )
            ))
        }
    }
    
    private fun transitionToResultPhase() {
        phase = GamePhase.RESULT
        currentPhaseTime = resultDuration
        
        // 戦闘フェーズ終了ボーナス
        val phaseEndBonus = plugin.config.getInt("currency.phase-end-bonus", 50)
        addTeamCurrency(Team.RED, phaseEndBonus, "戦闘フェーズ終了ボーナス")
        addTeamCurrency(Team.BLUE, phaseEndBonus, "戦闘フェーズ終了ボーナス")
        
        val winner = when {
            score[Team.RED]!! > score[Team.BLUE]!! -> Team.RED
            score[Team.BLUE]!! > score[Team.RED]!! -> Team.BLUE
            else -> null
        }
        
        bossBar?.setTitle("試合終了！")
        bossBar?.color = BarColor.YELLOW
        
        // ゲーム結果レポートを作成
        displayGameReport(winner)
        
        getAllPlayers().forEach { player ->
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード変更
            player.gameMode = GameMode.SPECTATOR
            
            // 結果表示
            if (winner != null) {
                player.showTitle(Title.title(
                    Component.text("${winner.displayName}の勝利！", winner.color),
                    Component.text("スコア: 赤 ${score[Team.RED]} - ${score[Team.BLUE]} 青", NamedTextColor.WHITE),
                    Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500)
                    )
                ))
            } else {
                player.showTitle(Title.title(
                    Component.text("引き分け！", NamedTextColor.YELLOW),
                    Component.text("スコア: 赤 ${score[Team.RED]} - ${score[Team.BLUE]} 青", NamedTextColor.WHITE),
                    Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500)
                    )
                ))
            }
        }
    }
    
    fun stop() {
        plugin.logger.info("Stopping game: $gameName")
        state = GameState.ENDING
        
        // タスクキャンセル
        gameTask?.cancel()
        
        // プレイヤーのUUIDリストをコピー（forEach中の変更を避けるため）
        val playerUUIDs = (redTeam + blueTeam).toList()
        
        // プレイヤー処理
        playerUUIDs.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード戻す
            player.gameMode = GameMode.SURVIVAL
            
            // 発光効果を解除
            player.isGlowing = false
            
            // UI削除
            player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
            bossBar?.removePlayer(player)
            
            // ワールドスポーンに転送
            player.teleport(world.spawnLocation)
            
            player.sendMessage(Component.text("ゲームが終了しました", NamedTextColor.YELLOW))
            
            // GameManagerからプレイヤーを削除
            val gameManager = plugin.gameManager as com.hacklab.ctf.managers.GameManagerNew
            gameManager.removePlayerFromGame(player)
        }
        
        // BossBar削除
        bossBar?.removeAll()
        bossBar = null
        
        // スコアボード削除
        scoreboard = null
        objective = null
        
        // 旗とスポーン装飾を削除
        cleanupGameBlocks()
        
        // データクリアの前に状態をリセット
        state = GameState.WAITING
        phase = GamePhase.BUILD
        currentPhaseTime = 0
        autoStartCountdown = -1
        
        // データクリア
        redTeam.clear()
        blueTeam.clear()
        disconnectedPlayers.clear()
        score.clear()
        score[Team.RED] = 0
        score[Team.BLUE] = 0
        droppedFlags.clear()
        spawnProtection.clear()
        actionBarCooldown.clear()
        actionBarErrorDisplay.clear()
        redFlagCarrier = null
        blueFlagCarrier = null
    }
    
    private fun endGame() {
        plugin.logger.info("Game $gameName ended naturally")
        
        // 勝者を決定
        val winner = getWinner()
        
        // コールバックを実行（マッチがある場合は、マッチが次のゲームを管理）
        if (gameEndCallback != null) {
            gameEndCallback?.invoke(winner)
        } else {
            // マッチがない場合のみ、ゲームを停止
            stop()
        }
        
        // GameManagerから削除はしない（再利用可能）
    }
    
    // 以下、ヘルパーメソッド
    
    private fun teleportToSpawn(player: Player, team: Team) {
        val spawnLocation = when (team) {
            Team.RED -> redSpawnLocation ?: redFlagLocation
            Team.BLUE -> blueSpawnLocation ?: blueFlagLocation
        }
        
        if (spawnLocation != null) {
            player.teleport(spawnLocation)
        } else {
            player.sendMessage(Component.text("スポーン地点が設定されていません", NamedTextColor.RED))
            plugin.logger.warning("Game $name: No spawn location for team $team")
        }
    }
    
    private fun giveBuildPhaseItems(player: Player, team: Team) {
        val inv = player.inventory
        
        // 革の防具（チームカラー）
        giveColoredLeatherArmor(player, team)
        
        // config.ymlから建築フェーズ装備を取得
        val buildEquipment = plugin.config.getConfigurationSection("initial-equipment.build-phase")
        if (buildEquipment != null) {
            // ツール
            buildEquipment.getStringList("tools").forEach { toolStr ->
                val parts = toolStr.split(":")
                val material = Material.getMaterial(parts[0]) ?: return@forEach
                val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
                inv.addItem(ItemStack(material, amount))
            }
            
            // ブロック
            buildEquipment.getStringList("blocks").forEach { blockStr ->
                val parts = blockStr.split(":")
                val material = Material.getMaterial(parts[0]) ?: return@forEach
                val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
                inv.addItem(ItemStack(material, amount))
            }
            
            // 食料
            buildEquipment.getStringList("food").forEach { foodStr ->
                val parts = foodStr.split(":")
                val material = Material.getMaterial(parts[0]) ?: return@forEach
                val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
                inv.addItem(ItemStack(material, amount))
            }
        } else {
            // デフォルト装備
            inv.addItem(ItemStack(Material.IRON_PICKAXE))
            inv.addItem(ItemStack(Material.IRON_AXE))
            inv.addItem(ItemStack(Material.IRON_SHOVEL))
            inv.addItem(ItemStack(Material.OAK_PLANKS, 64))
            inv.addItem(ItemStack(Material.COBBLESTONE, 64))
            inv.addItem(ItemStack(Material.DIRT, 32))
            inv.addItem(ItemStack(Material.BREAD, 16))
        }
        
        // ショップアイテムをホットバー9番目に配置
        val shopItem = plugin.shopManager.createShopItem()
        player.inventory.setItem(8, shopItem)
    }
    
    
    private fun giveCombatPhaseItems(player: Player, team: Team) {
        val inv = player.inventory
        
        // 革の防具（チームカラー）を再装備（防具をリセットして確実に着用）
        giveColoredLeatherArmor(player, team)
        
        // config.ymlから戦闘フェーズ装備を取得
        val combatEquipment = plugin.config.getConfigurationSection("initial-equipment.combat-phase")
        if (combatEquipment != null) {
            // 武器
            combatEquipment.getStringList("weapons").forEach { weaponStr ->
                val parts = weaponStr.split(":")
                val material = Material.getMaterial(parts[0]) ?: return@forEach
                val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
                inv.addItem(ItemStack(material, amount))
            }
            
            // 食料
            combatEquipment.getStringList("food").forEach { foodStr ->
                val parts = foodStr.split(":")
                val material = Material.getMaterial(parts[0]) ?: return@forEach
                val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
                inv.addItem(ItemStack(material, amount))
            }
        } else {
            // デフォルト装備
            inv.addItem(ItemStack(Material.STONE_SWORD))
            inv.addItem(ItemStack(Material.STONE_AXE))
            inv.addItem(ItemStack(Material.BREAD, 8))
        }
        
        // チーム識別用にプレイヤー名を色付け
        player.setDisplayName("${team.getChatColor()}${player.name}")
        player.setPlayerListName("${team.getChatColor()}${player.name}")
        
        // ショップアイテムをホットバー9番目に配置
        val shopItem = plugin.shopManager.createShopItem()
        player.inventory.setItem(8, shopItem)
    }
    
    private fun giveDefaultCombatItems(player: Player, team: Team) {
        val inv = player.inventory
        // デフォルト装備
        inv.addItem(ItemStack(Material.STONE_SWORD))
        inv.addItem(ItemStack(Material.STONE_AXE))
        inv.addItem(ItemStack(Material.BREAD, 8))
    }
    
    private fun giveColoredLeatherArmor(player: Player, team: Team) {
        val color = when (team) {
            Team.RED -> org.bukkit.Color.RED
            Team.BLUE -> org.bukkit.Color.BLUE
        }
        
        // 革のヘルメット
        val helmet = ItemStack(Material.LEATHER_HELMET)
        val helmetMeta = helmet.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        helmetMeta?.setColor(color)
        helmetMeta?.isUnbreakable = true
        helmet.itemMeta = helmetMeta
        
        // 革のチェストプレート
        val chestplate = ItemStack(Material.LEATHER_CHESTPLATE)
        val chestMeta = chestplate.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        chestMeta?.setColor(color)
        chestMeta?.isUnbreakable = true
        chestplate.itemMeta = chestMeta
        
        // 革のレギンス
        val leggings = ItemStack(Material.LEATHER_LEGGINGS)
        val legMeta = leggings.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        legMeta?.setColor(color)
        legMeta?.isUnbreakable = true
        leggings.itemMeta = legMeta
        
        // 革のブーツ
        val boots = ItemStack(Material.LEATHER_BOOTS)
        val bootMeta = boots.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        bootMeta?.setColor(color)
        bootMeta?.isUnbreakable = true
        boots.itemMeta = bootMeta
        
        // 装備
        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots
    }
    
    private fun setupFlags() {
        // 旗の設置（ビーコン）
        Team.values().forEach { team ->
            val flagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
            } ?: return@forEach
            
            setupFlagBeacon(flagLocation, team)
        }
    }
    
    private fun setupFlagBeacon(location: Location, team: Team) {
        val world = location.world
        
        // ビーコンを設置
        location.block.type = Material.BEACON
        
        // 3x3の鉄ブロックベースを設置
        for (x in -1..1) {
            for (z in -1..1) {
                location.clone().add(x.toDouble(), -1.0, z.toDouble()).block.type = Material.IRON_BLOCK
            }
        }
        
        // チーム色のステンドグラスを上に設置
        val glassType = when (team) {
            Team.RED -> Material.RED_STAINED_GLASS
            Team.BLUE -> Material.BLUE_STAINED_GLASS
        }
        location.clone().add(0.0, 1.0, 0.0).block.type = glassType
        
        // 旗位置を記録
        when (team) {
            Team.RED -> redFlagLocation = location
            Team.BLUE -> blueFlagLocation = location
        }
    }
    
    private fun setupSpawnAreas() {
        // スポーンエリアの装飾（スポーン地点が設定されている場合のみ）
        Team.values().forEach { team ->
            val spawnLocation = when (team) {
                Team.RED -> redSpawnLocation
                Team.BLUE -> blueSpawnLocation
            } ?: return@forEach  // スポーン地点が設定されていない場合はスキップ
            
            setupSpawnDecoration(spawnLocation, team)
        }
    }
    
    private fun setupSpawnDecoration(location: Location, team: Team) {
        // チーム色のコンクリートで3x3の床のみ
        val concreteType = when (team) {
            Team.RED -> Material.RED_CONCRETE
            Team.BLUE -> Material.BLUE_CONCRETE
        }
        
        for (x in -1..1) {
            for (z in -1..1) {
                location.clone().add(x.toDouble(), -1.0, z.toDouble()).block.type = concreteType
            }
        }
    }
    
    private fun cleanupGameBlocks() {
        // 旗の削除
        Team.values().forEach { team ->
            val flagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
            } ?: return@forEach
            
            // ビーコンと色付きガラスを削除
            flagLocation.block.type = Material.AIR
            flagLocation.clone().add(0.0, 1.0, 0.0).block.type = Material.AIR
            
            // ベースブロックを削除
            for (x in -1..1) {
                for (z in -1..1) {
                    flagLocation.clone().add(x.toDouble(), -1.0, z.toDouble()).block.type = Material.AIR
                }
            }
        }
        
        // スポーン装飾の削除（スポーン地点が設定されている場合のみ）
        Team.values().forEach { team ->
            val spawnLocation = when (team) {
                Team.RED -> redSpawnLocation
                Team.BLUE -> blueSpawnLocation
            } ?: return@forEach  // スポーン地点が設定されていない場合はスキップ
            
            // コンクリート床を削除
            for (x in -1..1) {
                for (z in -1..1) {
                    spawnLocation.clone().add(x.toDouble(), -1.0, z.toDouble()).block.type = Material.AIR
                }
            }
        }
    }
    
    private fun setupScoreboard(player: Player) {
        try {
            // 共通のスコアボードを使用（初回のみ作成）
            if (scoreboard == null) {
                scoreboard = Bukkit.getScoreboardManager().newScoreboard
                objective = scoreboard!!.registerNewObjective("ctf_game", "dummy", 
                    Component.text("CTF - $gameName", NamedTextColor.GOLD))
                objective!!.displaySlot = DisplaySlot.SIDEBAR
            }
            
            // プレイヤーに共通のスコアボードを設定
            player.scoreboard = scoreboard!!
            
        } catch (e: Exception) {
            plugin.logger.warning("Failed to setup scoreboard for player ${player.name}: ${e.message}")
        }
    }
    
    fun updateScoreboard() {
        val obj = objective ?: return
        
        // スコアボードの更新を1秒に1回に制限（ゲームループと同期）
        if (System.currentTimeMillis() - lastScoreboardUpdate < 1000) {
            return
        }
        lastScoreboardUpdate = System.currentTimeMillis()
        
        // 既存のエントリをクリア
        scoreboard?.entries?.forEach { entry ->
            scoreboard?.resetScores(entry)
        }
        
        var line = 15
        
        // マッチ情報（マッチがある場合）
        match?.let { m ->
            obj.getScore("§6§l=== マッチ情報 ===").score = line--
            
            // モードと進捗
            when (m.mode) {
                MatchMode.FIRST_TO_X -> {
                    obj.getScore("§e先取${m.target}勝負").score = line--
                    obj.getScore("§f第${m.getCurrentGameNumber()}ゲーム目").score = line--
                }
                MatchMode.FIXED_ROUNDS -> {
                    obj.getScore("§e全${m.target}ゲーム").score = line--
                    obj.getScore("§f第${m.getCurrentGameNumber()}/${m.target}ゲーム").score = line--
                }
            }
            
            // マッチスコア
            val wins = m.getMatchWins()
            obj.getScore("§f勝利数: §c${wins[Team.RED] ?: 0} §f- §9${wins[Team.BLUE] ?: 0}").score = line--
            obj.getScore(" ").score = line--
        }
        
        // ゲーム情報
        obj.getScore("§e§l==============").score = line--
        obj.getScore("§fフェーズ: §a${getPhaseDisplayName()}").score = line--
        obj.getScore("§f残り時間: §e${formatTime(currentPhaseTime)}").score = line--
        obj.getScore("§e§l==============").score = line--
        obj.getScore("  ").score = line--
        
        // スコア
        if (phase == GamePhase.COMBAT || phase == GamePhase.RESULT) {
            obj.getScore("§c赤チーム: §f${score[Team.RED] ?: 0}").score = line--
            obj.getScore("§9青チーム: §f${score[Team.BLUE] ?: 0}").score = line--
            obj.getScore("   ").score = line--
        }
        
        // チーム通貨
        if (match != null) {
            // マッチシステムの通貨
            obj.getScore("§6§l=== チーム資金 ===").score = line--
            obj.getScore("§c赤チーム: §e${match!!.getTeamCurrency(Team.RED)}G").score = line--
            obj.getScore("§9青チーム: §e${match!!.getTeamCurrency(Team.BLUE)}G").score = line--
        } else {
            // 単独ゲームの通貨
            obj.getScore("§6§l=== チーム資金 ===").score = line--
            obj.getScore("§c赤チーム: §e${getTeamCurrency(Team.RED)}G").score = line--
            obj.getScore("§9青チーム: §e${getTeamCurrency(Team.BLUE)}G").score = line--
        }
        obj.getScore("    ").score = line--
        
        // チーム人数
        obj.getScore("§c赤: §f${redTeam.size}名").score = line--
        obj.getScore("§9青: §f${blueTeam.size}名").score = line--
        obj.getScore("     ").score = line--
        
        // 旗の状態
        if (phase == GamePhase.COMBAT) {
            if (redFlagCarrier != null) {
                val carrier = Bukkit.getPlayer(redFlagCarrier!!)
                obj.getScore("§c赤旗: §e${carrier?.name ?: "不明"}").score = line--
            }
            if (blueFlagCarrier != null) {
                val carrier = Bukkit.getPlayer(blueFlagCarrier!!)
                obj.getScore("§9青旗: §e${carrier?.name ?: "不明"}").score = line--
            }
        }
    }
    
    private fun getPhaseDisplayName(): String {
        return when (phase) {
            GamePhase.BUILD -> "建築"
            GamePhase.COMBAT -> "戦闘"
            GamePhase.RESULT -> "結果"
        }
    }
    
    private fun updateBossBar() {
        val bar = bossBar ?: return
        
        // フェーズ情報
        val phaseText = when (phase) {
            GamePhase.BUILD -> "建築フェーズ"
            GamePhase.COMBAT -> "戦闘フェーズ"
            GamePhase.RESULT -> "リザルト"
        }
        
        // 時間情報
        val timeText = formatTime(currentPhaseTime)
        
        // マッチ情報（マッチモードの場合）
        val matchInfo = match?.let { m ->
            val wins = m.getMatchWins()
            when (m.mode) {
                MatchMode.FIRST_TO_X -> "[先取${m.target}] 第${m.getCurrentGameNumber()}ゲーム | "
                MatchMode.FIXED_ROUNDS -> "[${m.getCurrentGameNumber()}/${m.target}] | "
            } + "赤${wins[Team.RED]}勝 青${wins[Team.BLUE]}勝 | "
        } ?: ""
        
        // 現在のスコア（戦闘・結果フェーズのみ）
        val scoreInfo = if (phase == GamePhase.COMBAT || phase == GamePhase.RESULT) {
            "スコア: 赤${score[Team.RED] ?: 0} - 青${score[Team.BLUE] ?: 0} | "
        } else ""
        
        // 旗の状態（戦闘フェーズのみ）
        val flagInfo = if (phase == GamePhase.COMBAT) {
            val redCarrierName = redFlagCarrier?.let { Bukkit.getPlayer(it)?.name }
            val blueCarrierName = blueFlagCarrier?.let { Bukkit.getPlayer(it)?.name }
            
            when {
                redCarrierName != null && blueCarrierName != null -> "旗: 赤→${blueCarrierName} 青→${redCarrierName} | "
                redCarrierName != null -> "青旗: ${redCarrierName}が保持 | "
                blueCarrierName != null -> "赤旗: ${blueCarrierName}が保持 | "
                else -> ""
            }
        } else ""
        
        // タイトルを組み立て
        bar.setTitle("$matchInfo$scoreInfo$flagInfo$phaseText - $timeText")
        
        // フェーズに応じて色を変更
        bar.color = when (phase) {
            GamePhase.BUILD -> BarColor.GREEN
            GamePhase.COMBAT -> BarColor.RED
            GamePhase.RESULT -> BarColor.YELLOW
        }
        
        // 進行度を更新
        val totalTime = when (phase) {
            GamePhase.BUILD -> buildDuration
            GamePhase.COMBAT -> combatDuration
            GamePhase.RESULT -> resultDuration
        }
        
        val progress = currentPhaseTime.toDouble() / totalTime.toDouble()
        bar.progress = progress.coerceIn(0.0, 1.0)
    }
    
    fun dropFlag(player: Player, team: Team) {
        val carrier = when (team) {
            Team.RED -> redFlagCarrier
            Team.BLUE -> blueFlagCarrier
        }
        
        if (carrier != player.uniqueId) return
        
        // キャリアをクリア
        when (team) {
            Team.RED -> redFlagCarrier = null
            Team.BLUE -> blueFlagCarrier = null
        }
        
        // グロー効果を解除
        player.isGlowing = false
        
        // 旗をドロップ
        val itemStack = ItemStack(Material.BEACON)
        val meta = itemStack.itemMeta
        meta.displayName(Component.text("${team.displayName}の旗", team.color))
        meta.isUnbreakable = true
        itemStack.itemMeta = meta
        
        val droppedItem = player.world.dropItem(player.location, itemStack)
        droppedItem.setGlowing(true)
        droppedItem.customName(Component.text("${team.displayName}の旗", team.color))
        droppedItem.isCustomNameVisible = true
        droppedItem.isInvulnerable = true
        
        // ドロップ情報を記録
        droppedFlags[player.location] = Pair(team, System.currentTimeMillis())
        
        // メッセージ
        getAllPlayers().forEach {
            it.sendMessage(Component.text("${team.displayName}の旗がドロップされました！", NamedTextColor.YELLOW))
        }
    }
    
    private fun checkDroppedFlags() {
        val iterator = droppedFlags.iterator()
        while (iterator.hasNext()) {
            val (location, data) = iterator.next()
            val (team, dropTime) = data
            
            // 30秒経過したら元の位置に戻す
            if (System.currentTimeMillis() - dropTime > 30000) {
                // ドロップアイテムを削除
                location.world.getNearbyEntities(location, 50.0, 50.0, 50.0)
                    .filterIsInstance<org.bukkit.entity.Item>()
                    .find { it.itemStack.type == Material.BEACON }
                    ?.remove()
                
                // 旗を元の位置に再設置
                val flagLocation = when (team) {
                    Team.RED -> redFlagLocation
                    Team.BLUE -> blueFlagLocation
                } ?: continue
                
                setupFlagBeacon(flagLocation, team)
                
                iterator.remove()
                
                getAllPlayers().forEach {
                    it.sendMessage(Component.text("${team.displayName}の旗が元の位置に戻りました", team.color))
                }
            }
        }
    }
    
    fun pickupFlag(player: Player, item: org.bukkit.entity.Item): Boolean {
        val team = getPlayerTeam(player.uniqueId) ?: return false
        
        // アイテムがビーコンかチェック
        if (item.itemStack.type != Material.BEACON) return false
        
        // どちらのチームの旗かを判定
        val flagTeam = when {
            item.customName()?.contains(Component.text("赤")) == true -> Team.RED
            item.customName()?.contains(Component.text("青")) == true -> Team.BLUE
            else -> return false
        }
        
        // 自分のチームの旗を拾った場合
        if (team == flagTeam) {
            // ドロップフラグから削除
            droppedFlags.entries.removeIf { (location, data) ->
                data.first == flagTeam && location.distance(item.location) < 2.0
            }
            
            // アイテムを削除
            item.remove()
            
            // 旗を元の位置に戻す
            val flagLocation = when (flagTeam) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
            } ?: return false
            
            setupFlagBeacon(flagLocation, flagTeam)
            
            // メッセージ
            getAllPlayers().forEach {
                it.sendMessage(Component.text("${player.name}が${flagTeam.displayName}の旗を回収しました！", team.color))
            }
            
            return true
        }
        
        // 敵の旗を拾う場合
        // 既に旗を持っているかチェック
        if (player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier) {
            player.sendMessage(Component.text("既に旗を持っています", NamedTextColor.RED))
            return false
        }
        
        // 旗を拾う
        when (flagTeam) {
            Team.RED -> redFlagCarrier = player.uniqueId
            Team.BLUE -> blueFlagCarrier = player.uniqueId
        }
        
        // ドロップフラグから削除
        droppedFlags.entries.removeIf { (location, data) ->
            data.first == flagTeam && location.distance(item.location) < 2.0
        }
        
        // アイテムを削除
        item.remove()
        
        // プレイヤーに発光効果
        player.isGlowing = true
        
        // メッセージ
        getAllPlayers().forEach {
            it.sendMessage(Component.text("${player.name}が${flagTeam.displayName}の旗を取得しました！", NamedTextColor.YELLOW))
        }
        
        return true
    }
    
    private fun checkDroppedFlagTouch() {
        // ドロップされている旗の位置を確認
        val world = redFlagLocation?.world ?: return
        
        // 全プレイヤーをチェック
        getAllPlayers().forEach { player ->
            val team = getPlayerTeam(player.uniqueId) ?: return@forEach
            
            // 既に旗を持っているプレイヤーはスキップ
            if (player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier) {
                return@forEach
            }
            
            // ドロップされた旗の近くにいるかチェック
            world.entities.filterIsInstance<org.bukkit.entity.Item>().forEach { item ->
                if (item.itemStack.type == Material.BEACON && player.location.distance(item.location) < 1.5) {
                    // どちらのチームの旗かを判定
                    val flagTeam = when {
                        item.customName()?.contains(Component.text("赤")) == true -> Team.RED
                        item.customName()?.contains(Component.text("青")) == true -> Team.BLUE
                        else -> return@forEach
                    }
                    
                    // 自分のチームの旗に触れた場合
                    if (team == flagTeam) {
                        // ドロップフラグから削除
                        droppedFlags.entries.removeIf { (location, data) ->
                            data.first == flagTeam && location.distance(item.location) < 2.0
                        }
                        
                        // アイテムを削除
                        item.remove()
                        
                        // 旗を元の位置に戻す
                        val flagLocation = when (flagTeam) {
                            Team.RED -> redFlagLocation
                            Team.BLUE -> blueFlagLocation
                        } ?: return@forEach
                        
                        setupFlagBeacon(flagLocation, flagTeam)
                        
                        // メッセージ
                        getAllPlayers().forEach { p ->
                            p.sendMessage(Component.text("${player.name}が${flagTeam.displayName}の旗を回収しました！", team.color))
                        }
                    }
                    // 敵の旗に触れた場合
                    else if (team != flagTeam) {
                        // 旗を拾う
                        when (flagTeam) {
                            Team.RED -> redFlagCarrier = player.uniqueId
                            Team.BLUE -> blueFlagCarrier = player.uniqueId
                        }
                        
                        // ドロップフラグから削除
                        droppedFlags.entries.removeIf { (location, data) ->
                            data.first == flagTeam && location.distance(item.location) < 2.0
                        }
                        
                        // アイテムを削除
                        item.remove()
                        
                        // プレイヤーに発光効果
                        player.isGlowing = true
                        
                        // メッセージ
                        getAllPlayers().forEach { p ->
                            p.sendMessage(Component.text("${player.name}が${flagTeam.displayName}の旗を取得しました！", NamedTextColor.YELLOW))
                        }
                    }
                }
            }
        }
    }
    
    fun captureFlag(player: Player): Boolean {
        val team = getPlayerTeam(player.uniqueId) ?: return false
        
        // 旗を持っているかチェック
        val carriedFlagTeam = when (player.uniqueId) {
            redFlagCarrier -> Team.RED
            blueFlagCarrier -> Team.BLUE
            else -> return false
        }
        
        // 自分のチームの旗拠点にいるかチェック
        val ownFlagLocation = when (team) {
            Team.RED -> redFlagLocation
            Team.BLUE -> blueFlagLocation
        } ?: return false
        
        if (player.location.distance(ownFlagLocation) > 3.0) {
            return false
        }
        
        // 自分のチームの旗が自陣にあるかチェック
        val ownFlagIsAtBase = when (team) {
            Team.RED -> redFlagCarrier == null && !droppedFlags.any { it.value.first == Team.RED }
            Team.BLUE -> blueFlagCarrier == null && !droppedFlags.any { it.value.first == Team.BLUE }
        }
        
        if (!ownFlagIsAtBase) {
            // エラーメッセージを5秒間表示するように設定
            val now = System.currentTimeMillis()
            val lastShown = actionBarCooldown[player.uniqueId] ?: 0
            
            if (now - lastShown > 10000) { // 10秒に1回新規エラーを設定可能
                val errorMessage = "自チームの旗が自陣にないため、キャプチャーできません"
                actionBarErrorDisplay[player.uniqueId] = Pair(errorMessage, now + 5000) // 5秒間表示
                actionBarCooldown[player.uniqueId] = now
            }
            
            return false
        }
        
        // スコア加算
        score[team] = (score[team] ?: 0) + 1
        
        // 通貨報酬（マッチがある場合もない場合も）
        val captureReward = plugin.config.getInt("currency.capture-reward", 30)
        addTeamCurrency(team, captureReward, "${player.name}が旗をキャプチャー")
        
        // キャリアをクリア
        when (carriedFlagTeam) {
            Team.RED -> redFlagCarrier = null
            Team.BLUE -> blueFlagCarrier = null
        }
        
        // グロー効果を解除
        player.isGlowing = false
        
        // 敵の旗を元の位置に戻す
        val enemyFlagLocation = when (carriedFlagTeam) {
            Team.RED -> redFlagLocation
            Team.BLUE -> blueFlagLocation
        } ?: return false
        
        setupFlagBeacon(enemyFlagLocation, carriedFlagTeam)
        
        // 全プレイヤーに通知
        getAllPlayers().forEach { p ->
            p.sendMessage(Component.text("${team.displayName}が得点しました！ (${player.name})", team.color))
            p.sendMessage(Component.text("現在のスコア - 赤: ${score[Team.RED]} 青: ${score[Team.BLUE]}", NamedTextColor.WHITE))
            
            // 効果音を再生
            p.playSound(p.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        }
        
        // タイトル表示
        getAllPlayers().forEach { p ->
            p.showTitle(Title.title(
                Component.text("${team.displayName}が得点！", team.color),
                Component.text("赤: ${score[Team.RED]} - ${score[Team.BLUE]} :青", NamedTextColor.WHITE),
                Title.Times.times(
                    Duration.ofMillis(250),
                    Duration.ofSeconds(2),
                    Duration.ofMillis(250)
                )
            ))
        }
        
        return true
    }
    
    fun handleRespawn(player: Player) {
        val team = getPlayerTeam(player.uniqueId) ?: return
        
        // 3秒間のスポーン保護を付与
        spawnProtection[player.uniqueId] = System.currentTimeMillis() + 3000
        
        // 保護中は光る
        player.isGlowing = true
        
        // 3秒後に保護を解除
        object : BukkitRunnable() {
            override fun run() {
                if (spawnProtection.remove(player.uniqueId) != null) {
                    player.isGlowing = false
                    player.sendMessage(Component.text("スポーン保護が解除されました", NamedTextColor.YELLOW))
                }
            }
        }.runTaskLater(plugin, 60L) // 3秒後
        
        // スポーン地点に転送
        teleportToSpawn(player, team)
        
        // 戦闘フェーズではリスポーン時に装備を再配布しない
        // 建築フェーズでは、リスポーン時にツールを復元する
        when (phase) {
            GamePhase.BUILD -> {
                // 建築ツールのみ再配布（防具とショップアイテムは既に持っているはず）
                val buildEquipment = plugin.config.getConfigurationSection("initial-equipment.build-phase")
                if (buildEquipment != null) {
                    buildEquipment.getStringList("tools").forEach { toolStr ->
                        val parts = toolStr.split(":")
                        val material = Material.getMaterial(parts[0]) ?: return@forEach
                        if (!player.inventory.contains(material)) {
                            val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
                            player.inventory.addItem(ItemStack(material, amount))
                        }
                    }
                }
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズではリスポーン時に装備を再配布しない
            }
            GamePhase.RESULT -> {}
        }
        
        player.sendMessage(Component.text("3秒間スポーン保護が有効です", NamedTextColor.GREEN))
    }
    
    fun isUnderSpawnProtection(player: Player): Boolean {
        val protectionEnd = spawnProtection[player.uniqueId] ?: return false
        return System.currentTimeMillis() < protectionEnd
    }
    
    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return String.format("%d:%02d", min, sec)
    }
    
    private fun updateActionBarGuides() {
        getAllPlayers().forEach { player ->
            val team = getPlayerTeam(player.uniqueId) ?: return@forEach
            
            // エラーメッセージが表示期間中の場合は優先して表示
            val now = System.currentTimeMillis()
            val errorData = actionBarErrorDisplay[player.uniqueId]
            if (errorData != null && now < errorData.second) {
                player.sendActionBar(Component.text(errorData.first, NamedTextColor.RED))
                return@forEach
            } else if (errorData != null) {
                // 期限切れのエラーメッセージを削除
                actionBarErrorDisplay.remove(player.uniqueId)
            }
            
            val message = when (phase) {
                GamePhase.BUILD -> {
                    // 建築フェーズ
                    Component.text("建築して防御を固めよう！", NamedTextColor.GREEN)
                }
                
                GamePhase.COMBAT -> {
                    // 戦闘フェーズ
                    val enemyTeam = if (team == Team.RED) Team.BLUE else Team.RED
                    
                    when {
                        // 自分が旗を持っている
                        player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier -> {
                            Component.text("自陣に戻れ！", NamedTextColor.GOLD)
                        }
                        
                        // 自チームの旗が敵に取られている
                        (team == Team.RED && redFlagCarrier != null) || 
                        (team == Team.BLUE && blueFlagCarrier != null) -> {
                            val carrierName = when (team) {
                                Team.RED -> redFlagCarrier?.let { Bukkit.getPlayer(it)?.name } ?: "不明"
                                Team.BLUE -> blueFlagCarrier?.let { Bukkit.getPlayer(it)?.name } ?: "不明"
                            }
                            Component.text("旗が敵に取られた！取り返せ！($carrierName)", NamedTextColor.RED)
                        }
                        
                        // 自チームの旗がドロップしている
                        (team == Team.RED && droppedFlags.any { it.value.first == Team.RED }) ||
                        (team == Team.BLUE && droppedFlags.any { it.value.first == Team.BLUE }) -> {
                            Component.text("自チームの旗を回収せよ！", NamedTextColor.YELLOW)
                        }
                        
                        // 通常状態（敵の旗を取りに行く）
                        else -> {
                            Component.text("${enemyTeam.displayName}の旗を奪取せよ！", enemyTeam.color)
                        }
                    }
                }
                
                GamePhase.RESULT -> {
                    // リザルトフェーズは何も表示しない
                    return@forEach
                }
            }
            
            player.sendActionBar(message)
        }
    }
    
    // 通貨管理メソッド（マッチがない場合用）
    fun getTeamCurrency(team: Team): Int {
        return match?.getTeamCurrency(team) ?: teamCurrency[team] ?: 0
    }
    
    fun addTeamCurrency(team: Team, amount: Int, reason: String = "") {
        if (match != null) {
            match!!.addTeamCurrency(team, amount, reason)
        } else {
            val current = teamCurrency[team] ?: 0
            teamCurrency[team] = current + amount
            
            // チームメンバーに通知
            getTeamPlayers(team).forEach { player ->
                if (reason.isNotEmpty()) {
                    player.sendMessage(Component.text("[チーム] $reason (+${amount}G)", NamedTextColor.GREEN))
                }
                player.sendMessage(Component.text("[チーム] 残高: ${teamCurrency[team]}G", NamedTextColor.GREEN))
            }
        }
    }
    
    fun spendTeamCurrency(team: Team, amount: Int, player: Player, itemName: String): Boolean {
        if (match != null) {
            return match!!.spendTeamCurrency(team, amount, player, itemName)
        } else {
            val current = teamCurrency[team] ?: 0
            if (current < amount) return false
            
            teamCurrency[team] = current - amount
            
            // チームメンバーに通知
            getTeamPlayers(team).forEach { p ->
                p.sendMessage(Component.text("[チーム] ${player.name} が $itemName を購入しました (-${amount}G)", NamedTextColor.YELLOW))
                p.sendMessage(Component.text("[チーム] 残高: ${teamCurrency[team]}G", NamedTextColor.YELLOW))
            }
            
            return true
        }
    }
    
    fun initializeCurrency() {
        val initialAmount = plugin.config.getInt("currency.initial", 50)
        teamCurrency[Team.RED] = initialAmount
        teamCurrency[Team.BLUE] = initialAmount
    }
    
    private fun displayGameReport(winner: Team?) {
        // ゲーム結果の詳細レポート
        getAllPlayers().forEach { player ->
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(Component.text("========== ゲーム結果 ==========").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            
            // マッチ情報（マッチモードの場合）
            match?.let { m ->
                player.sendMessage(Component.text(m.getMatchStatus()).color(NamedTextColor.YELLOW))
                val wins = m.getMatchWins()
                player.sendMessage(Component.text("現在のマッチスコア: ").color(NamedTextColor.WHITE)
                    .append(Component.text("赤 ${wins[Team.RED]} ").color(NamedTextColor.RED))
                    .append(Component.text("- ").color(NamedTextColor.WHITE))
                    .append(Component.text("青 ${wins[Team.BLUE]}").color(NamedTextColor.BLUE)))
                player.sendMessage(Component.text("", NamedTextColor.WHITE))
            }
            
            // 今回のゲーム結果
            player.sendMessage(Component.text("今回のゲーム:").color(NamedTextColor.YELLOW))
            if (winner != null) {
                player.sendMessage(Component.text("勝利: ").color(NamedTextColor.WHITE).append(Component.text(winner.displayName).color(winner.color)))
            } else {
                player.sendMessage(Component.text("引き分け").color(NamedTextColor.YELLOW))
            }
            player.sendMessage(Component.text("スコア: ").color(NamedTextColor.WHITE)
                .append(Component.text("赤 ${score[Team.RED]} ").color(NamedTextColor.RED))
                .append(Component.text("- ").color(NamedTextColor.WHITE))
                .append(Component.text("青 ${score[Team.BLUE]}").color(NamedTextColor.BLUE)))
            
            // チーム統計
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(Component.text("チーム統計:").color(NamedTextColor.YELLOW))
            
            // 赤チーム
            player.sendMessage(Component.text("赤チーム:").color(NamedTextColor.RED))
            val redPlayers = getTeamPlayers(Team.RED)
            redPlayers.forEach { p ->
                player.sendMessage(Component.text("  - ${p.name}", NamedTextColor.WHITE))
            }
            
            // 青チーム
            player.sendMessage(Component.text("青チーム:").color(NamedTextColor.BLUE))
            val bluePlayers = getTeamPlayers(Team.BLUE)
            bluePlayers.forEach { p ->
                player.sendMessage(Component.text("  - ${p.name}", NamedTextColor.WHITE))
            }
            
            // 通貨情報
            if (match != null) {
                player.sendMessage(Component.text("", NamedTextColor.WHITE))
                player.sendMessage(Component.text("チーム資金:").color(NamedTextColor.YELLOW))
                player.sendMessage(Component.text("赤チーム: ").color(NamedTextColor.RED)
                    .append(Component.text("${match!!.getTeamCurrency(Team.RED)}G").color(NamedTextColor.YELLOW)))
                player.sendMessage(Component.text("青チーム: ").color(NamedTextColor.BLUE)
                    .append(Component.text("${match!!.getTeamCurrency(Team.BLUE)}G").color(NamedTextColor.YELLOW)))
            }
            
            player.sendMessage(Component.text("===============================").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
        }
    }
}