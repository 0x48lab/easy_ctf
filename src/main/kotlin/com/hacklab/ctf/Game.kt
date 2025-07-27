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
    var world: World  // var に変更してテンポラリワールドを設定可能に
) {
    // テンポラリワールド
    private var tempWorld: World? = null
    private var originalWorld: World = world  // 元のワールドを保持
    var state = GameState.WAITING
    var phase = GamePhase.BUILD
    
    // マッチシステム参照
    private var matchWrapper: MatchWrapper? = null
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
    var autoStartEnabled = plugin.config.getBoolean("default-game.auto-start-enabled", false)
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
    
    // プレイヤー統計
    val playerDeaths = mutableMapOf<UUID, Int>() // プレイヤー -> 死亡回数
    val playerKills = mutableMapOf<UUID, Int>() // プレイヤー -> キル数
    val killStreaks = mutableMapOf<UUID, Int>() // プレイヤー -> 現在のキルストリーク
    val playerCaptures = mutableMapOf<UUID, Int>() // プレイヤー -> 旗キャプチャー数
    val playerFlagPickups = mutableMapOf<UUID, Int>() // プレイヤー -> 旗取得数
    val playerFlagDefends = mutableMapOf<UUID, Int>() // プレイヤー -> 旗キャリアキル数（防衛）
    val playerMoneySpent = mutableMapOf<UUID, Int>() // プレイヤー -> 使用金額
    val playerBlocksPlaced = mutableMapOf<UUID, Int>() // プレイヤー -> ブロック設置数
    val playerAssists = mutableMapOf<UUID, Int>() // プレイヤー -> アシスト数
    
    // アシスト管理
    val damageTracking = mutableMapOf<UUID, MutableMap<UUID, Long>>() // 被害者 -> (攻撃者 -> 最終ダメージ時刻)
    val captureAssists = mutableMapOf<Team, MutableSet<UUID>>() // チーム -> キャプチャーに貢献したプレイヤー
    
    // ActionBarメッセージのクールダウン管理
    private val actionBarCooldown = mutableMapOf<UUID, Long>() // プレイヤー -> 次回表示可能時刻
    private val actionBarErrorDisplay = mutableMapOf<UUID, Pair<String, Long>>() // プレイヤー -> (エラーメッセージ, 表示終了時刻)
    
    // タスク
    private var gameTask: BukkitRunnable? = null
    
    // ゲッター
    val name: String get() = gameName
    
    fun setMatchContext(matchWrapper: MatchWrapper) {
        this.matchWrapper = matchWrapper
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
    
    fun updateFromConfig(config: com.hacklab.ctf.config.GameConfig) {
        // 位置情報は後でワールド変換が必要なので、座標のみ保持
        redFlagLocation = config.redFlagLocation?.let { loc ->
            Location(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        blueFlagLocation = config.blueFlagLocation?.let { loc ->
            Location(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        redSpawnLocation = config.redSpawnLocation?.let { loc ->
            Location(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        blueSpawnLocation = config.blueSpawnLocation?.let { loc ->
            Location(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        buildDuration = config.buildDuration
        combatDuration = config.combatDuration
        buildPhaseGameMode = config.buildPhaseGameMode
        minPlayers = config.minPlayers
        maxPlayersPerTeam = config.maxPlayersPerTeam
        autoStartEnabled = config.autoStartEnabled
    }
    
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
        matchWrapper?.players?.put(player.uniqueId, player)
        
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
        matchWrapper?.players?.remove(player.uniqueId)
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
        // 自動開始が無効な場合は何もしない
        if (!autoStartEnabled) return
        
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
        
        // テンポラリワールドを作成
        val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
        plugin.logger.info("[Game] テンポラリワールドを作成中: $gameName")
        tempWorld = worldManager.createTempWorld(gameName)
        
        if (tempWorld == null) {
            plugin.logger.warning("[Game] テンポラリワールドの作成に失敗しました")
            getAllPlayers().forEach {
                it.sendMessage(Component.text("テンポラリワールドの作成に失敗しました", NamedTextColor.RED))
            }
            state = GameState.WAITING
            return false
        }
        
        plugin.logger.info("[Game] テンポラリワールド作成成功: ${tempWorld!!.name}")
        
        // ワールドを切り替え
        world = tempWorld!!
        
        // マップを復元
        val gameManager = plugin.gameManager as com.hacklab.ctf.managers.GameManager
        val mapManager = com.hacklab.ctf.map.CompressedMapManager(plugin)
        
        // 保存されたマップがある場合は復元
        if (mapManager.hasMap(gameName)) {
            plugin.logger.info("[Game] 保存されたマップを復元中...")
            if (!gameManager.resetGameMap(gameName, tempWorld)) {
                plugin.logger.warning("[Game] マップの復元に失敗しました")
            } else {
                plugin.logger.info("[Game] マップの復元が完了しました")
            }
        } else {
            plugin.logger.info("[Game] 保存されたマップが見つかりません: $gameName")
        }
        
        // 位置情報をテンポラリワールドに更新
        updateLocationsToWorld(tempWorld!!)
        
        // 通貨を初期化
        initializeCurrency()
        
        // BossBar作成
        bossBar = Bukkit.createBossBar(
            "建築フェーズ - 残り時間: ${formatTime(currentPhaseTime)}",
            BarColor.GREEN,
            BarStyle.SOLID
        )
        
        // プレイヤー統計をリセット
        playerDeaths.clear()
        playerKills.clear()
        killStreaks.clear()
        playerCaptures.clear()
        playerFlagPickups.clear()
        playerFlagDefends.clear()
        playerMoneySpent.clear()
        playerBlocksPlaced.clear()
        playerAssists.clear()
        
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
        
        // マッチモードで、かつ最終ゲームでない場合は短縮
        currentPhaseTime = if (matchWrapper != null && !matchWrapper!!.isMatchComplete()) {
            plugin.config.getInt("default-phases.intermediate-result-duration", 15)  // 中間結果
        } else {
            resultDuration  // 最終結果は通常の時間
        }
        
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
            
            // 元のワールドに転送
            val mainWorld = originalWorld ?: Bukkit.getWorlds()[0]
            player.teleport(mainWorld.spawnLocation)
            
            player.sendMessage(Component.text("ゲームが終了しました", NamedTextColor.YELLOW))
            
            // GameManagerからプレイヤーを削除
            val gameManager = plugin.gameManager as com.hacklab.ctf.managers.GameManager
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
        
        // テンポラリワールドをクリーンアップ
        if (tempWorld != null) {
            val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
            worldManager.cleanupTempWorld(gameName)
            tempWorld = null
            
            // ワールドを元に戻す
            world = originalWorld
        }
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
    
    private fun updateLocationsToWorld(newWorld: World) {
        // 位置情報を新しいワールドに更新
        redFlagLocation?.let {
            redFlagLocation = Location(newWorld, it.x, it.y, it.z, it.yaw, it.pitch)
        }
        blueFlagLocation?.let {
            blueFlagLocation = Location(newWorld, it.x, it.y, it.z, it.yaw, it.pitch)
        }
        redSpawnLocation?.let {
            redSpawnLocation = Location(newWorld, it.x, it.y, it.z, it.yaw, it.pitch)
        }
        blueSpawnLocation?.let {
            blueSpawnLocation = Location(newWorld, it.x, it.y, it.z, it.yaw, it.pitch)
        }
        
        plugin.logger.info("[Game] 位置情報を ${newWorld.name} ワールドに更新しました")
    }
    
    private fun teleportToSpawn(player: Player, team: Team) {
        val spawnLocation = when (team) {
            Team.RED -> redSpawnLocation ?: redFlagLocation
            Team.BLUE -> blueSpawnLocation ?: blueFlagLocation
        }
        
        if (spawnLocation != null) {
            player.teleport(spawnLocation)
            plugin.logger.info("[Game] ${player.name} を ${spawnLocation.world?.name} ワールドにテレポート")
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
        
        // フェーズごとに異なる表示
        when (phase) {
            GamePhase.BUILD -> {
                // 建築フェーズ
                obj.getScore("§e§l[建築] §f残り: ${formatTime(currentPhaseTime)}").score = line--
                obj.getScore(" ").score = line--
                
                // チーム通貨
                if (matchWrapper != null) {
                    obj.getScore("§c赤チーム: §e${matchWrapper!!.getTeamCurrency(Team.RED)}G").score = line--
                    obj.getScore("§9青チーム: §e${matchWrapper!!.getTeamCurrency(Team.BLUE)}G").score = line--
                } else {
                    obj.getScore("§c赤チーム: §e${getTeamCurrency(Team.RED)}G").score = line--
                    obj.getScore("§9青チーム: §e${getTeamCurrency(Team.BLUE)}G").score = line--
                }
            }
            
            GamePhase.COMBAT -> {
                // 戦闘フェーズ
                obj.getScore("§c§l[戦闘] §f残り: ${formatTime(currentPhaseTime)}").score = line--
                obj.getScore(" ").score = line--
                
                // スコア
                obj.getScore("§c赤: ${score[Team.RED] ?: 0} §f- §9青: ${score[Team.BLUE] ?: 0}").score = line--
                obj.getScore("  ").score = line--
                
                // チーム通貨
                if (matchWrapper != null) {
                    obj.getScore("§c赤チーム: §e${matchWrapper!!.getTeamCurrency(Team.RED)}G").score = line--
                    obj.getScore("§9青チーム: §e${matchWrapper!!.getTeamCurrency(Team.BLUE)}G").score = line--
                } else {
                    obj.getScore("§c赤チーム: §e${getTeamCurrency(Team.RED)}G").score = line--
                    obj.getScore("§9青チーム: §e${getTeamCurrency(Team.BLUE)}G").score = line--
                }
                obj.getScore("   ").score = line--
                
                // 旗の状態（持っている場合のみ）
                if (redFlagCarrier != null) {
                    val carrier = Bukkit.getPlayer(redFlagCarrier!!)
                    obj.getScore("§c赤旗: §e${carrier?.name ?: "不明"}が所持").score = line--
                }
                if (blueFlagCarrier != null) {
                    val carrier = Bukkit.getPlayer(blueFlagCarrier!!)
                    obj.getScore("§9青旗: §e${carrier?.name ?: "不明"}が所持").score = line--
                }
            }
            
            GamePhase.RESULT -> {
                // リザルトフェーズ
                obj.getScore("§6§l[結果発表]").score = line--
                obj.getScore(" ").score = line--
                
                // 最終スコア
                obj.getScore("§c赤チーム: §f${score[Team.RED] ?: 0}").score = line--
                obj.getScore("§9青チーム: §f${score[Team.BLUE] ?: 0}").score = line--
                obj.getScore("  ").score = line--
                
                // 勝者
                val winner = when {
                    score[Team.RED]!! > score[Team.BLUE]!! -> "§c赤チームの勝利！"
                    score[Team.BLUE]!! > score[Team.RED]!! -> "§9青チームの勝利！"
                    else -> "§e引き分け！"
                }
                obj.getScore(winner).score = line--
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
        val matchInfo = matchWrapper?.let { m ->
            val wins = m.matchWins
            "[${m.currentGameNumber}/${m.config.matchTarget}] | " + "赤${wins[Team.RED]}勝 青${wins[Team.BLUE]}勝 | "
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
        
        // アイテムのメタデータから旗のチームを判別
        val itemMeta = item.itemStack.itemMeta
        val displayName = itemMeta?.displayName()
        
        // どちらのチームの旗かを判定（displayNameとcustomNameの両方をチェック）
        val flagTeam = when {
            displayName != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName).contains("赤") -> Team.RED
            displayName != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName).contains("青") -> Team.BLUE
            item.customName() != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.customName()!!).contains("赤") -> Team.RED
            item.customName() != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.customName()!!).contains("青") -> Team.BLUE
            else -> {
                plugin.logger.warning("Failed to determine flag team for item: displayName=${displayName}, customName=${item.customName()}")
                return false
            }
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
        
        // キャプチャー統計を記録
        playerCaptures[player.uniqueId] = (playerCaptures[player.uniqueId] ?: 0) + 1
        
        // 通貨報酬（マッチがある場合もない場合も）
        val captureReward = plugin.config.getInt("currency.capture-reward", 30)
        addTeamCurrency(team, captureReward, "${player.name}が旗をキャプチャー")
        
        // キャプチャーアシスト報酬
        val assists = captureAssists[team] ?: mutableSetOf()
        assists.remove(player.uniqueId) // キャプチャーした本人は除外
        
        if (assists.isNotEmpty()) {
            val assistReward = plugin.config.getInt("currency.capture-assist-reward", 15)
            assists.forEach { assisterId ->
                val assister = Bukkit.getPlayer(assisterId)
                if (assister != null) {
                    assister.sendMessage(Component.text("キャプチャーアシスト! +${assistReward}G", NamedTextColor.GREEN))
                }
            }
            // チーム全体に一度だけアシスト報酬を追加
            if (assists.size > 0) {
                addTeamCurrency(team, assistReward * assists.size, "${assists.size}名がキャプチャーアシスト")
            }
        }
        
        // アシストリストをクリア
        captureAssists[team]?.clear()
        
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
        return matchWrapper?.getTeamCurrency(team) ?: teamCurrency[team] ?: 0
    }
    
    fun addTeamCurrency(team: Team, amount: Int, reason: String = "") {
        if (matchWrapper != null) {
            matchWrapper!!.addTeamCurrency(team, amount)
        } else {
            val current = teamCurrency[team] ?: 0
            teamCurrency[team] = current + amount
        }
        
        // チームメンバーに通知
        if (reason.isNotEmpty()) {
            getTeamPlayers(team).forEach { player ->
                player.sendMessage(Component.text("[チーム] $reason (+${amount}G)", NamedTextColor.GREEN))
            }
        }
        
        updateScoreboard()
    }
    
    fun spendTeamCurrency(team: Team, amount: Int, player: Player, itemName: String): Boolean {
        if (matchWrapper != null) {
            return matchWrapper!!.useTeamCurrency(team, amount)
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
            matchWrapper?.let { m ->
                player.sendMessage(Component.text(m.getMatchStatus()).color(NamedTextColor.YELLOW))
                val wins = m.matchWins
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
            if (matchWrapper != null) {
                player.sendMessage(Component.text("", NamedTextColor.WHITE))
                player.sendMessage(Component.text("チーム資金:").color(NamedTextColor.YELLOW))
                player.sendMessage(Component.text("赤チーム: ").color(NamedTextColor.RED)
                    .append(Component.text("${matchWrapper!!.getTeamCurrency(Team.RED)}G").color(NamedTextColor.YELLOW)))
                player.sendMessage(Component.text("青チーム: ").color(NamedTextColor.BLUE)
                    .append(Component.text("${matchWrapper!!.getTeamCurrency(Team.BLUE)}G").color(NamedTextColor.YELLOW)))
            }
            
            player.sendMessage(Component.text("===============================").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
        }
        
        // MVP発表（マッチの最終ゲームまたは単独ゲームの場合のみ）
        if (matchWrapper == null || (matchWrapper != null && matchWrapper!!.isMatchComplete())) {
            displayMVP()
        }
    }
    
    private fun displayMVP() {
        // MVP計算
        val mvpScores = mutableMapOf<UUID, Double>()
        val allPlayers = getAllPlayers()
        
        allPlayers.forEach { player ->
            val uuid = player.uniqueId
            var score = 0.0
            
            // キル数 (1キル = 10ポイント)
            score += (playerKills[uuid] ?: 0) * 10.0
            
            // アシスト (1アシスト = 5ポイント)
            score += (playerAssists[uuid] ?: 0) * 5.0
            
            // 旗キャプチャー (1キャプチャー = 30ポイント)
            score += (playerCaptures[uuid] ?: 0) * 30.0
            
            // 旗取得 (1取得 = 15ポイント)
            score += (playerFlagPickups[uuid] ?: 0) * 15.0
            
            // 旗防衛 (1防衛 = 20ポイント)
            score += (playerFlagDefends[uuid] ?: 0) * 20.0
            
            // お金使用 (100G使用 = 10ポイント)
            score += (playerMoneySpent[uuid] ?: 0) * 0.1
            
            // ブロック設置 (100ブロック = 5ポイント)
            score += (playerBlocksPlaced[uuid] ?: 0) * 0.05
            
            // デス数ペナルティ (1デス = -5ポイント)
            score -= (playerDeaths[uuid] ?: 0) * 5.0
            
            mvpScores[uuid] = score
        }
        
        // MVP決定
        val mvp = mvpScores.maxByOrNull { it.value }
        if (mvp != null && mvp.value > 0) {
            val mvpPlayer = Bukkit.getPlayer(mvp.key)
            if (mvpPlayer != null) {
                // MVP発表
                getAllPlayers().forEach { player ->
                    player.sendMessage(Component.text("", NamedTextColor.WHITE))
                    player.sendMessage(Component.text("★★★ MVP発表 ★★★").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    player.sendMessage(Component.text("MVP: ").color(NamedTextColor.YELLOW)
                        .append(Component.text(mvpPlayer.name).color(NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                        .append(Component.text(" (スコア: ${String.format("%.1f", mvp.value)})").color(NamedTextColor.WHITE)))
                    
                    // MVP統計詳細
                    player.sendMessage(Component.text("", NamedTextColor.WHITE))
                    player.sendMessage(Component.text("MVP統計:").color(NamedTextColor.YELLOW))
                    
                    val uuid = mvpPlayer.uniqueId
                    val kills = playerKills[uuid] ?: 0
                    val assists = playerAssists[uuid] ?: 0
                    val captures = playerCaptures[uuid] ?: 0
                    val flagPickups = playerFlagPickups[uuid] ?: 0
                    val flagDefends = playerFlagDefends[uuid] ?: 0
                    val moneySpent = playerMoneySpent[uuid] ?: 0
                    val blocks = playerBlocksPlaced[uuid] ?: 0
                    val deaths = playerDeaths[uuid] ?: 0
                    
                    if (kills > 0) player.sendMessage(Component.text("  キル: $kills").color(NamedTextColor.GREEN))
                    if (assists > 0) player.sendMessage(Component.text("  アシスト: $assists").color(NamedTextColor.GREEN))
                    if (captures > 0) player.sendMessage(Component.text("  旗キャプチャー: $captures").color(NamedTextColor.GOLD))
                    if (flagPickups > 0) player.sendMessage(Component.text("  旗取得: $flagPickups").color(NamedTextColor.YELLOW))
                    if (flagDefends > 0) player.sendMessage(Component.text("  旗防衛: $flagDefends").color(NamedTextColor.AQUA))
                    if (moneySpent > 0) player.sendMessage(Component.text("  使用金額: ${moneySpent}G").color(NamedTextColor.YELLOW))
                    if (blocks > 0) player.sendMessage(Component.text("  ブロック設置: $blocks").color(NamedTextColor.WHITE))
                    if (deaths > 0) player.sendMessage(Component.text("  デス数: $deaths").color(NamedTextColor.RED))
                    
                    player.sendMessage(Component.text("★★★★★★★★★★★★★★★").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    
                    // MVPにタイトル表示
                    if (player == mvpPlayer) {
                        player.showTitle(Title.title(
                            Component.text("MVP！").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                            Component.text("おめでとうございます！").color(NamedTextColor.YELLOW),
                            Title.Times.times(
                                Duration.ofMillis(500),
                                Duration.ofSeconds(3),
                                Duration.ofMillis(500)
                            )
                        ))
                    }
                }
                
                // 効果音
                getAllPlayers().forEach { p ->
                    p.playSound(p.location, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
                }
            }
        }
        
        // 各項目のトップを表示
        displayTopPlayers()
    }
    
    private fun displayTopPlayers() {
        val allPlayers = getAllPlayers()
        if (allPlayers.isEmpty()) return
        
        getAllPlayers().forEach { player ->
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(Component.text("===== 各部門トップ =====").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            
            // キル数トップ
            val topKiller = playerKills.maxByOrNull { it.value }
            if (topKiller != null && topKiller.value > 0) {
                val killerName = Bukkit.getPlayer(topKiller.key)?.name ?: "不明"
                player.sendMessage(Component.text("🗡 最多キル: ").color(NamedTextColor.RED)
                    .append(Component.text("$killerName (${topKiller.value}キル)").color(NamedTextColor.WHITE)))
            }
            
            // 旗キャプチャートップ
            val topCapturer = playerCaptures.maxByOrNull { it.value }
            if (topCapturer != null && topCapturer.value > 0) {
                val capturerName = Bukkit.getPlayer(topCapturer.key)?.name ?: "不明"
                player.sendMessage(Component.text("🚩 最多キャプチャー: ").color(NamedTextColor.GOLD)
                    .append(Component.text("$capturerName (${topCapturer.value}回)").color(NamedTextColor.WHITE)))
            }
            
            // 旗防衛トップ
            val topDefender = playerFlagDefends.maxByOrNull { it.value }
            if (topDefender != null && topDefender.value > 0) {
                val defenderName = Bukkit.getPlayer(topDefender.key)?.name ?: "不明"
                player.sendMessage(Component.text("🛡 最多防衛: ").color(NamedTextColor.AQUA)
                    .append(Component.text("$defenderName (${topDefender.value}回)").color(NamedTextColor.WHITE)))
            }
            
            // アシストトップ
            val topAssister = playerAssists.maxByOrNull { it.value }
            if (topAssister != null && topAssister.value > 0) {
                val assisterName = Bukkit.getPlayer(topAssister.key)?.name ?: "不明"
                player.sendMessage(Component.text("🤝 最多アシスト: ").color(NamedTextColor.GREEN)
                    .append(Component.text("$assisterName (${topAssister.value}回)").color(NamedTextColor.WHITE)))
            }
            
            // 建築トップ
            val topBuilder = playerBlocksPlaced.maxByOrNull { it.value }
            if (topBuilder != null && topBuilder.value > 0) {
                val builderName = Bukkit.getPlayer(topBuilder.key)?.name ?: "不明"
                player.sendMessage(Component.text("🏗 最多建築: ").color(NamedTextColor.YELLOW)
                    .append(Component.text("$builderName (${topBuilder.value}ブロック)").color(NamedTextColor.WHITE)))
            }
            
            // 最多消費
            val topSpender = playerMoneySpent.maxByOrNull { it.value }
            if (topSpender != null && topSpender.value > 0) {
                val spenderName = Bukkit.getPlayer(topSpender.key)?.name ?: "不明"
                player.sendMessage(Component.text("💰 最多消費: ").color(NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text("$spenderName (${topSpender.value}G)").color(NamedTextColor.WHITE)))
            }
            
            // 最少デス（1人以上いる場合のみ）
            if (playerDeaths.isNotEmpty()) {
                val leastDeaths = playerDeaths.filter { 
                    val p = Bukkit.getPlayer(it.key)
                    p != null && getAllPlayers().contains(p)
                }.minByOrNull { it.value }
                
                if (leastDeaths != null) {
                    val survivorName = Bukkit.getPlayer(leastDeaths.key)?.name ?: "不明"
                    player.sendMessage(Component.text("💀 最少デス: ").color(NamedTextColor.DARK_GREEN)
                        .append(Component.text("$survivorName (${leastDeaths.value}回)").color(NamedTextColor.WHITE)))
                }
            }
            
            player.sendMessage(Component.text("=======================").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
        }
    }
}