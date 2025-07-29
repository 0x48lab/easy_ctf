package com.hacklab.ctf

import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import com.hacklab.ctf.utils.MatchMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.block.BlockFace
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
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
    private var mapCenterLocation: Location? = null
    
    // 設定
    var autoStartEnabled = plugin.config.getBoolean("default-game.auto-start-enabled", false)
    var minPlayers = plugin.config.getInt("default-game.min-players", 2)
    var maxPlayersPerTeam = plugin.config.getInt("default-game.max-players-per-team", 10)
    var respawnDelay = plugin.config.getInt("default-game.respawn-delay-base", 10)
    var buildDuration = plugin.config.getInt("default-phases.build-duration", 300)
    var combatDuration = plugin.config.getInt("default-phases.combat-duration", 600)
    var resultDuration = plugin.config.getInt("default-phases.result-duration", 60)
    var buildPhaseGameMode = plugin.config.getString("default-phases.build-phase-gamemode", "SURVIVAL")!!
    
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
    
    // ブロック設置制限管理
    val teamPlacedBlocks = mutableMapOf<Team, MutableSet<Location>>() // チーム -> 設置したブロックの座標
    
    // ブロック接続ツリー管理
    data class BlockNode(
        val location: Location,
        val parent: Location?,
        val children: MutableSet<Location> = mutableSetOf()
    )
    val teamBlockTrees = mutableMapOf<Team, MutableMap<Location, BlockNode>>() // チーム -> (座標 -> ノード)
    
    // タスク
    private var gameTask: BukkitRunnable? = null
    private var suffocationTask: BukkitRunnable? = null
    private var scoreboardUpdateTask: BukkitRunnable? = null
    private var actionBarTask: BukkitRunnable? = null
    private val spawnProtectionTasks = mutableMapOf<UUID, BukkitRunnable>()
    private val respawnTasks = mutableMapOf<UUID, BukkitRunnable>()
    
    // ゲッター
    val name: String get() = gameName
    
    // チームカラーのブロック定義
    companion object {
        val TEAM_BLOCKS = mapOf(
            Team.RED to setOf(
                Material.RED_CONCRETE,
                Material.RED_STAINED_GLASS
            ),
            Team.BLUE to setOf(
                Material.BLUE_CONCRETE,
                Material.BLUE_STAINED_GLASS
            )
        )
        
        val NEUTRAL_BLOCK = Material.WHITE_CONCRETE // 接続が切れたブロック
    }
    
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
        
        // マップ中央位置を計算
        calculateMapCenter()
    }
    
    fun addPlayer(player: Player, team: Team? = null): Boolean {
        if (state != GameState.WAITING) {
            plugin.logger.warning("Player ${player.name} cannot join game $name: state is $state")
            when (state) {
                GameState.STARTING, GameState.RUNNING -> {
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game-states.already-started")))
                }
                GameState.ENDING -> {
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("game-states.already-ending")))
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
                player.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.team-full-red")))
                return false
            }
            redTeam.add(player.uniqueId)
        } else {
            if (blueTeam.size >= maxPlayersPerTeam) {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.team-full-blue")))
                return false
            }
            blueTeam.add(player.uniqueId)
        }
        
        // スコアボード表示
        setupScoreboard(player)
        
        player.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.game-joined", 
            "game" to gameName,
            "color" to selectedTeam.getChatColor(),
            "team" to selectedTeam.displayName
        )))
        
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
        
        player.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.game-left", "game" to gameName)))
        
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
            GamePhase.INTERMISSION -> {}
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
                    it.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.auto-start-cancelled")))
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
                            it.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.auto-start-cancelled")))
                        }
                        return
                    }
                    
                    if (autoStartCountdown % 10 == 0 || autoStartCountdown <= 5) {
                        getAllPlayers().forEach {
                            it.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.countdown-message", "seconds" to autoStartCountdown.toString())))
                        }
                    }
                    
                    autoStartCountdown--
                    
                    // スコアボード更新（カウントダウン表示のため）
                    updateScoreboard()
                }
            }.runTaskTimer(plugin, 0L, 20L)
        }
    }
    
    fun start(): Boolean {
        plugin.logger.info("[Game] Attempting to start game $name, current state: $state, phase: $phase")
        
        if (state != GameState.WAITING) {
            plugin.logger.warning("Game $name cannot start: current state is $state")
            getAllPlayers().forEach {
                it.sendMessage(Component.text(plugin.languageManager.getMessage("game-states.already-state", "state" to state.toString())))
            }
            return false
        }
        
        // 最小人数チェック（マッチの場合は最小人数チェックをスキップ）
        if (matchWrapper == null && redTeam.size + blueTeam.size < minPlayers) {
            getAllPlayers().forEach {
                it.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.min-players-required", "min" to minPlayers.toString())))
            }
            return false
        }
        
        // 必須設定チェック
        if (redFlagLocation == null || blueFlagLocation == null) {
            getAllPlayers().forEach {
                it.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.flags-not-set")))
            }
            return false
        }
        
        state = GameState.STARTING
        phase = GamePhase.BUILD
        currentPhaseTime = buildDuration
        
        // マッチモードで既にテンポラリワールドがある場合は再利用
        val isMatchMode = matchWrapper != null && matchWrapper!!.isActive
        val needNewWorld = tempWorld == null || !isMatchMode
        
        if (needNewWorld) {
            // テンポラリワールドを作成
            val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
            tempWorld = worldManager.createTempWorld(gameName)
            
            if (tempWorld == null) {
                plugin.logger.warning("[Game] テンポラリワールドの作成に失敗しました")
                getAllPlayers().forEach {
                    it.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.temp-world-failed")))
                }
                state = GameState.WAITING
                return false
            }
            
        } else {
        }
        
        // ワールドを切り替え
        world = tempWorld!!
        
        // マップを復元（マッチの最初のゲームのみ）
        if (matchWrapper == null || matchWrapper!!.currentGameNumber == 1) {
            val gameManager = plugin.gameManager as com.hacklab.ctf.managers.GameManager
            val mapManager = com.hacklab.ctf.map.CompressedMapManager(plugin)
            
            // 保存されたマップがある場合は復元
            if (mapManager.hasMap(gameName)) {
                if (!gameManager.resetGameMap(gameName, tempWorld)) {
                    plugin.logger.warning("[Game] マップの復元に失敗しました")
                } else {
                    plugin.logger.info("[Game] マップを復元しました")
                }
            } else {
                plugin.logger.info("[Game] 保存されたマップがありません")
            }
        } else {
            plugin.logger.info("[Game] マッチ継続中のため、マップ復元をスキップ")
        }
        
        // 位置情報をテンポラリワールドに更新
        updateLocationsToWorld(tempWorld!!)
        
        // 通貨を初期化
        initializeCurrency()
        
        // BossBar作成
        bossBar = Bukkit.createBossBar(
            plugin.languageManager.getMessage("phase-extended.build-time-format", "time" to formatTime(currentPhaseTime)),
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
            
            // アクションバーをクリア
            player.sendActionBar(Component.empty())
            
            // ゲームモード設定
            val targetGameMode = GameMode.valueOf(buildPhaseGameMode)
            player.gameMode = targetGameMode
            
            // スポーン地点に転送
            teleportToSpawn(player, team)
            
            // 建築フェーズアイテム配布
            giveBuildPhaseItems(player, team)
            
            // BossBar追加
            bossBar?.addPlayer(player)
            
            // タイトル表示
            player.showTitle(Title.title(
                Component.text(plugin.languageManager.getMessage("phase-extended.game-start-title")),
                Component.text(plugin.languageManager.getMessage("phase-extended.game-start-subtitle")),
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
        
        // マッチモードでない場合、またはマッチの最初のゲームの場合のみブロック記録をクリア
        if (matchWrapper == null || matchWrapper!!.currentGameNumber == 1) {
            // ブロック設置記録をクリア
            teamPlacedBlocks.clear()
            teamPlacedBlocks[Team.RED] = mutableSetOf()
            teamPlacedBlocks[Team.BLUE] = mutableSetOf()
            
            // ブロックツリーをクリア
            teamBlockTrees.clear()
            teamBlockTrees[Team.RED] = mutableMapOf()
            teamBlockTrees[Team.BLUE] = mutableMapOf()
        }
        
        state = GameState.RUNNING
        
        plugin.logger.info("[Game] Starting game loop for $name")
        
        // ゲームループ開始
        startGameLoop()
        
        return true
    }
    
    private fun startGameLoop() {
        // 既存のタスクをキャンセル
        gameTask?.cancel()
        gameTask = null
        
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
                        GamePhase.INTERMISSION -> endGame()
                    }
                }
                
                // UI更新
                updateBossBar()
                updateScoreboard()
            }
        }
        gameTask?.runTaskTimer(plugin, 0L, 20L)
        
        // アクションバー更新タスクを別で管理（5ティックごと）
        actionBarTask?.cancel()
        actionBarTask = object : BukkitRunnable() {
            override fun run() {
                if (state != GameState.RUNNING) {
                    cancel()
                    actionBarTask = null
                    return
                }
                updateActionBarGuides()
            }
        }
        actionBarTask?.runTaskTimer(plugin, 0L, 5L)
    }
    
    private fun transitionToCombatPhase() {
        phase = GamePhase.COMBAT
        currentPhaseTime = combatDuration
        
        // リスポーンタスクをクリア（建築フェーズからの移行時）
        respawnTasks.values.forEach { task ->
            plugin.logger.info("[Respawn] Cancelling respawn task during combat phase transition")
            task.cancel()
        }
        respawnTasks.clear()
        
        bossBar?.setTitle(plugin.languageManager.getMessage("phase-extended.combat-time-format", "time" to formatTime(currentPhaseTime)))
        bossBar?.color = BarColor.RED
        
        // 窒息チェックタスクを開始
        startSuffocationTask()
        
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
                Component.text(plugin.languageManager.getMessage("phase-extended.combat-start-title")),
                Component.text(plugin.languageManager.getMessage("phase-extended.combat-start-subtitle")),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(500)
                )
            ))
        }
    }
    
    private fun transitionToResultPhase() {
        phase = GamePhase.INTERMISSION
        
        // リスポーンタスクをキャンセル
        respawnTasks.values.forEach { task ->
            plugin.logger.info("[Respawn] Cancelling respawn task during phase transition")
            task.cancel()
        }
        respawnTasks.clear()
        
        // マッチモードで、かつ最終ゲームでない場合は短縮
        currentPhaseTime = if (matchWrapper != null && !matchWrapper!!.isMatchComplete()) {
            plugin.config.getInt("default-phases.intermediate-result-duration", 15)  // マッチ間の作戦会議
        } else {
            resultDuration  // 最終ゲーム後は通常の時間
        }
        
        // 戦闘フェーズ終了ボーナス
        val phaseEndBonus = plugin.config.getInt("currency.phase-end-bonus", 50)
        val bonusReason = plugin.languageManager.getMessage("phase-extended.phase-end-bonus")
        addTeamCurrency(Team.RED, phaseEndBonus, bonusReason)
        addTeamCurrency(Team.BLUE, phaseEndBonus, bonusReason)
        
        val winner = when {
            score[Team.RED]!! > score[Team.BLUE]!! -> Team.RED
            score[Team.BLUE]!! > score[Team.RED]!! -> Team.BLUE
            else -> null
        }
        
        bossBar?.setTitle("試合終了！")
        bossBar?.color = BarColor.YELLOW
        
        // ゲームレポートを作成
        displayGameReport(winner)
        
        getAllPlayers().forEach { player ->
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード変更
            player.gameMode = GameMode.SPECTATOR
            
            // 勝負表示
            if (winner != null) {
                player.showTitle(Title.title(
                    Component.text(plugin.languageManager.getMessage("phase-extended.winner-title", 
                        "color" to winner.getChatColor(),
                        "team" to winner.displayName
                    )),
                    Component.text(plugin.languageManager.getMessage("phase-extended.score-display",
                        "red" to score[Team.RED].toString(),
                        "blue" to score[Team.BLUE].toString()
                    )),
                    Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500)
                    )
                ))
            } else {
                player.showTitle(Title.title(
                    Component.text(plugin.languageManager.getMessage("phase-extended.draw-title")),
                    Component.text(plugin.languageManager.getMessage("phase-extended.score-display",
                        "red" to score[Team.RED].toString(),
                        "blue" to score[Team.BLUE].toString()
                    )),
                    Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500)
                    )
                ))
            }
        }
    }
    
    /**
     * マッチの次のゲームのために状態をリセット
     */
    fun resetForNextMatchGame() {
        plugin.logger.info("[Game] Resetting for next match game")
        
        // 全てのタスクをキャンセル
        gameTask?.cancel()
        suffocationTask?.cancel()
        scoreboardUpdateTask?.cancel()
        spawnProtectionTasks.values.forEach { it.cancel() }
        actionBarTask?.cancel()
        
        // リスポーンタスクをキャンセル
        respawnTasks.values.forEach { task ->
            plugin.logger.info("[Respawn] Cancelling respawn task during match reset")
            task.cancel()
        }
        
        // タスク参照をクリア
        gameTask = null
        suffocationTask = null
        scoreboardUpdateTask = null
        spawnProtectionTasks.clear()
        actionBarTask = null
        respawnTasks.clear()
        
        // ボスバーをリセット
        bossBar?.removeAll()
        bossBar = null
        
        // 状態をリセット
        state = GameState.WAITING
        phase = GamePhase.BUILD
        currentPhaseTime = 0
        autoStartCountdown = -1
        
        // ゲーム状態データはリセット
        score.clear()
        score[Team.RED] = 0
        score[Team.BLUE] = 0
        droppedFlags.clear()
        spawnProtection.clear()
        actionBarCooldown.clear()
        actionBarErrorDisplay.clear()
        redFlagCarrier = null
        blueFlagCarrier = null
        
        // 旗とスポーン装飾を再設置
        setupFlags()
        setupSpawnAreas()
        
        plugin.logger.info("[Game] Reset complete, state is now: $state")
    }
    
    fun stop() {
        plugin.logger.info("Stopping game: $gameName, current state: $state, matchWrapper: ${matchWrapper != null}")
        state = GameState.ENDING
        
        // 全てのタスクをキャンセル
        gameTask?.cancel()
        suffocationTask?.cancel()
        scoreboardUpdateTask?.cancel()
        spawnProtectionTasks.values.forEach { it.cancel() }
        actionBarTask?.cancel()
        
        // リスポーンタスクをキャンセル
        respawnTasks.values.forEach { task ->
            plugin.logger.info("[Respawn] Cancelling respawn task during game stop")
            task.cancel()
        }
        
        // タスク参照をクリア
        gameTask = null
        suffocationTask = null
        scoreboardUpdateTask = null
        spawnProtectionTasks.clear()
        actionBarTask = null
        respawnTasks.clear()
        
        // マッチモードかどうかチェック
        val isMatchMode = matchWrapper != null && matchWrapper!!.isActive && !matchWrapper!!.isMatchComplete()
        
        // プレイヤーのUUIDリストをコピー（forEach中の変更を避けるため）
        val playerUUIDs = (redTeam + blueTeam).toList()
        
        // プレイヤー処理
        playerUUIDs.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
            // インベントリクリア
            player.inventory.clear()
            
            // 発光効果を解除
            player.isGlowing = false
            
            // 敵陣メタデータを削除
            player.removeMetadata("on_enemy_block", plugin)
            
            if (!isMatchMode) {
                // マッチモードでない場合のみ、元のワールドに転送してゲームから削除
                player.gameMode = GameMode.SURVIVAL
                
                // UI削除
                player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
                bossBar?.removePlayer(player)
                
                val mainWorld = originalWorld ?: Bukkit.getWorlds()[0]
                player.teleport(mainWorld.spawnLocation)
                
                player.sendMessage(Component.text(plugin.languageManager.getMessage("phase-extended.game-ended")))
                
                // GameManagerからプレイヤーを削除
                val gameManager = plugin.gameManager as com.hacklab.ctf.managers.GameManager
                gameManager.removePlayerFromGame(player)
            } else {
                // マッチモードの場合は、スポーン地点に戻してアドベンチャーモードに
                val team = getPlayerTeam(player.uniqueId)
                if (team != null) {
                    val spawnLocation = when (team) {
                        Team.RED -> redSpawnLocation ?: redFlagLocation
                        Team.BLUE -> blueSpawnLocation ?: blueFlagLocation
                    }
                    if (spawnLocation != null) {
                        player.teleport(spawnLocation)
                    }
                }
                player.gameMode = GameMode.ADVENTURE
                player.sendMessage(Component.text(plugin.languageManager.getMessage("phase-extended.wait-next-game")))
            }
        }
        
        // BossBar削除
        bossBar?.removeAll()
        bossBar = null
        
        // スコアボード削除
        scoreboard = null
        objective = null
        
        // 旗とスポーン装飾を削除（マッチモードでもクリアする）
        // ただし、マッチ継続中はプレイヤーが設置したブロックは保持
        cleanupGameBlocks()
        
        // データクリアの前に状態をリセット
        state = GameState.WAITING
        phase = GamePhase.BUILD
        currentPhaseTime = 0
        autoStartCountdown = -1
        
        // マッチモードの場合、プレイヤーデータは保持
        if (!isMatchMode) {
            // データクリア
            redTeam.clear()
            blueTeam.clear()
            disconnectedPlayers.clear()
        }
        
        // ゲーム状態データはリセット
        score.clear()
        score[Team.RED] = 0
        score[Team.BLUE] = 0
        droppedFlags.clear()
        spawnProtection.clear()
        actionBarCooldown.clear()
        actionBarErrorDisplay.clear()
        redFlagCarrier = null
        blueFlagCarrier = null
        
        // テンポラリワールドをクリーンアップ（マッチモードでは削除しない）
        if (!isMatchMode && tempWorld != null) {
            val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
            worldManager.cleanupTempWorld(gameName)
            tempWorld = null
            
            // ワールドを元に戻す
            world = originalWorld
        }
    }
    
    private fun endGame() {
        // 既に終了処理中の場合は何もしない
        if (state == GameState.ENDING) {
            plugin.logger.info("Game $gameName is already ending, skipping duplicate endGame call")
            return
        }
        
        plugin.logger.info("Game $gameName ended naturally, state: $state, matchWrapper: ${matchWrapper != null}")
        
        // 状態を終了中に設定（重複防止）
        state = GameState.ENDING
        
        // 勝者を決定
        val winner = getWinner()
        
        plugin.logger.info("Game $gameName winner: $winner, has callback: ${gameEndCallback != null}")
        
        // コールバックを実行（マッチがある場合は、マッチが次のゲームを管理）
        if (gameEndCallback != null) {
            plugin.logger.info("Invoking game end callback for match")
            gameEndCallback?.invoke(winner)
            // マッチモードの場合、stop()を呼ばない（次のゲームのために状態をリセット）
            if (matchWrapper != null && matchWrapper!!.isActive && !matchWrapper!!.isMatchComplete()) {
                plugin.logger.info("Match continues, not stopping game")
                // 状態はhandleMatchGameEndで適切にリセットされる
            } else {
                plugin.logger.info("Match complete or no match, stopping game")
                stop()
            }
        } else {
            // マッチがない場合のみ、ゲームを停止
            plugin.logger.info("No match callback, stopping game")
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
        
    }
    
    private fun teleportToSpawn(player: Player, team: Team) {
        val spawnLocation = when (team) {
            Team.RED -> redSpawnLocation ?: redFlagLocation
            Team.BLUE -> blueSpawnLocation ?: blueFlagLocation
        }
        
        if (spawnLocation != null) {
            // 相手チームの旗の位置を取得
            val targetFlagLocation = when (team) {
                Team.RED -> blueFlagLocation
                Team.BLUE -> redFlagLocation
            }
            
            // スポーン位置をクローンして、向きを設定
            val teleportLocation = spawnLocation.clone()
            
            if (targetFlagLocation != null) {
                // 相手の旗の方向を向くように計算
                val direction = targetFlagLocation.toVector().subtract(teleportLocation.toVector())
                
                // Yaw（水平方向の回転）を計算
                val yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z)).toFloat()
                
                // Pitch（上下の角度）は0に設定（水平に保つ）
                teleportLocation.yaw = yaw
                teleportLocation.pitch = 0f
            }
            
            player.teleport(teleportLocation)
        } else {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("spawn.not-set")))
            plugin.logger.warning("Game $name: No spawn location for team $team")
        }
    }
    
    private fun giveBuildPhaseItems(player: Player, team: Team) {
        val inv = player.inventory
        
        // 革の防具（チームカラー）
        giveColoredLeatherArmor(player, team)
        
        // チームカラーブロック（無限）をスロット0と1に固定
        val infiniteConcrete = ItemStack(
            when (team) {
                Team.RED -> Material.RED_CONCRETE
                Team.BLUE -> Material.BLUE_CONCRETE
            }
        ).apply {
            amount = 1
            itemMeta = itemMeta?.apply {
                displayName(Component.text(plugin.languageManager.getMessage("flag.concrete-name",
                    "color" to team.getChatColor(),
                    "team" to team.displayName
                )))
                lore(listOf(Component.text(plugin.languageManager.getMessage("flag.infinite-desc"))))
                // 無限フラグを設定
                persistentDataContainer.set(
                    NamespacedKey(plugin, "infinite_block"),
                    PersistentDataType.BOOLEAN,
                    true
                )
            }
        }
        
        val infiniteGlass = ItemStack(
            when (team) {
                Team.RED -> Material.RED_STAINED_GLASS
                Team.BLUE -> Material.BLUE_STAINED_GLASS
            }
        ).apply {
            amount = 1
            itemMeta = itemMeta?.apply {
                displayName(Component.text(plugin.languageManager.getMessage("flag.glass-name",
                    "color" to team.getChatColor(),
                    "team" to team.displayName
                )))
                lore(listOf(Component.text(plugin.languageManager.getMessage("flag.infinite-desc"))))
                // 無限フラグを設定
                persistentDataContainer.set(
                    NamespacedKey(plugin, "infinite_block"),
                    PersistentDataType.BOOLEAN,
                    true
                )
            }
        }
        
        player.inventory.setItem(0, infiniteConcrete)
        player.inventory.setItem(1, infiniteGlass)
        
        // ショップアイテムをホットバー9番目に配置
        val shopItem = plugin.shopManager.createShopItem()
        player.inventory.setItem(8, shopItem)
    }
    
    
    private fun giveCombatPhaseItems(player: Player, team: Team) {
        val inv = player.inventory
        
        // 革の防具（チームカラー）を再装備（防具をリセットして確実に着用）
        giveColoredLeatherArmor(player, team)
        
        // チーム識別用にプレイヤー名を色付け
        player.setDisplayName("${team.getChatColor()}${player.name}")
        player.setPlayerListName("${team.getChatColor()}${player.name}")
        
        // 戦闘フェーズではチームカラーブロックは配布しない
        
        // ショップアイテムをホットバー9番目に配置
        val shopItem = plugin.shopManager.createShopItem()
        player.inventory.setItem(8, shopItem)
    }
    
    private fun giveDefaultCombatItems(player: Player, team: Team) {
        val inv = player.inventory
        // 初期装備なし - ショップで購入する必要がある
        
        // 戦闘フェーズではチームカラーブロックは配布しない
        
        // ショップアイテムをホットバー9番目に配置
        val shopItem = plugin.shopManager.createShopItem()
        player.inventory.setItem(8, shopItem)
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
    
    fun setupFlagBeacon(location: Location, team: Team) {
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
        
        // スポーン地点のコンクリートを設置し、チームの配置ブロックとして記録
        for (x in -1..1) {
            for (z in -1..1) {
                val blockLocation = location.clone().add(x.toDouble(), -1.0, z.toDouble())
                blockLocation.block.type = concreteType
                
                // チームの配置ブロックとして記録（ビーコンから接続可能）
                teamPlacedBlocks.getOrPut(team) { mutableSetOf() }.add(blockLocation)
                // ツリー構造には追加しない（ビーコンから直接接続可能なため）
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
                    val blockLocation = spawnLocation.clone().add(x.toDouble(), -1.0, z.toDouble())
                    blockLocation.block.type = Material.AIR
                    
                    // チームの配置ブロックリストからも削除
                    teamPlacedBlocks[team]?.remove(blockLocation)
                }
            }
        }
    }
    
    private fun setupScoreboard(player: Player) {
        try {
            // プレイヤーごとに個別のスコアボードを作成
            val playerScoreboard = Bukkit.getScoreboardManager().newScoreboard
            val playerObjective = playerScoreboard.registerNewObjective("ctf_game", "dummy", 
                Component.text("CTF - $gameName", NamedTextColor.GOLD))
            playerObjective.displaySlot = DisplaySlot.SIDEBAR
            
            // プレイヤーに設定
            player.scoreboard = playerScoreboard
            
            // 初回更新
            updatePlayerScoreboard(player)
            
        } catch (e: Exception) {
            plugin.logger.warning("Failed to setup scoreboard for player ${player.name}: ${e.message}")
        }
    }
    
    fun updateScoreboard() {
        // スコアボードの更新を1秒に1回に制限（ゲームループと同期）
        if (System.currentTimeMillis() - lastScoreboardUpdate < 1000) {
            return
        }
        lastScoreboardUpdate = System.currentTimeMillis()
        
        // 全プレイヤーのスコアボードを更新
        getAllPlayers().forEach { player ->
            updatePlayerScoreboard(player)
        }
    }
    
    private fun updatePlayerScoreboard(player: Player) {
        val playerScoreboard = player.scoreboard
        val obj = playerScoreboard.getObjective("ctf_game") ?: return
        
        // 既存のエントリをクリア
        playerScoreboard.entries.forEach { entry ->
            playerScoreboard.resetScores(entry)
        }
        
        var line = 15
        
        // ゲーム開始前の表示
        if (state == GameState.WAITING || state == GameState.STARTING) {
            // 参加人数
            val redCount = redTeam.size
            val blueCount = blueTeam.size
            obj.getScore("§c赤${redCount} §9青${blueCount}").score = line--
            
            // マッチモードの場合、先行勝利数を表示
            if (matchWrapper != null) {
                obj.getScore("§e先行3勝制").score = line--
            }
            
            // ゲーム設定
            obj.getScore("§f建築${buildDuration / 60}分 戦闘${combatDuration / 60}分").score = line--
            obj.getScore("§f開始${minPlayers}人～").score = line--
            
            // カウントダウン表示
            if (state == GameState.STARTING && autoStartCountdown > 0) {
                obj.getScore("§a開始まで: §f${autoStartCountdown}秒").score = line--
            }
            
            return
        }
        
        // ゲーム中の表示
        // マッチモードの場合
        if (matchWrapper != null) {
            // 第Nゲーム表示
            obj.getScore("§e第${matchWrapper!!.currentGameNumber}ゲーム").score = line--
            
            val redWins = matchWrapper!!.matchWins[Team.RED] ?: 0
            val blueWins = matchWrapper!!.matchWins[Team.BLUE] ?: 0
            
            // マッチスコア
            obj.getScore("§c赤${redWins}§f-§9${blueWins}青").score = line--
        }
        
        // 自チームの通貨を表示
        val playerTeam = getPlayerTeam(player.uniqueId)
        if (playerTeam != null) {
            val currency = if (matchWrapper != null) {
                matchWrapper!!.getTeamCurrency(playerTeam)
            } else {
                getTeamCurrency(playerTeam)
            }
            obj.getScore("§e${currency}G").score = line--
        }
    }
    
    private fun getPhaseDisplayName(): String {
        return when (phase) {
            GamePhase.BUILD -> plugin.languageManager.getMessage("game-phases.build")
            GamePhase.COMBAT -> plugin.languageManager.getMessage("game-phases.combat")
            GamePhase.INTERMISSION -> plugin.languageManager.getMessage("game-phases.intermission")
        }
    }
    
    private fun updateBossBar() {
        val bar = bossBar ?: return
        
        // 時間情報
        val timeText = formatTime(currentPhaseTime)
        
        // フェーズごとに異なる表示
        val barTitle = when (phase) {
            GamePhase.BUILD -> {
                // 建築フェーズ: シンプルに時間のみ
                "建築フェーズ - $timeText"
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズ: 旗の取得数を表示
                val redScore = score[Team.RED] ?: 0
                val blueScore = score[Team.BLUE] ?: 0
                "戦闘フェーズ - 赤 $redScore : $blueScore 青 - $timeText"
            }
            GamePhase.INTERMISSION -> {
                // 作戦会議フェーズ
                "作戦会議 - $timeText"
            }
        }
        
        bar.setTitle(barTitle)
        
        // フェーズに応じて色を変更
        bar.color = when (phase) {
            GamePhase.BUILD -> BarColor.GREEN
            GamePhase.COMBAT -> BarColor.RED
            GamePhase.INTERMISSION -> BarColor.YELLOW
        }
        
        // 進行度を更新
        val totalTime = when (phase) {
            GamePhase.BUILD -> buildDuration
            GamePhase.COMBAT -> combatDuration
            GamePhase.INTERMISSION -> resultDuration
        }
        
        val progress = currentPhaseTime.toDouble() / totalTime.toDouble()
        bar.progress = progress.coerceIn(0.0, 1.0)
    }
    
    fun dropFlag(player: Player, team: Team, deathLocation: Location? = null) {
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
        
        // ドロップ位置を決定（死亡位置が指定されている場合はそれを使用）
        val baseLocation = deathLocation ?: player.location
        
        // 旗が回収不可能な状況かチェック
        val isUnrecoverable = when {
            // 奈落（Y座標が0以下）
            baseLocation.blockY <= 0 -> true
            
            // 溶岩の中
            baseLocation.block.type == Material.LAVA -> true
            
            // 高所から落下中（下に安全な場所がない）
            isLocationFalling(baseLocation) && findSafeDropLocation(baseLocation) == null -> true
            
            // 死亡位置の周囲が危険（溶岩や奈落に囲まれている）
            isLocationSurroundedByDanger(baseLocation) -> true
            
            else -> false
        }
        
        if (isUnrecoverable) {
            // 回収不可能な場合は即座に旗を返却
            val flagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
            } ?: return
            
            setupFlagBeacon(flagLocation, team)
            
            getAllPlayers().forEach {
                it.sendMessage(Component.text(plugin.languageManager.getMessage("flag.returned-uncollectable", 
                    "color" to team.colorCode,
                    "team" to team.displayName), team.color))
            }
            return
        }
        
        // ドロップ位置はプレイヤーの現在位置
        val dropLocation = baseLocation
        
        // 旗をドロップ
        val itemStack = ItemStack(Material.BEACON)
        val meta = itemStack.itemMeta
        meta.displayName(Component.text(plugin.languageManager.getMessage("flag.flag-name", "team" to team.displayName), team.color))
        meta.isUnbreakable = true
        itemStack.itemMeta = meta
        
        val droppedItem = player.world.dropItem(dropLocation, itemStack)
        droppedItem.setGlowing(true)
        droppedItem.customName(Component.text(plugin.languageManager.getMessage("flag.flag-name", "team" to team.displayName), team.color))
        droppedItem.isCustomNameVisible = true
        droppedItem.isInvulnerable = true
        droppedItem.setGravity(false) // 重力を無効化して落下を防ぐ
        droppedItem.velocity = org.bukkit.util.Vector(0, 0, 0) // 速度をゼロにする
        
        // ドロップ情報を記録
        droppedFlags[dropLocation] = Pair(team, System.currentTimeMillis())
        
        // メッセージ
        getAllPlayers().forEach {
            it.sendMessage(Component.text(plugin.languageManager.getMessage("flag.flag-dropped", "team" to team.displayName), NamedTextColor.YELLOW))
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
                    it.sendMessage(Component.text(plugin.languageManager.getMessage("flag.returned-home", 
                        "color" to team.colorCode,
                        "team" to team.displayName), team.color))
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
                it.sendMessage(Component.text(plugin.languageManager.getMessage("flag.flag-recovered",
                    "player" to player.name,
                    "flagTeam" to flagTeam.displayName), team.color))
            }
            
            return true
        }
        
        // 敵の旗を拾う場合
        // 既に旗を持っているかチェック
        if (player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("flag.already-carrying")))
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
            it.sendMessage(Component.text(plugin.languageManager.getMessage("flag.picked-up",
                "player" to player.name,
                "color" to flagTeam.colorCode,
                "team" to flagTeam.displayName), NamedTextColor.YELLOW))
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
                            p.sendMessage(Component.text(plugin.languageManager.getMessage("flag.flag-recovered",
                                "player" to player.name,
                                "flagTeam" to flagTeam.displayName), team.color))
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
                            p.sendMessage(Component.text(plugin.languageManager.getMessage("flag.picked-up",
                                "player" to player.name,
                                "color" to flagTeam.colorCode,
                                "team" to flagTeam.displayName), NamedTextColor.YELLOW))
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
                val errorMessage = plugin.languageManager.getMessage("flag.cannot-capture-no-flag")
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
        addTeamCurrency(team, captureReward, plugin.languageManager.getMessage("currency.capture-currency", "player" to player.name))
        
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
                addTeamCurrency(team, assistReward * assists.size, plugin.languageManager.getMessage("currency.capture-assist", "count" to assists.size.toString()))
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
            p.sendMessage(Component.text(plugin.languageManager.getMessage("flag.captured-by",
                "color" to team.colorCode,
                "team" to team.displayName,
                "player" to player.name), team.color))
            p.sendMessage(Component.text(plugin.languageManager.getMessage("flag.current-score",
                "red" to (score[Team.RED] ?: 0).toString(),
                "blue" to (score[Team.BLUE] ?: 0).toString()), NamedTextColor.WHITE))
            
            // 効果音を再生
            p.playSound(p.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        }
        
        // タイトル表示
        getAllPlayers().forEach { p ->
            p.showTitle(Title.title(
                Component.text(plugin.languageManager.getMessage("flag.scored-title", "team" to team.displayName), team.color),
                Component.text(plugin.languageManager.getMessage("flag.score-subtitle", 
                    "red" to (score[Team.RED] ?: 0).toString(),
                    "blue" to (score[Team.BLUE] ?: 0).toString()), NamedTextColor.WHITE),
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
        
        // 既存のスポーン保護タスクをキャンセル
        spawnProtectionTasks[player.uniqueId]?.cancel()
        
        // 3秒後に保護を解除
        val task = object : BukkitRunnable() {
            override fun run() {
                if (spawnProtection.remove(player.uniqueId) != null) {
                    player.isGlowing = false
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("spawn.protection-ended")))
                }
                spawnProtectionTasks.remove(player.uniqueId)
            }
        }
        task.runTaskLater(plugin, 60L) // 3秒後
        spawnProtectionTasks[player.uniqueId] = task
        
        // スポーン地点に転送
        teleportToSpawn(player, team)
        
        // 戦闘フェーズではリスポーン時に装備を再配布しない（革防具は死亡時処理で保持される）
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
                
                // 革防具がない場合は再配布（初回死亡時など）
                if (player.equipment?.helmet?.type != Material.LEATHER_HELMET) {
                    plugin.equipmentManager.giveArmor(player, team)
                }
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズでは革防具の確認のみ（死亡時処理で保持されているはず）
                // 万が一ない場合は再配布
                if (player.equipment?.helmet?.type != Material.LEATHER_HELMET) {
                    plugin.equipmentManager.giveArmor(player, team)
                }
            }
            GamePhase.INTERMISSION -> {}
        }
        
        player.sendMessage(Component.text(plugin.languageManager.getMessage("spawn.protection-active")))
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
                    Component.text(plugin.languageManager.getMessage("action-bar.build-phase-guide"))
                }
                
                GamePhase.COMBAT -> {
                    // 戦闘フェーズ
                    val enemyTeam = if (team == Team.RED) Team.BLUE else Team.RED
                    
                    when {
                        // 自分が旗を持っている
                        player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier -> {
                            Component.text(plugin.languageManager.getMessage("action-bar.return-to-base"))
                        }
                        
                        // 自チームの旗が敵に取られている
                        (team == Team.RED && redFlagCarrier != null) || 
                        (team == Team.BLUE && blueFlagCarrier != null) -> {
                            val carrierName = when (team) {
                                Team.RED -> redFlagCarrier?.let { Bukkit.getPlayer(it)?.name } ?: plugin.languageManager.getMessage("flag.flag-carrier-unknown")
                                Team.BLUE -> blueFlagCarrier?.let { Bukkit.getPlayer(it)?.name } ?: plugin.languageManager.getMessage("flag.flag-carrier-unknown")
                            }
                            Component.text(plugin.languageManager.getMessage("flag.enemy-has-flag", "carrier" to carrierName))
                        }
                        
                        // 自チームの旗がドロップしている
                        (team == Team.RED && droppedFlags.any { it.value.first == Team.RED }) ||
                        (team == Team.BLUE && droppedFlags.any { it.value.first == Team.BLUE }) -> {
                            Component.text(plugin.languageManager.getMessage("flag.team-flag-dropped"))
                        }
                        
                        // 通常状態（敵の旗を取りに行く）
                        else -> {
                            Component.text(plugin.languageManager.getMessage("flag.retrieve-enemy-flag",
                                "color" to enemyTeam.getChatColor(),
                                "team" to enemyTeam.displayName
                            ))
                        }
                    }
                }
                
                GamePhase.INTERMISSION -> {
                    // 作戦会議フェーズでは何も表示しない
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
                player.sendMessage(Component.text(plugin.languageManager.getMessage("currency.team-notification",
                    "reason" to reason,
                    "amount" to amount.toString()
                )))
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
                p.sendMessage(Component.text(plugin.languageManager.getMessage("currency.purchase-notification",
                    "player" to player.name,
                    "item" to itemName,
                    "amount" to amount.toString()
                )))
                p.sendMessage(Component.text(plugin.languageManager.getMessage("currency.balance-notification",
                    "balance" to teamCurrency[team].toString()
                )))
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
        // ゲームの詳細レポート
        getAllPlayers().forEach { player ->
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(Component.text(plugin.languageManager.getMessage("report.header")))
            
            // マッチ情報（マッチモードの場合）
            matchWrapper?.let { m ->
                player.sendMessage(Component.text(m.getMatchStatus()).color(NamedTextColor.YELLOW))
                val wins = m.matchWins
                player.sendMessage(Component.text(plugin.languageManager.getMessage("report.match-score-header"))
                    .append(Component.text(plugin.languageManager.getMessage("report.match-score-format",
                        "red" to wins[Team.RED].toString(),
                        "blue" to wins[Team.BLUE].toString()
                    )))
                )
                player.sendMessage(Component.text("", NamedTextColor.WHITE))
            }
            
            // 今回のゲーム
            player.sendMessage(Component.text(plugin.languageManager.getMessage("report.game-header")))
            if (winner != null) {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("report.winner-format",
                    "color" to winner.getChatColor(),
                    "team" to winner.displayName
                )))
            } else {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("report.draw-result")))
            }
            player.sendMessage(Component.text(plugin.languageManager.getMessage("report.score-header"))
                .append(Component.text(plugin.languageManager.getMessage("report.match-score-format",
                    "red" to score[Team.RED].toString(),
                    "blue" to score[Team.BLUE].toString()
                )))
            )
            
            // チーム統計
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(Component.text(plugin.languageManager.getMessage("report.team-stats-header")))
            
            // 赤チーム
            player.sendMessage(Component.text(plugin.languageManager.getMessage("report.red-team-header")))
            val redPlayers = getTeamPlayers(Team.RED)
            redPlayers.forEach { p ->
                player.sendMessage(Component.text("  - ${p.name}", NamedTextColor.WHITE))
            }
            
            // 青チーム
            player.sendMessage(Component.text(plugin.languageManager.getMessage("report.blue-team-header")))
            val bluePlayers = getTeamPlayers(Team.BLUE)
            bluePlayers.forEach { p ->
                player.sendMessage(Component.text("  - ${p.name}", NamedTextColor.WHITE))
            }
            
            // 通貨情報
            if (matchWrapper != null) {
                player.sendMessage(Component.text("", NamedTextColor.WHITE))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("report.team-funds-header")))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("report.team-funds-format",
                    "color" to Team.RED.getChatColor(),
                    "team" to Team.RED.displayName,
                    "amount" to matchWrapper!!.getTeamCurrency(Team.RED).toString()
                )))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("report.team-funds-format",
                    "color" to Team.BLUE.getChatColor(),
                    "team" to Team.BLUE.displayName,
                    "amount" to matchWrapper!!.getTeamCurrency(Team.BLUE).toString()
                )))
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
                    
                    if (kills > 0) player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.kills-display", "kills" to kills.toString())).color(NamedTextColor.GREEN))
                    if (assists > 0) player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.assists-display", "assists" to assists.toString())).color(NamedTextColor.GREEN))
                    if (captures > 0) player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.captures-display", "captures" to captures.toString())).color(NamedTextColor.GOLD))
                    if (flagPickups > 0) player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.flag-pickups-display", "pickups" to flagPickups.toString())).color(NamedTextColor.YELLOW))
                    if (flagDefends > 0) player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.flag-defends-display", "defends" to flagDefends.toString())).color(NamedTextColor.AQUA))
                    if (moneySpent > 0) player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.money-spent-display", "amount" to moneySpent.toString())).color(NamedTextColor.YELLOW))
                    if (blocks > 0) player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.blocks-placed-display", "blocks" to blocks.toString())).color(NamedTextColor.WHITE))
                    if (deaths > 0) player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.deaths-display", "deaths" to deaths.toString())).color(NamedTextColor.RED))
                    
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("mvp.star-decoration")).color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    
                    // MVPにタイトル表示
                    if (player == mvpPlayer) {
                        player.showTitle(Title.title(
                            Component.text(plugin.languageManager.getMessage("mvp.mvp-title")).color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                            Component.text(plugin.languageManager.getMessage("mvp.mvp-subtitle")).color(NamedTextColor.YELLOW),
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
    
    /**
     * プレイヤーが落下中かどうかを判定
     */
    private fun isPlayerFalling(player: Player): Boolean {
        // プレイヤーの足元のブロックを確認
        val blockBelow = player.location.clone().subtract(0.0, 1.0, 0.0).block
        val blockBelow2 = player.location.clone().subtract(0.0, 2.0, 0.0).block
        
        // 足元が空気ブロックで、かつY速度が負（落下中）の場合
        return blockBelow.type == Material.AIR && 
               blockBelow2.type == Material.AIR && 
               player.velocity.y < -0.5
    }
    
    /**
     * 指定位置が落下位置かどうかを判定
     */
    private fun isLocationFalling(location: Location): Boolean {
        // 位置の下のブロックを確認
        val blockBelow = location.clone().subtract(0.0, 1.0, 0.0).block
        val blockBelow2 = location.clone().subtract(0.0, 2.0, 0.0).block
        val blockBelow3 = location.clone().subtract(0.0, 3.0, 0.0).block
        
        // 下3ブロックが空気の場合は落下位置と判定
        return blockBelow.type == Material.AIR && 
               blockBelow2.type == Material.AIR && 
               blockBelow3.type == Material.AIR
    }
    
    /**
     * 安全な旗ドロップ位置を探す
     */
    private fun findSafeDropLocation(originalLocation: Location): Location? {
        val world = originalLocation.world
        val startX = originalLocation.blockX
        val startY = originalLocation.blockY
        val startZ = originalLocation.blockZ
        
        // まず現在位置が安全かチェック
        if (isSafeLocation(originalLocation)) {
            return originalLocation.clone().add(0.5, 0.5, 0.5)
        }
        
        // 現在位置から下方向に安全な場所を探す（最大50ブロック下まで）
        for (y in startY downTo kotlin.math.max(0, startY - 50)) {
            val checkLoc = Location(world, startX.toDouble(), y.toDouble(), startZ.toDouble())
            if (isSafeLocation(checkLoc)) {
                return checkLoc.add(0.5, 1.0, 0.5) // ブロックの中心、1ブロック上
            }
        }
        
        // 下に見つからない場合、周囲を螺旋状に探索
        for (radius in 1..15) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    // 螺旋の外周のみチェック
                    if (kotlin.math.abs(dx) == radius || kotlin.math.abs(dz) == radius) {
                        // 現在の高さから上下に探索
                        for (dy in -10..10) {
                            val y = startY + dy
                            if (y in 0..255) {
                                val checkLoc = Location(world, (startX + dx).toDouble(), y.toDouble(), (startZ + dz).toDouble())
                                if (isSafeLocation(checkLoc)) {
                                    return checkLoc.add(0.5, 1.0, 0.5)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * 位置が危険物に囲まれているかチェック
     */
    private fun isLocationSurroundedByDanger(location: Location): Boolean {
        val world = location.world
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ
        
        var dangerCount = 0
        var totalCount = 0
        
        // 周囲3x3x3の範囲をチェック
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    
                    val checkLoc = Location(world, (x + dx).toDouble(), (y + dy).toDouble(), (z + dz).toDouble())
                    val block = checkLoc.block
                    
                    totalCount++
                    
                    // 危険なブロック
                    if (block.type == Material.LAVA || 
                        block.type == Material.VOID_AIR ||
                        (checkLoc.blockY <= 0)) {
                        dangerCount++
                    }
                }
            }
        }
        
        // 半分以上が危険な場合は回収不可能と判定
        return dangerCount > totalCount / 2
    }
    
    /**
     * 安全な観戦位置を探す（落下死用）
     */
    fun findSafeSpectatorLocation(deathLocation: Location): Location {
        // まず安全なドロップ位置を探す
        val safeLocation = findSafeDropLocation(deathLocation)
        if (safeLocation != null) {
            // 見つかった場合は少し上空から観戦
            return safeLocation.clone().add(0.0, 5.0, 0.0)
        }
        
        // 見つからない場合は、マップ中央を使用
        if (mapCenterLocation != null) {
            return mapCenterLocation!!.clone().add(0.0, 20.0, 0.0)
        }
        
        // マップ中央が設定されていない場合は、スポーン地点から計算
        // ゲームワールドを使用（テンポラリワールドの場合もある）
        val world = this.world
        val redSpawn = redSpawnLocation
        val blueSpawn = blueSpawnLocation
        
        if (redSpawn != null && blueSpawn != null) {
            // 両スポーン地点の中間点を計算
            val centerX = (redSpawn.x + blueSpawn.x) / 2.0
            val centerZ = (redSpawn.z + blueSpawn.z) / 2.0
            val centerY = kotlin.math.max(redSpawn.y, blueSpawn.y) + 20.0
            return Location(world, centerX, centerY, centerZ)
        }
        
        // 最後の手段として、赤スポーンまたは青スポーンを使用
        return redSpawn?.clone()?.add(0.0, 10.0, 0.0) 
            ?: blueSpawn?.clone()?.add(0.0, 10.0, 0.0)
            ?: Location(world, deathLocation.x, deathLocation.y + 10.0, deathLocation.z)
    }
    
    /**
     * 指定位置が安全かどうかを判定
     */
    private fun isSafeLocation(location: Location): Boolean {
        val block = location.block
        val blockAbove = location.clone().add(0.0, 1.0, 0.0).block
        val blockAbove2 = location.clone().add(0.0, 2.0, 0.0).block
        
        // 足元が固体ブロックで、上2ブロックが空気の場合は安全
        return block.type.isSolid && 
               !blockAbove.type.isSolid && 
               !blockAbove2.type.isSolid &&
               block.type != Material.LAVA &&
               block.type != Material.WATER
    }
    
    /**
     * ブロック設置が許可されているかをチェック
     * @param player 設置するプレイヤー
     * @param block 設置するブロック
     * @return 設置可能な場合はペアレントの位置、不可の場合null
     */
    fun canPlaceBlock(player: Player, block: org.bukkit.block.Block): Location? {
        val team = getPlayerTeam(player.uniqueId) ?: return null
        val location = block.location
        
        // スポーン地点と旗周辺の建築制限チェック（3x3、Y座標全て）
        // 赤チームの旗周辺チェック
        redFlagLocation?.let { flagLoc ->
            if (kotlin.math.abs(location.blockX - flagLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - flagLoc.blockZ) <= 1) {
                player.sendActionBar(Component.text(plugin.languageManager.getMessage("action-bar.cannot-place-flag")))
                return null
            }
        }
        
        // 青チームの旗周辺チェック
        blueFlagLocation?.let { flagLoc ->
            if (kotlin.math.abs(location.blockX - flagLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - flagLoc.blockZ) <= 1) {
                player.sendActionBar(Component.text(plugin.languageManager.getMessage("action-bar.cannot-place-flag")))
                return null
            }
        }
        
        // 赤チームのスポーン地点周辺チェック
        redSpawnLocation?.let { spawnLoc ->
            if (kotlin.math.abs(location.blockX - spawnLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - spawnLoc.blockZ) <= 1) {
                player.sendActionBar(Component.text(plugin.languageManager.getMessage("action-bar.cannot-place-spawn")))
                return null
            }
        }
        
        // 青チームのスポーン地点周辺チェック
        blueSpawnLocation?.let { spawnLoc ->
            if (kotlin.math.abs(location.blockX - spawnLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - spawnLoc.blockZ) <= 1) {
                player.sendActionBar(Component.text(plugin.languageManager.getMessage("action-bar.cannot-place-spawn")))
                return null
            }
        }
        
        // チームカラーのブロックかチェック
        val teamBlocks = TEAM_BLOCKS[team] ?: return null
        
        if (block.type in teamBlocks) {
            // チームカラーブロックの場合
            
            // 自チームの旗位置を取得
            val teamFlagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
            } ?: return null
            
            // 旗から3ブロック以内かチェック（旗がルート）
            if (location.distance(teamFlagLocation) <= 3.0) {
                return teamFlagLocation
            }
            
            // 既に設置したブロックから隣接（縦横斜め）しているかチェック
            val placedBlocks = teamPlacedBlocks[team] ?: mutableSetOf()
            
            // 隣接ブロックを探す
            for (placedBlock in placedBlocks) {
                if (isAdjacent(location, placedBlock)) {
                    return placedBlock
                }
            }
            
            // どちらの条件も満たさない場合は設置不可
            player.sendActionBar(Component.text(plugin.languageManager.getMessage("action-bar.place-restriction-flag"), NamedTextColor.RED))
            return null
            
        } else if (isDeviceOrFence(block.type)) {
            // フェンスや装置の場合
            
            // 周囲3ブロック以内に自チームのカラーブロックがあるかチェック
            val placedBlocks = teamPlacedBlocks[team] ?: mutableSetOf()
            
            for (placedBlock in placedBlocks) {
                if (location.distance(placedBlock) <= 3.0) {
                    // 最も近いブロックを親として返す
                    return placedBlock
                }
            }
            
            // 自チームの旗から3ブロック以内かもチェック
            val teamFlagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
            } ?: return null
            
            if (location.distance(teamFlagLocation) <= 3.0) {
                return teamFlagLocation
            }
            
            player.sendActionBar(Component.text(plugin.languageManager.getMessage("action-bar.place-restriction-team"), NamedTextColor.RED))
            return null
            
        } else {
            // その他のブロックは設置不可
            player.sendActionBar(Component.text(plugin.languageManager.getMessage("action-bar.cannot-place-block")))
            return null
        }
    }
    
    /**
     * フェンスや装置かどうかをチェック
     */
    private fun isDeviceOrFence(material: Material): Boolean {
        return material in setOf(
            Material.OAK_FENCE, Material.IRON_BARS, Material.GLASS_PANE,
            Material.LADDER, Material.OAK_TRAPDOOR, Material.IRON_TRAPDOOR,
            Material.OAK_DOOR, Material.IRON_DOOR, Material.TORCH,
            Material.REDSTONE_TORCH, Material.STONE_BUTTON, Material.LEVER,
            Material.STONE_PRESSURE_PLATE, Material.PISTON, Material.STICKY_PISTON,
            Material.REDSTONE, Material.REDSTONE_BLOCK, Material.HOPPER,
            Material.DISPENSER, Material.TNT, Material.WATER_BUCKET, Material.LAVA_BUCKET
        )
    }
    
    /**
     * 2つの位置が隣接しているかチェック（縦横斜め）
     */
    private fun isAdjacent(loc1: Location, loc2: Location): Boolean {
        val dx = kotlin.math.abs(loc1.blockX - loc2.blockX)
        val dy = kotlin.math.abs(loc1.blockY - loc2.blockY)
        val dz = kotlin.math.abs(loc1.blockZ - loc2.blockZ)
        
        // 隣接の定義：各軸の差が1以下で、少なくとも1つの軸で差がある
        return dx <= 1 && dy <= 1 && dz <= 1 && (dx + dy + dz) > 0
    }
    
    /**
     * ブロック設置を記録（ツリー構造で管理）
     */
    fun recordBlockPlacement(team: Team, location: Location, parent: Location) {
        val block = location.block
        
        // チームカラーブロックのみ記録（フェンスや装置は記録しない）
        val teamBlocks = TEAM_BLOCKS[team] ?: return
        if (block.type in teamBlocks) {
            val blocks = teamPlacedBlocks.getOrPut(team) { mutableSetOf() }
            blocks.add(location.clone())
            
            val trees = teamBlockTrees.getOrPut(team) { mutableMapOf() }
            
            // 親ノードを取得または作成（旗の場合）
            val parentNode = trees[parent] ?: BlockNode(parent, null).also { trees[parent] = it }
            
            // 新しいノードを作成
            val newNode = BlockNode(location, parent)
            trees[location] = newNode
            
            // 親ノードに子として追加
            parentNode.children.add(location)
            
            // 隣接する白いブロックを再接続する
            reconnectWhiteBlocks(team, location)
        }
        // フェンスや装置は設置記録しない（破壊されても切断判定に影響しない）
    }
    
    /**
     * 新しく設置したブロックの隣接する白いブロックを再接続し、チームカラーに戻す
     */
    private fun reconnectWhiteBlocks(team: Team, newBlockLocation: Location) {
        val offsets = listOf(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH_EAST, BlockFace.NORTH_WEST,
            BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST
        )
        
        val teamBlocks = TEAM_BLOCKS[team] ?: return
        val blocks = teamPlacedBlocks.getOrPut(team) { mutableSetOf() }
        val trees = teamBlockTrees.getOrPut(team) { mutableMapOf() }
        val newNode = trees[newBlockLocation] ?: return
        
        // 隣接ブロックをチェック
        for (face in offsets) {
            val adjacentLoc = newBlockLocation.block.getRelative(face).location
            val adjacentBlock = adjacentLoc.block
            
            // 白いコンクリートの場合
            if (adjacentBlock.type == NEUTRAL_BLOCK) {
                // このブロックから再帰的に接続された白いブロックを全て収集
                val connectedWhiteBlocks = mutableSetOf<Location>()
                collectConnectedWhiteBlocks(adjacentLoc, connectedWhiteBlocks)
                
                // 収集した白いブロックを全てチームカラーに戻す
                val blockType = teamBlocks.first() // チームの最初のブロックタイプを使用
                for (whiteLoc in connectedWhiteBlocks) {
                    whiteLoc.block.type = blockType
                    
                    // ブロックリストに追加
                    blocks.add(whiteLoc.clone())
                    
                    // ツリーに追加（新しく設置したブロックの子として）
                    val whiteNode = BlockNode(whiteLoc, newBlockLocation)
                    trees[whiteLoc] = whiteNode
                    newNode.children.add(whiteLoc)
                }
            }
        }
    }
    
    /**
     * 接続された白いブロックを再帰的に収集
     */
    private fun collectConnectedWhiteBlocks(location: Location, result: MutableSet<Location>) {
        if (location in result) return
        if (location.block.type != NEUTRAL_BLOCK) return
        
        result.add(location.clone())
        
        val offsets = listOf(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH_EAST, BlockFace.NORTH_WEST,
            BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST
        )
        
        for (face in offsets) {
            val adjacentLoc = location.block.getRelative(face).location
            collectConnectedWhiteBlocks(adjacentLoc, result)
        }
    }
    
    /**
     * ブロック破壊を記録し、切断されたブロックを処理
     */
    fun recordBlockBreak(location: Location) {
        // 破壊されたブロックがどのチームのものか特定
        var ownerTeam: Team? = null
        for ((team, blocks) in teamPlacedBlocks) {
            if (location in blocks) {
                ownerTeam = team
                break
            }
        }
        
        if (ownerTeam == null) return
        
        // ブロックを削除
        teamPlacedBlocks[ownerTeam]?.remove(location)
        val trees = teamBlockTrees[ownerTeam] ?: return
        val node = trees[location] ?: return
        
        // 親ノードから子として削除
        node.parent?.let { parentLoc ->
            trees[parentLoc]?.children?.remove(location)
        }
        
        // 破壊されたブロック自体をツリーから削除
        trees.remove(location)
        
        // ビーコンからの到達可能性をチェックして、切断されたブロックを特定
        val reachableBlocks = findReachableBlocks(ownerTeam)
        
        // 到達不可能なブロックを白いブロックに変換
        val allTeamBlocks = teamPlacedBlocks[ownerTeam] ?: mutableSetOf()
        for (blockLoc in allTeamBlocks.toList()) {
            if (blockLoc !in reachableBlocks && blockLoc != location) {
                // ブロックを白いコンクリートに変換
                val block = blockLoc.block
                when (block.type) {
                    Material.RED_CONCRETE, Material.BLUE_CONCRETE -> block.type = Material.WHITE_CONCRETE
                    Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS -> block.type = Material.WHITE_STAINED_GLASS
                    else -> {}
                }
                
                // リストから削除
                teamPlacedBlocks[ownerTeam]?.remove(blockLoc)
                trees.remove(blockLoc)
            }
        }
    }
    
    /**
     * 敵陣シールドシステムタスクを開始
     */
    private fun startSuffocationTask() {
        // 既存のタスクがあればキャンセル
        suffocationTask?.cancel()
        suffocationTask = null
        
        // プレイヤーごとのシールド値を管理（最大値: 100）
        val playerShield = mutableMapOf<UUID, Float>()
        val lastDamageTime = mutableMapOf<UUID, Long>()
        
        suffocationTask = object : BukkitRunnable() {
            override fun run() {
                // ゲームまたはフェーズが終了したらタスクを停止
                if (state != GameState.RUNNING || phase != GamePhase.COMBAT) {
                    cancel()
                    suffocationTask = null
                    return
                }
                
                try {
                    // プレイヤーリストをコピーして処理
                    val players = getAllPlayers().toList()
                    
                    for (player in players) {
                        // プレイヤーが有効でない場合はスキップ
                        if (!player.isOnline || player.isDead) continue
                        
                        val playerTeam = getPlayerTeam(player.uniqueId)
                        if (playerTeam == null) continue
                        
                        val blockBelow = player.location.clone().subtract(0.0, 0.5, 0.0).block
                        
                        // プレイヤーの下のブロックが敵チームの色ブロックかチェック
                        val isOnEnemyBlock = when (playerTeam) {
                            Team.RED -> blockBelow.type == Material.BLUE_CONCRETE || blockBelow.type == Material.BLUE_STAINED_GLASS
                            Team.BLUE -> blockBelow.type == Material.RED_CONCRETE || blockBelow.type == Material.RED_STAINED_GLASS
                        }
                        
                        // 現在のシールド値を取得（初期値100）
                        var shield = playerShield.getOrDefault(player.uniqueId, 100f)
                        
                        if (isOnEnemyBlock) {
                            // 初めて敵陣に乗った時だけ警告
                            if (!player.hasMetadata("on_enemy_block")) {
                                player.setMetadata("on_enemy_block", org.bukkit.metadata.FixedMetadataValue(plugin, true))
                                player.sendMessage(Component.text(plugin.languageManager.getMessage("shield.decreasing"), NamedTextColor.AQUA))
                            }
                            
                            // シールドを減らす（0.5秒ごとに2減少 = 25秒で枯渇）
                            shield = (shield - 2f).coerceAtLeast(0f)
                            
                            // シールドが少なくなったら警告
                            if (shield == 40f) {
                                player.sendMessage(Component.text(plugin.languageManager.getMessage("shield.weakening"), NamedTextColor.YELLOW))
                                player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f)
                            } else if (shield == 20f) {
                                player.sendMessage(Component.text(plugin.languageManager.getMessage("shield.critical"), NamedTextColor.RED))
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
                                
                                // 2秒ごとにダメージ
                                if (currentTime - lastDamage >= 2000) {
                                    if (player.health > 1.0) {
                                        // ダメージを与える
                                        val event = EntityDamageEvent(player, EntityDamageEvent.DamageCause.CUSTOM, 1.0)
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
                            
                            // シールドを回復（0.5秒ごとに5回復 = 10秒で全回復）
                            if (shield < 100f) {
                                shield = (shield + 5f).coerceAtMost(100f)
                                
                                // 全回復したら通知
                                if (shield == 100f) {
                                    player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2.0f)
                                }
                            }
                        }
                        
                        // シールド値を保存
                        playerShield[player.uniqueId] = shield
                        
                        // アクションバーにシールド状態を表示
                        val shieldColor = when {
                            shield > 60 -> NamedTextColor.AQUA
                            shield > 30 -> NamedTextColor.YELLOW
                            else -> NamedTextColor.RED
                        }
                        
                        if (shield < 100f) {
                            val shieldBar = "█".repeat((shield / 10).toInt()).padEnd(10, '░')
                            player.sendActionBar(Component.text("シールド: [$shieldBar] ${shield.toInt()}%", shieldColor))
                        }
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Error in oxygen task: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        
        // 10ティック（0.5秒）ごとに実行
        suffocationTask?.runTaskTimer(plugin, 0L, 10L)
    }
    
    /**
     * ビーコンから到達可能なブロックを探索（BFS）
     */
    private fun findReachableBlocks(team: Team): Set<Location> {
        val reachable = mutableSetOf<Location>()
        val queue = mutableListOf<Location>()
        
        // ビーコンの位置を起点として追加
        val beaconLocation = when (team) {
            Team.RED -> redFlagLocation
            Team.BLUE -> blueFlagLocation
        } ?: return emptySet()
        
        // スポーン地点を取得
        val spawnLocation = when (team) {
            Team.RED -> redSpawnLocation
            Team.BLUE -> blueSpawnLocation
        }
        
        // ビーコン周辺3ブロック以内のブロックを起点に追加
        val teamBlocks = teamPlacedBlocks[team] ?: return emptySet()
        for (block in teamBlocks) {
            if (block.distance(beaconLocation) <= 3.0) {
                queue.add(block)
                reachable.add(block)
            }
        }
        
        // スポーン地点周辺3ブロック以内のブロックも起点に追加
        if (spawnLocation != null) {
            for (block in teamBlocks) {
                if (block.distance(spawnLocation) <= 3.0) {
                    if (block !in reachable) {
                        queue.add(block)
                        reachable.add(block)
                    }
                }
            }
        }
        
        // BFSで隣接ブロックを探索
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            
            // 隣接する全方向（26方向）をチェック
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        
                        val neighbor = current.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble())
                        if (neighbor in teamBlocks && neighbor !in reachable) {
                            reachable.add(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }
            }
        }
        
        return reachable
    }
    
    /**
     * マップ中央位置を計算（各種位置から推定）
     */
    private fun calculateMapCenter() {
        val locations = mutableListOf<Location>()
        
        // 旗位置を追加
        redFlagLocation?.let { locations.add(it) }
        blueFlagLocation?.let { locations.add(it) }
        
        // スポーン位置を追加
        redSpawnLocation?.let { locations.add(it) }
        blueSpawnLocation?.let { locations.add(it) }
        
        if (locations.isEmpty()) {
            mapCenterLocation = null
            return
        }
        
        // すべての位置の中心を計算
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        
        locations.forEach { loc ->
            sumX += loc.x
            sumY += loc.y
            sumZ += loc.z
        }
        
        val avgX = sumX / locations.size
        val avgY = sumY / locations.size
        val avgZ = sumZ / locations.size
        
        mapCenterLocation = Location(world, avgX, avgY, avgZ)
    }
    
    /**
     * マップ範囲を設定（POS1/POS2から）
     */
    fun setMapBounds(pos1: Location, pos2: Location) {
        val centerX = (pos1.x + pos2.x) / 2.0
        val centerY = (pos1.y + pos2.y) / 2.0
        val centerZ = (pos1.z + pos2.z) / 2.0
        
        mapCenterLocation = Location(world, centerX, centerY, centerZ)
    }
}