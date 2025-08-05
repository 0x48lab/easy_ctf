package com.hacklab.ctf

import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import com.hacklab.ctf.utils.MatchMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.enchantments.Enchantment
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
    val spectators = mutableSetOf<UUID>() // 観戦者リスト
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
    var buildDuration = plugin.config.getInt("default-phases.build-duration", 300)
    var combatDuration = plugin.config.getInt("default-phases.combat-duration", 600)
    var resultDuration = plugin.config.getInt("default-phases.result-duration", 60)
    var intermediateDuration = plugin.config.getInt("default-phases.intermediate-result-duration", 15)
    var buildPhaseGameMode = plugin.config.getString("default-phases.build-phase-gamemode", "SURVIVAL")!!
    var buildPhaseBlocks = 16
    var combatPhaseBlocks = 16
    
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
    private val actionBarPriority = mutableMapOf<UUID, Int>() // プレイヤー -> 優先度（高いほど優先）
    
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
    val respawnTasks = mutableMapOf<UUID, BukkitRunnable>()
    
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
            Team.SPECTATOR -> spectators
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
    fun getCenterLocation(): Location? = mapCenterLocation
    
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
        resultDuration = config.resultDuration
        intermediateDuration = config.intermediateDuration
        buildPhaseGameMode = config.buildPhaseGameMode
        buildPhaseBlocks = config.buildPhaseBlocks
        combatPhaseBlocks = config.combatPhaseBlocks
        minPlayers = config.minPlayers
        maxPlayersPerTeam = config.maxPlayersPerTeam
        autoStartEnabled = config.autoStartEnabled
        
        // マップ中央位置を計算
        calculateMapCenter()
    }
    
    fun addPlayer(player: Player, team: Team? = null): Boolean {
        if (state != GameState.WAITING) {
            plugin.logger.warning(plugin.languageManager.getMessage("log.player-cannot-join", "player" to player.name, "game" to name, "state" to state.toString()))
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
        
        // タブリストの色を更新（チーム参加時）
        updatePlayerTabColor(player)
        
        player.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.game-joined", 
            "game" to gameName,
            "color" to selectedTeam.getChatColor(),
            "team" to selectedTeam.displayName
        )))
        
        // マッチに追加
        matchWrapper?.players?.put(player.uniqueId, player)
        
        // 自動開始チェック
        checkAutoStart()
        
        // スコアボードを更新（人数表示のため）
        updateScoreboard()
        
        return true
    }
    
    fun addSpectator(player: Player): Boolean {
        spectators.add(player.uniqueId)
        
        // 観戦者モードの設定
        player.gameMode = GameMode.SPECTATOR
        
        // スコアボード表示
        setupScoreboard(player)
        
        player.sendMessage(Component.text(plugin.languageManager.getMessage("spectator.joined"), NamedTextColor.GRAY))
        
        // マッチに追加
        matchWrapper?.players?.put(player.uniqueId, player)
        
        // 中央にテレポート（ゲーム実行中の場合）
        if (state == GameState.RUNNING || state == GameState.STARTING) {
            getCenterLocation()?.let { center ->
                player.teleport(center)
            }
        }
        
        // スコアボードを更新
        updateScoreboard()
        
        return true
    }
    
    fun switchToSpectator(player: Player) {
        // 既存のチームから削除
        redTeam.remove(player.uniqueId)
        blueTeam.remove(player.uniqueId)
        
        // 旗を持っていた場合
        if (redFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.RED)
        } else if (blueFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.BLUE)
        }
        
        // 観戦者として追加
        spectators.add(player.uniqueId)
        player.gameMode = GameMode.SPECTATOR
        
        player.sendMessage(Component.text(plugin.languageManager.getMessage("spectator.switched"), NamedTextColor.GRAY))
        
        // タブリストの色を更新
        updatePlayerTeamColor(player, player.scoreboard)
        
        // スコアボードを更新
        updateScoreboard()
    }
    
    fun removePlayer(player: Player) {
        redTeam.remove(player.uniqueId)
        blueTeam.remove(player.uniqueId)
        spectators.remove(player.uniqueId)
        disconnectedPlayers.remove(player.uniqueId)
        
        // 他のプレイヤーのスコアボードからこのプレイヤーを削除
        getAllPlayers().forEach { otherPlayer ->
            val scoreboard = otherPlayer.scoreboard
            if (scoreboard != Bukkit.getScoreboardManager().mainScoreboard) {
                scoreboard.teams.forEach { team ->
                    if (team.hasEntry(player.name)) {
                        team.removeEntry(player.name)
                    }
                }
            }
        }
        
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
        
        // スコアボードを更新（人数表示のため）
        updateScoreboard()
    }
    
    fun handleDisconnect(player: Player) {
        val team = getPlayerTeam(player.uniqueId) ?: return
        
        // 切断中のプレイヤーとして記録（チーム情報を保持）
        disconnectedPlayers[player.uniqueId] = team
        
        // チームから一時的に除外（スコアボードの人数に含まれないように）
        redTeam.remove(player.uniqueId)
        blueTeam.remove(player.uniqueId)
        
        // 旗を持っていた場合
        if (redFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.RED)
        } else if (blueFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.BLUE)
        }
        
        // スコアボードを更新
        updateScoreboard()
    }
    
    fun handleReconnect(player: Player) {
        val team = disconnectedPlayers.remove(player.uniqueId) ?: return
        
        // プレイヤーを再度チームに追加
        when (team) {
            Team.RED -> redTeam.add(player.uniqueId)
            Team.BLUE -> blueTeam.add(player.uniqueId)
            Team.SPECTATOR -> spectators.add(player.uniqueId)
        }
        
        // スコアボードを更新
        updateScoreboard()
        
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
            spectators.contains(uuid) -> Team.SPECTATOR
            else -> null
        }
    }
    
    fun getAllPlayers(): Set<Player> {
        return (redTeam + blueTeam + spectators).mapNotNull { Bukkit.getPlayer(it) }.toSet()
    }
    
    fun getTeamPlayers(): Set<Player> {
        // チームプレイヤーのみ（観戦者を除外）
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
        plugin.logger.info(plugin.languageManager.getMessage("log.game-starting", "game" to name, "state" to state.toString(), "phase" to phase.toString()))
        
        if (state != GameState.WAITING) {
            plugin.logger.warning(plugin.languageManager.getMessage("log.game-cannot-start", "game" to name, "state" to state.toString()))
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
        val needNewWorld = tempWorld == null
        
        if (needNewWorld) {
            // テンポラリワールドを作成
            val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
            tempWorld = worldManager.createTempWorld(gameName)
            
            if (tempWorld == null) {
                plugin.logger.warning(plugin.languageManager.getMessage("log.temp-world-failed"))
                getAllPlayers().forEach {
                    it.sendMessage(Component.text(plugin.languageManager.getMessage("join-leave.temp-world-failed")))
                }
                state = GameState.WAITING
                return false
            }
            
        } else {
            plugin.logger.info("[Game] Reusing existing temporary world for match game ${matchWrapper?.currentGameNumber}")
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
                    plugin.logger.warning(plugin.languageManager.getMessage("log.map-restore-failed"))
                } else {
                    plugin.logger.info(plugin.languageManager.getMessage("log.map-restored"))
                }
            } else {
                plugin.logger.info(plugin.languageManager.getMessage("log.no-saved-map"))
            }
        } else {
            plugin.logger.info(plugin.languageManager.getMessage("log.skip-map-restore"))
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
        
        // チャンクの事前読み込み
        preloadSpawnChunks()
        
        // プレイヤーの準備（バッチ処理で安全にテレポート）
        val allPlayers = getAllPlayers().toList()
        
        // 新しいゲーム開始時（またはマッチの最初のゲーム）のみインベントリをクリア
        if (matchWrapper == null || matchWrapper!!.currentGameNumber == 1) {
            allPlayers.forEach { player ->
                player.inventory.clear()
            }
        }
        
        val redPlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.RED }
        val bluePlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.BLUE }
        val spectatorPlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.SPECTATOR }
        
        // チームごとにバッチ処理
        processTeleportBatch(redPlayers, Team.RED, 0L)
        processTeleportBatch(bluePlayers, Team.BLUE, 2L)
        processTeleportBatch(spectatorPlayers, Team.SPECTATOR, 4L)
        
        // 全プレイヤーの初期化処理（テレポート後に実行）
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            getAllPlayers().forEach { player ->
                val team = getPlayerTeam(player.uniqueId)!!
                
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
        }, 10L) // 0.5秒後に実行
        
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
        
        // 旗とスポーン地点の設置（ブロック記録クリア後に実行）
        setupFlags()
        setupSpawnAreas()
        
        // ショップの購入履歴をリセット
        plugin.shopManager.resetGamePurchases(name)
        
        state = GameState.RUNNING
        
        plugin.logger.info(plugin.languageManager.getMessage("log.game-loop-started", "game" to name))
        
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
        actionBarTask?.runTaskTimer(plugin, 0L, 20L) // 1秒ごとに更新（頻度を下げる）
    }
    
    private fun transitionToCombatPhase() {
        phase = GamePhase.COMBAT
        currentPhaseTime = combatDuration
        
        // リスポーンタスクをクリア（建築フェーズからの移行時）
        respawnTasks.values.forEach { task ->
            plugin.logger.info(plugin.languageManager.getMessage("log.respawn-task-cancel-combat"))
            task.cancel()
        }
        respawnTasks.clear()
        
        bossBar?.setTitle(plugin.languageManager.getMessage("phase-extended.combat-time-format", "time" to formatTime(currentPhaseTime)))
        bossBar?.color = BarColor.RED
        
        // 窒息チェックタスクを開始
        startSuffocationTask()
        
        // プレイヤーの準備（バッチ処理で安全にテレポート）
        val allPlayers = getAllPlayers().toList()
        val redPlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.RED }
        val bluePlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.BLUE }
        val spectatorPlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.SPECTATOR }
        
        // チームごとにバッチ処理
        processCombatTeleportBatch(redPlayers, Team.RED, 0L)
        processCombatTeleportBatch(bluePlayers, Team.BLUE, 2L)
        processCombatTeleportBatch(spectatorPlayers, Team.SPECTATOR, 4L)
    }
    
    private fun transitionToResultPhase() {
        phase = GamePhase.INTERMISSION
        
        // リスポーンタスクをキャンセル
        respawnTasks.values.forEach { task ->
            plugin.logger.info(plugin.languageManager.getMessage("log.respawn-task-cancel-phase"))
            task.cancel()
        }
        respawnTasks.clear()
        
        // マッチモードで、かつ最終ゲームでない場合は短縮
        currentPhaseTime = if (matchWrapper != null && !matchWrapper!!.isMatchComplete()) {
            intermediateDuration  // マッチ間の作戦会議
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
            // マッチモードでない場合のみインベントリクリア
            if (matchWrapper == null || matchWrapper!!.isMatchComplete()) {
                player.inventory.clear()
            }
            
            // ゲームモード変更
            player.gameMode = GameMode.SPECTATOR
            
            // 勝負表示
            if (winner != null) {
                player.clearTitle() // 既存のタイトルをクリア
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
                player.clearTitle() // 既存のタイトルをクリア
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
            plugin.logger.info(plugin.languageManager.getMessage("log.respawn-task-cancel-match"))
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
        actionBarPriority.clear()
        redFlagCarrier = null
        blueFlagCarrier = null
        
        // マッチモードの場合は旗とスポーン装飾の再設置をスキップ（start()で行う）
        // 通常モードの場合のみ再設置
        if (matchWrapper == null || !matchWrapper!!.isActive) {
            setupFlags()
            setupSpawnAreas()
        }
        
        plugin.logger.info("[Game] Reset complete, state is now: $state")
    }
    
    fun stop(forceStop: Boolean = false) {
        plugin.logger.info("Stopping game: $gameName, current state: $state, forceStop: $forceStop, matchWrapper: ${matchWrapper != null}")
        state = GameState.ENDING
        
        // 全てのタスクをキャンセル
        gameTask?.cancel()
        suffocationTask?.cancel()
        scoreboardUpdateTask?.cancel()
        spawnProtectionTasks.values.forEach { it.cancel() }
        actionBarTask?.cancel()
        
        // リスポーンタスクをキャンセル
        respawnTasks.values.forEach { task ->
            plugin.logger.info(plugin.languageManager.getMessage("log.respawn-task-cancel-stop"))
            task.cancel()
        }
        
        // タスク参照をクリア
        gameTask = null
        suffocationTask = null
        scoreboardUpdateTask = null
        spawnProtectionTasks.clear()
        actionBarTask = null
        respawnTasks.clear()
        
        // マッチモードかどうかチェック（強制終了の場合はマッチモードを無視）
        val isMatchMode = !forceStop && matchWrapper != null && matchWrapper!!.isActive && !matchWrapper!!.isMatchComplete()
        
        // プレイヤーのUUIDリストをコピー（forEach中の変更を避けるため）
        val playerUUIDs = (redTeam + blueTeam).toList()
        
        // プレイヤー処理
        playerUUIDs.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
            // 発光効果を解除
            player.isGlowing = false
            
            // 敵陣メタデータを削除
            player.removeMetadata("on_enemy_block", plugin)
            
            // UIを必ず削除（強制終了時も含む）
            player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
            bossBar?.removePlayer(player)
            
            if (!isMatchMode || forceStop) {
                // マッチモードでない場合、または強制終了の場合
                player.inventory.clear()
                player.gameMode = GameMode.SURVIVAL
                
                // 元のワールドに確実に転送
                val mainWorld = originalWorld
                if (mainWorld != null && mainWorld != tempWorld) {
                    player.teleport(mainWorld.spawnLocation)
                    plugin.logger.info("[Game] Teleporting ${player.name} back to original world: ${mainWorld.name}")
                } else {
                    // フォールバック：メインワールドに転送
                    val fallbackWorld = Bukkit.getWorlds()[0]
                    player.teleport(fallbackWorld.spawnLocation)
                    plugin.logger.warning("[Game] Original world not available, teleporting ${player.name} to main world: ${fallbackWorld.name}")
                }
                
                player.sendMessage(Component.text(plugin.languageManager.getMessage("phase-extended.game-ended")))
                
                // GameManagerからプレイヤーを削除
                val gameManager = plugin.gameManager as com.hacklab.ctf.managers.GameManager
                gameManager.removePlayerFromGame(player)
            } else {
                // マッチモードの場合は、テンポラリワールド内でスポーン地点に戻してアドベンチャーモードに
                val team = getPlayerTeam(player.uniqueId)
                if (team != null && tempWorld != null) {
                    val spawnLocation = when (team) {
                        Team.RED -> redSpawnLocation ?: redFlagLocation
                        Team.BLUE -> blueSpawnLocation ?: blueFlagLocation
                        Team.SPECTATOR -> mapCenterLocation ?: redFlagLocation
                    }
                    if (spawnLocation != null && spawnLocation.world == tempWorld) {
                        // テンポラリワールド内でテレポート
                        player.teleport(spawnLocation)
                        plugin.logger.info("[Game] Teleporting ${player.name} to spawn in temp world: ${tempWorld!!.name}")
                    } else {
                        // スポーン地点が正しく設定されていない場合は、ワールドの中心にテレポート
                        val centerLocation = Location(tempWorld, 0.0, 64.0, 0.0)
                        player.teleport(centerLocation)
                        plugin.logger.warning("[Game] Spawn location not in temp world, teleporting ${player.name} to center")
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
        actionBarPriority.clear()
        redFlagCarrier = null
        blueFlagCarrier = null
        
        // テンポラリワールドをクリーンアップ（マッチモードでは削除しない、ただし強制終了時は削除）
        if ((!isMatchMode || forceStop) && tempWorld != null) {
            val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
            worldManager.cleanupTempWorld(gameName)
            tempWorld = null
            
            // ワールドを元に戻す
            world = originalWorld
        }
        
        // マッチモードでもワールド参照は保持する（強制終了でない場合）
        if (isMatchMode && tempWorld != null && !forceStop) {
            plugin.logger.info("[Game] Keeping temp world for match continuation: ${tempWorld!!.name}")
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
    
    /**
     * スポーン地点周辺のチャンクを事前読み込み
     */
    private fun preloadSpawnChunks() {
        val locations = listOfNotNull(redSpawnLocation, blueSpawnLocation, redFlagLocation, blueFlagLocation)
        locations.forEach { location ->
            val chunkX = location.blockX shr 4
            val chunkZ = location.blockZ shr 4
            
            // 3x3チャンクを事前読み込み
            for (x in -1..1) {
                for (z in -1..1) {
                    location.world.loadChunk(chunkX + x, chunkZ + z, true)
                }
            }
        }
    }
    
    /**
     * プレイヤーをバッチ処理で安全にテレポート
     */
    private fun processTeleportBatch(players: List<Player>, team: Team, initialDelay: Long) {
        if (players.isEmpty()) return
        
        val batchSize = 5 // 一度にテレポートする人数
        var currentDelay = initialDelay
        
        players.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                batch.forEachIndexed { playerIndex, player ->
                    // 各プレイヤーに少しずつ遅延を追加
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        // 観戦者の場合は特別な処理
                        if (team == Team.SPECTATOR) {
                            val centerLocation = getCenterLocation() ?: getSafeSpawnLocation(Team.RED)
                            player.teleport(centerLocation)
                            player.gameMode = GameMode.SPECTATOR
                            // 観戦者にはアイテムを配布しない
                        } else {
                            // 安全なスポーン位置を計算
                            val safeLocation = getSafeSpawnLocation(team)
                            
                            // フェーズ間の移行ではインベントリをクリアしない
                            // （ゲーム開始時のstart()で既にクリアされている）
                            
                            // アクションバーをクリア
                            player.sendActionBar(Component.empty())
                            
                            // ゲームモード設定
                            val targetGameMode = GameMode.valueOf(buildPhaseGameMode)
                            player.gameMode = targetGameMode
                            
                            // 安全な位置にテレポート
                            player.teleport(safeLocation)
                            
                            // 建築フェーズアイテム配布
                            giveBuildPhaseItems(player, team)
                        }
                        
                        // BossBar追加
                        bossBar?.addPlayer(player)
                    }, playerIndex.toLong()) // 各プレイヤーに0.05秒の遅延
                }
            }, currentDelay)
            currentDelay += 5L // バッチ間は0.25秒の遅延
        }
    }
    
    /**
     * 戦闘フェーズ用のプレイヤーバッチテレポート処理
     */
    private fun processCombatTeleportBatch(players: List<Player>, team: Team, initialDelay: Long) {
        if (players.isEmpty()) return
        
        val batchSize = 5
        var currentDelay = initialDelay
        
        players.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                batch.forEachIndexed { playerIndex, player ->
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        // 観戦者の場合は特別な処理
                        if (team == Team.SPECTATOR) {
                            val centerLocation = getCenterLocation() ?: getSafeSpawnLocation(Team.RED)
                            player.teleport(centerLocation)
                            player.gameMode = GameMode.SPECTATOR
                            // 観戦者にはアイテムを配布しない
                        } else {
                            val safeLocation = getSafeSpawnLocation(team)
                            
                            // フェーズ間の移行ではインベントリをクリアしない
                            // （ゲーム開始時のstart()で既にクリアされている）
                            
                            // 建築フェーズ専用アイテム（色付きコンクリート・ガラス）を削除
                            removeTeamColoredBlocks(player)
                            
                            // ゲームモード変更
                            player.gameMode = GameMode.SURVIVAL
                            
                            // 安全な位置にテレポート
                            player.teleport(safeLocation)
                            
                            // 戦闘フェーズアイテム配布
                            giveCombatPhaseItems(player, team)
                        }
                        
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
                    }, playerIndex.toLong())
                }
            }, currentDelay)
            currentDelay += 5L
        }
    }
    
    /**
     * 安全なスポーン位置を計算（プレイヤーが重ならないように分散）
     * 複数スポーン地点がある場合はランダムに選択
     */
    private fun getSafeSpawnLocation(team: Team): Location {
        // GameConfigを使用してランダムスポーン地点を取得
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(gameName)
        val baseLocation = when (team) {
            Team.RED -> config?.getEffectiveRedSpawn() ?: (redSpawnLocation ?: redFlagLocation)
            Team.BLUE -> config?.getEffectiveBlueSpawn() ?: (blueSpawnLocation ?: blueFlagLocation)
            Team.SPECTATOR -> mapCenterLocation ?: redFlagLocation
        } ?: throw IllegalStateException("No spawn location for team $team")
        
        val world = baseLocation.world
        val baseX = baseLocation.blockX
        val baseY = baseLocation.blockY
        val baseZ = baseLocation.blockZ
        
        // スポーン地点から9ブロック範囲内でランダムに試行
        val maxAttempts = 50
        var attempts = 0
        
        while (attempts < maxAttempts) {
            attempts++
            
            // -4〜+4の範囲でランダムな位置を選択（9x9の範囲）
            val offsetX = (Math.random() * 9 - 4).toInt()
            val offsetZ = (Math.random() * 9 - 4).toInt()
            
            val checkX = baseX + offsetX
            val checkZ = baseZ + offsetZ
            
            // その位置で安全な高さを探す
            for (checkY in (baseY - 5)..(baseY + 5)) {
                if (checkY < 0 || checkY > world.maxHeight - 3) continue
                
                val floor = world.getBlockAt(checkX, checkY, checkZ)
                val space1 = world.getBlockAt(checkX, checkY + 1, checkZ)
                val space2 = world.getBlockAt(checkX, checkY + 2, checkZ)
                
                // 床が固体で、上2ブロックが空気の場合
                if (floor.type.isSolid && !floor.type.name.contains("SLAB") && !floor.type.name.contains("STAIRS") &&
                    !space1.type.isSolid && space1.type != Material.WATER && space1.type != Material.LAVA &&
                    !space2.type.isSolid && space2.type != Material.WATER && space2.type != Material.LAVA) {
                    
                    // 足元が安定しているか確認（周囲にもブロックがあるか）
                    var stableGround = 0
                    for (dx in -1..1) {
                        for (dz in -1..1) {
                            val nearbyBlock = world.getBlockAt(checkX + dx, checkY, checkZ + dz)
                            if (nearbyBlock.type.isSolid) {
                                stableGround++
                            }
                        }
                    }
                    
                    // 少なくとも5ブロック以上の安定した足場がある場合のみ使用
                    if (stableGround >= 5) {
                        val safeLocation = Location(world, checkX + 0.5, checkY + 1.0, checkZ + 0.5)
                        
                        // 相手チームの旗の方向を向くように設定
                        val targetFlagLocation = when (team) {
                            Team.RED -> blueFlagLocation
                            Team.BLUE -> redFlagLocation
                            Team.SPECTATOR -> null
                        }
                        
                        if (targetFlagLocation != null) {
                            val direction = targetFlagLocation.toVector().subtract(safeLocation.toVector())
                            val yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z)).toFloat()
                            safeLocation.yaw = yaw
                            safeLocation.pitch = 0f
                        }
                        
                        return safeLocation
                    }
                }
            }
        }
        
        // 安全な場所が見つからない場合は、元のベース位置を使用（ただし高さは調整）
        val fallbackLocation = baseLocation.clone()
        
        // 最後の手段として、ベース位置の高さを調整
        for (checkY in baseY..(baseY + 10)) {
            if (checkY > world.maxHeight - 3) break
            
            val floor = world.getBlockAt(baseX, checkY, baseZ)
            val space1 = world.getBlockAt(baseX, checkY + 1, baseZ)
            val space2 = world.getBlockAt(baseX, checkY + 2, baseZ)
            
            if (floor.type.isSolid && !space1.type.isSolid && !space2.type.isSolid) {
                fallbackLocation.y = checkY + 1.0
                break
            }
        }
        
        plugin.logger.warning(plugin.languageManager.getMessage("log.safe-spawn-not-found", "team" to team.displayName, "attempts" to attempts.toString()))
        return fallbackLocation
    }
    
    private fun teleportToSpawn(player: Player, team: Team) {
        try {
            // 既にテレポート済みのプレイヤー数を考慮して安全な位置を計算
            val safeLocation = getSafeSpawnLocation(team)
            player.teleport(safeLocation)
        } catch (e: IllegalStateException) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("spawn.not-set")))
            plugin.logger.warning("Game $name: No spawn location for team $team")
        }
    }
    
    private fun giveBuildPhaseItems(player: Player, team: Team) {
        // 観戦者には建築フェーズアイテムを配布しない
        if (team == Team.SPECTATOR) return
        
        val inv = player.inventory
        
        // 革の防具（チームカラー）
        giveColoredLeatherArmor(player, team)
        
        // ダイヤピッケル（効率エンチャント付き）を配布（重複チェック）
        if (!hasInitialItem(player, InitialItemType.PICKAXE, Material.DIAMOND_PICKAXE)) {
            val pickaxe = ItemStack(Material.DIAMOND_PICKAXE).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text("§b§l効率的なダイヤピッケル"))
                    lore(listOf(
                        Component.text("§7死亡時にドロップしません"),
                        Component.text("§7初期装備")
                    ))
                    addEnchant(Enchantment.EFFICIENCY, 3, false)
                    isUnbreakable = true
                }
            }
            markAsInitialItem(pickaxe, InitialItemType.PICKAXE)
            inv.addItem(pickaxe)
        }
        
        // ダイヤシャベル（効率エンチャント付き）を配布（重複チェック）
        if (!hasInitialItem(player, InitialItemType.SHOVEL, Material.DIAMOND_SHOVEL)) {
            val shovel = ItemStack(Material.DIAMOND_SHOVEL).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text("§b§l効率的なダイヤシャベル"))
                    lore(listOf(
                        Component.text("§7死亡時にドロップしません"),
                        Component.text("§7初期装備")
                    ))
                    addEnchant(Enchantment.EFFICIENCY, 3, false)
                    isUnbreakable = true
                }
            }
            markAsInitialItem(shovel, InitialItemType.SHOVEL)
            inv.addItem(shovel)
        }
        
        // チームカラーブロックを配布（フェーズごとの個数制限）
        val blocksToGive = if (phase == GamePhase.BUILD) buildPhaseBlocks else combatPhaseBlocks
        
        val teamConcrete = ItemStack(
            when (team) {
                Team.RED -> Material.RED_CONCRETE
                Team.BLUE -> Material.BLUE_CONCRETE
                Team.SPECTATOR -> Material.WHITE_CONCRETE // 観戦者用（到達しないはず）
            },
            blocksToGive
        ).apply {
            itemMeta = itemMeta?.apply {
                displayName(Component.text(plugin.languageManager.getMessage("flag.concrete-name",
                    "color" to team.getChatColor(),
                    "team" to team.displayName
                )))
                lore(listOf(Component.text("§7建築用ブロック")))
            }
        }
        
        val teamGlass = ItemStack(
            when (team) {
                Team.RED -> Material.RED_STAINED_GLASS
                Team.BLUE -> Material.BLUE_STAINED_GLASS
                Team.SPECTATOR -> Material.WHITE_STAINED_GLASS // 観戦者用（到達しないはず）
            },
            blocksToGive
        ).apply {
            itemMeta = itemMeta?.apply {
                displayName(Component.text(plugin.languageManager.getMessage("flag.glass-name",
                    "color" to team.getChatColor(),
                    "team" to team.displayName
                )))
                lore(listOf(Component.text("§7建築用ブロック")))
            }
        }
        
        player.inventory.addItem(teamConcrete)
        player.inventory.addItem(teamGlass)
        
        // ショップアイテムをホットバー9番目に配置（既にない場合のみ）
        if (!hasInitialItem(player, InitialItemType.SHOP_EMERALD, Material.EMERALD)) {
            val shopItem = plugin.shopManager.createShopItem()
            markAsInitialItem(shopItem, InitialItemType.SHOP_EMERALD)
            player.inventory.setItem(8, shopItem)
        }
    }
    
    
    private fun giveCombatPhaseItems(player: Player, team: Team) {
        val inv = player.inventory
        
        // 革の防具（チームカラー）を再装備（防具をリセットして確実に着用）
        giveColoredLeatherArmor(player, team)
        
        // チームカラーブロックを配布（戦闘フェーズ用個数）
        if (combatPhaseBlocks > 0) {
            val teamConcrete = ItemStack(
                when (team) {
                    Team.RED -> Material.RED_CONCRETE
                    Team.BLUE -> Material.BLUE_CONCRETE
                    Team.SPECTATOR -> Material.WHITE_CONCRETE
                },
                combatPhaseBlocks
            ).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text(plugin.languageManager.getMessage("flag.concrete-name",
                        "color" to team.getChatColor(),
                        "team" to team.displayName
                    )))
                    lore(listOf(Component.text("§7建築用ブロック")))
                }
            }
            
            val teamGlass = ItemStack(
                when (team) {
                    Team.RED -> Material.RED_STAINED_GLASS
                    Team.BLUE -> Material.BLUE_STAINED_GLASS
                    Team.SPECTATOR -> Material.WHITE_STAINED_GLASS
                },
                combatPhaseBlocks
            ).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text(plugin.languageManager.getMessage("flag.glass-name",
                        "color" to team.getChatColor(),
                        "team" to team.displayName
                    )))
                    lore(listOf(Component.text("§7建築用ブロック")))
                }
            }
            
            inv.addItem(teamConcrete)
            inv.addItem(teamGlass)
        }
        
        // ダイヤピッケル（効率エンチャント付き）を配布（重複チェック）
        if (!hasInitialItem(player, InitialItemType.PICKAXE, Material.DIAMOND_PICKAXE)) {
            val pickaxe = ItemStack(Material.DIAMOND_PICKAXE).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text("§b§l効率的なダイヤピッケル"))
                    lore(listOf(
                        Component.text("§7死亡時にドロップしません"),
                        Component.text("§7初期装備")
                    ))
                    addEnchant(Enchantment.EFFICIENCY, 3, false)
                    isUnbreakable = true
                }
            }
            markAsInitialItem(pickaxe, InitialItemType.PICKAXE)
            inv.addItem(pickaxe)
        }
        
        // ダイヤシャベル（効率エンチャント付き）を配布（重複チェック）
        if (!hasInitialItem(player, InitialItemType.SHOVEL, Material.DIAMOND_SHOVEL)) {
            val shovel = ItemStack(Material.DIAMOND_SHOVEL).apply {
                itemMeta = itemMeta?.apply {
                    displayName(Component.text("§b§l効率的なダイヤシャベル"))
                    lore(listOf(
                        Component.text("§7死亡時にドロップしません"),
                        Component.text("§7初期装備")
                    ))
                    addEnchant(Enchantment.EFFICIENCY, 3, false)
                    isUnbreakable = true
                }
            }
            markAsInitialItem(shovel, InitialItemType.SHOVEL)
            inv.addItem(shovel)
        }
        
        // チーム識別用にプレイヤー名を色付け
        player.setDisplayName("${team.getChatColor()}${player.name}")
        player.setPlayerListName("${team.getChatColor()}${player.name}")
        
        // 戦闘フェーズではチームカラーブロックは配布しない
        
        // ショップアイテムをホットバー9番目に配置（既にない場合のみ）
        if (!hasInitialItem(player, InitialItemType.SHOP_EMERALD, Material.EMERALD)) {
            val shopItem = plugin.shopManager.createShopItem()
            markAsInitialItem(shopItem, InitialItemType.SHOP_EMERALD)
            player.inventory.setItem(8, shopItem)
        }
    }
    
    private fun giveDefaultCombatItems(player: Player, team: Team) {
        val inv = player.inventory
        // 初期装備なし - ショップで購入する必要がある
        
        // 戦闘フェーズではチームカラーブロックは配布しない
        
        // ショップアイテムをホットバー9番目に配置（既にない場合のみ）
        if (!hasInitialItem(player, InitialItemType.SHOP_EMERALD, Material.EMERALD)) {
            val shopItem = plugin.shopManager.createShopItem()
            markAsInitialItem(shopItem, InitialItemType.SHOP_EMERALD)
            player.inventory.setItem(8, shopItem)
        }
    }
    
    private fun isLeatherArmor(material: Material): Boolean {
        return material in listOf(
            Material.LEATHER_HELMET,
            Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS,
            Material.LEATHER_BOOTS
        )
    }
    
    /**
     * 初期配布アイテムのタイプを定義
     */
    private enum class InitialItemType(val key: String) {
        PICKAXE("initial_pickaxe"),
        SHOVEL("initial_shovel"),
        LEATHER_ARMOR("initial_armor"),
        SHOP_EMERALD("initial_shop")
    }
    
    /**
     * プレイヤーが特定の初期アイテムを既に持っているかチェック
     */
    private fun hasInitialItem(player: Player, itemType: InitialItemType, material: Material? = null): Boolean {
        val key = NamespacedKey(plugin, itemType.key)
        
        return when (itemType) {
            InitialItemType.LEATHER_ARMOR -> {
                // 革防具の場合は装備スロットもチェック
                val equipment = player.equipment ?: return false
                listOf(equipment.helmet, equipment.chestplate, equipment.leggings, equipment.boots).any { item ->
                    item != null && isLeatherArmor(item.type) &&
                    item.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BOOLEAN) == true
                }
            }
            else -> {
                // その他のアイテムはインベントリをチェック
                player.inventory.contents.any { item ->
                    item != null &&
                    (material == null || item.type == material) &&
                    item.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BOOLEAN) == true
                }
            }
        }
    }
    
    /**
     * アイテムに初期配布マークを付ける
     */
    private fun markAsInitialItem(item: ItemStack, itemType: InitialItemType): ItemStack {
        item.itemMeta = item.itemMeta?.apply {
            val key = NamespacedKey(plugin, itemType.key)
            persistentDataContainer.set(key, PersistentDataType.BOOLEAN, true)
            
            // ドロップ不可フラグも同時に設定
            persistentDataContainer.set(
                NamespacedKey(plugin, "no_drop"),
                PersistentDataType.BOOLEAN,
                true
            )
        }
        return item
    }
    
    private fun giveColoredLeatherArmor(player: Player, team: Team) {
        // 観戦者には防具を配布しない
        if (team == Team.SPECTATOR) return
        
        // 既に初期配布の革防具を持っている場合はスキップ
        if (hasInitialItem(player, InitialItemType.LEATHER_ARMOR)) return
        
        val color = when (team) {
            Team.RED -> org.bukkit.Color.RED
            Team.BLUE -> org.bukkit.Color.BLUE
            Team.SPECTATOR -> org.bukkit.Color.GRAY // 観戦者用（到達しないはず）
        }
        
        // 革のヘルメット
        val helmet = ItemStack(Material.LEATHER_HELMET)
        val helmetMeta = helmet.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        helmetMeta?.setColor(color)
        helmetMeta?.isUnbreakable = true
        helmet.itemMeta = helmetMeta
        markAsInitialItem(helmet, InitialItemType.LEATHER_ARMOR)
        
        // 革のチェストプレート
        val chestplate = ItemStack(Material.LEATHER_CHESTPLATE)
        val chestMeta = chestplate.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        chestMeta?.setColor(color)
        chestMeta?.isUnbreakable = true
        chestplate.itemMeta = chestMeta
        markAsInitialItem(chestplate, InitialItemType.LEATHER_ARMOR)
        
        // 革のレギンス
        val leggings = ItemStack(Material.LEATHER_LEGGINGS)
        val legMeta = leggings.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        legMeta?.setColor(color)
        legMeta?.isUnbreakable = true
        leggings.itemMeta = legMeta
        markAsInitialItem(leggings, InitialItemType.LEATHER_ARMOR)
        
        // 革のブーツ
        val boots = ItemStack(Material.LEATHER_BOOTS)
        val bootMeta = boots.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        bootMeta?.setColor(color)
        bootMeta?.isUnbreakable = true
        boots.itemMeta = bootMeta
        markAsInitialItem(boots, InitialItemType.LEATHER_ARMOR)
        
        // 装備
        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots
    }
    
    private fun setupFlags() {
        // 旗の設置（ビーコン）
        Team.values().filter { it != Team.SPECTATOR }.forEach { team ->
            val flagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
            } ?: return@forEach
            
            setupFlagBeacon(flagLocation, team)
        }
    }
    
    fun setupFlagBeacon(location: Location, team: Team) {
        // 観戦者には旗を設置しない
        if (team == Team.SPECTATOR) return
        
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
            Team.SPECTATOR -> Material.WHITE_STAINED_GLASS // 観戦者用（到達しないはず）
        }
        location.clone().add(0.0, 1.0, 0.0).block.type = glassType
        
        // 旗位置を記録
        when (team) {
            Team.RED -> redFlagLocation = location
            Team.BLUE -> blueFlagLocation = location
            Team.SPECTATOR -> {} // 観戦者は旗位置を記録しない
        }
    }
    
    private fun setupSpawnAreas() {
        // GameConfigから複数スポーン地点を取得
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(gameName)
        
        // スポーンエリアの装飾（スポーン地点が設定されている場合のみ）
        Team.values().filter { it != Team.SPECTATOR }.forEach { team ->
            val spawnLocations = when (team) {
                Team.RED -> config?.getAllRedSpawnLocations() ?: listOfNotNull(redSpawnLocation)
                Team.BLUE -> config?.getAllBlueSpawnLocations() ?: listOfNotNull(blueSpawnLocation)
                Team.SPECTATOR -> emptyList()
            }
            
            // すべてのスポーン地点に対して装飾を設置
            spawnLocations.forEach { spawnLocation ->
                setupSpawnDecoration(spawnLocation, team)
            }
        }
    }
    
    private fun setupSpawnDecoration(location: Location, team: Team) {
        // 観戦者にはスポーン装飾を設置しない
        if (team == Team.SPECTATOR) return
        
        // チーム色のコンクリートで3x3の床のみ
        val concreteType = when (team) {
            Team.RED -> Material.RED_CONCRETE
            Team.BLUE -> Material.BLUE_CONCRETE
            Team.SPECTATOR -> Material.WHITE_CONCRETE // 観戦者用（到達しないはず）
        }
        
        // スポーン地点のコンクリートを設置し、チームの配置ブロックとして記録
        val trees = teamBlockTrees.getOrPut(team) { mutableMapOf() }
        val blocks = teamPlacedBlocks.getOrPut(team) { mutableSetOf() }
        
        plugin.logger.info("[SetupSpawn] Setting up spawn decoration for $team at $location")
        
        for (x in -1..1) {
            for (z in -1..1) {
                val blockLocation = location.clone().add(x.toDouble(), -1.0, z.toDouble())
                blockLocation.block.type = concreteType
                
                // チームの配置ブロックとして記録
                blocks.add(blockLocation)
                
                // ツリー構造にも追加（スポーン地点を起点として接続可能にする）
                // スポーン地点のブロックは親なし（ルートノード）として登録
                val node = BlockNode(blockLocation, null)
                trees[blockLocation] = node
                plugin.logger.info("[SetupSpawn] Added spawn block to tree: $blockLocation")
            }
        }
        
        plugin.logger.info("[SetupSpawn] Total blocks in tree for $team: ${trees.size}")
        plugin.logger.info("[SetupSpawn] Total blocks in placedBlocks for $team: ${blocks.size}")
    }
    
    private fun cleanupGameBlocks() {
        // 旗の削除
        Team.values().filter { it != Team.SPECTATOR }.forEach { team ->
            val flagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
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
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(gameName)
        
        Team.values().filter { it != Team.SPECTATOR }.forEach { team ->
            val spawnLocations = when (team) {
                Team.RED -> config?.getAllRedSpawnLocations() ?: listOfNotNull(redSpawnLocation)
                Team.BLUE -> config?.getAllBlueSpawnLocations() ?: listOfNotNull(blueSpawnLocation)
                Team.SPECTATOR -> emptyList()
            }
            
            // すべてのスポーン地点のコンクリート床を削除
            spawnLocations.forEach { spawnLocation ->
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
    }
    
    private fun setupScoreboard(player: Player) {
        try {
            // プレイヤーごとに個別のスコアボードを作成
            val playerScoreboard = Bukkit.getScoreboardManager().newScoreboard
            val playerObjective = playerScoreboard.registerNewObjective("ctf_game", "dummy", 
                Component.text("CTF - $gameName", NamedTextColor.GOLD))
            playerObjective.displaySlot = DisplaySlot.SIDEBAR
            
            // チームを作成（タブリスト色分け用）
            setupScoreboardTeams(playerScoreboard)
            
            // プレイヤーに設定
            player.scoreboard = playerScoreboard
            
            // プレイヤーを適切なチームに追加
            updatePlayerTeamColor(player, playerScoreboard)
            
            // 初回更新
            updatePlayerScoreboard(player)
            
        } catch (e: Exception) {
            plugin.logger.warning("Failed to setup scoreboard for player ${player.name}: ${e.message}")
        }
    }
    
    private fun setupScoreboardTeams(scoreboard: Scoreboard) {
        // 赤チーム
        if (scoreboard.getTeam("ctf_red") == null) {
            val redTeam = scoreboard.registerNewTeam("ctf_red")
            redTeam.color(NamedTextColor.RED)
            redTeam.prefix(Component.text("[赤] ", NamedTextColor.RED))
        }
        
        // 青チーム
        if (scoreboard.getTeam("ctf_blue") == null) {
            val blueTeam = scoreboard.registerNewTeam("ctf_blue")
            blueTeam.color(NamedTextColor.BLUE)
            blueTeam.prefix(Component.text("[青] ", NamedTextColor.BLUE))
        }
        
        // 観戦者チーム
        if (scoreboard.getTeam("ctf_spectator") == null) {
            val spectatorTeam = scoreboard.registerNewTeam("ctf_spectator")
            spectatorTeam.color(NamedTextColor.GRAY)
            spectatorTeam.prefix(Component.text("[観戦] ", NamedTextColor.GRAY))
        }
    }
    
    private fun updatePlayerTeamColor(player: Player, scoreboard: Scoreboard) {
        val playerTeam = getPlayerTeam(player.uniqueId)
        
        // 既存のチームから削除
        scoreboard.teams.forEach { team ->
            if (team.hasEntry(player.name)) {
                team.removeEntry(player.name)
            }
        }
        
        // 新しいチームに追加
        when (playerTeam) {
            Team.RED -> scoreboard.getTeam("ctf_red")?.addEntry(player.name)
            Team.BLUE -> scoreboard.getTeam("ctf_blue")?.addEntry(player.name)
            Team.SPECTATOR -> scoreboard.getTeam("ctf_spectator")?.addEntry(player.name)
            else -> {} // チーム未所属
        }
        
        // 他のプレイヤーのタブリストも更新
        getAllPlayers().forEach { otherPlayer ->
            if (otherPlayer != player) {
                val otherScoreboard = otherPlayer.scoreboard
                if (otherScoreboard != Bukkit.getScoreboardManager().mainScoreboard) {
                    setupScoreboardTeams(otherScoreboard)
                    
                    // このプレイヤーを他のプレイヤーのスコアボードにも追加
                    otherScoreboard.teams.forEach { team ->
                        if (team.hasEntry(player.name)) {
                            team.removeEntry(player.name)
                        }
                    }
                    
                    when (playerTeam) {
                        Team.RED -> otherScoreboard.getTeam("ctf_red")?.addEntry(player.name)
                        Team.BLUE -> otherScoreboard.getTeam("ctf_blue")?.addEntry(player.name)
                        Team.SPECTATOR -> otherScoreboard.getTeam("ctf_spectator")?.addEntry(player.name)
                        else -> {}
                    }
                    
                    // 逆に、他のプレイヤーも現在のプレイヤーのスコアボードに追加
                    val otherTeam = getPlayerTeam(otherPlayer.uniqueId)
                    when (otherTeam) {
                        Team.RED -> scoreboard.getTeam("ctf_red")?.addEntry(otherPlayer.name)
                        Team.BLUE -> scoreboard.getTeam("ctf_blue")?.addEntry(otherPlayer.name)
                        Team.SPECTATOR -> scoreboard.getTeam("ctf_spectator")?.addEntry(otherPlayer.name)
                        else -> {}
                    }
                }
            }
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
    
    fun updatePlayerTabColor(player: Player) {
        // プレイヤーのタブリスト色を更新
        val scoreboard = player.scoreboard
        if (scoreboard != Bukkit.getScoreboardManager().mainScoreboard) {
            updatePlayerTeamColor(player, scoreboard)
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
            val spectatorCount = spectators.size
            
            // 観戦者がいる場合は表示
            if (spectatorCount > 0) {
                obj.getScore("§c赤${redCount} §9青${blueCount} §7観戦${spectatorCount}").score = line--
            } else {
                obj.getScore("§c赤${redCount} §9青${blueCount}").score = line--
            }
            
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
        
        // 自チームの通貨を表示（観戦者以外）
        val playerTeam = getPlayerTeam(player.uniqueId)
        if (playerTeam != null && playerTeam != Team.SPECTATOR) {
            val currency = if (matchWrapper != null) {
                matchWrapper!!.getTeamCurrency(playerTeam)
            } else {
                getTeamCurrency(playerTeam)
            }
            obj.getScore("§e${currency}G").score = line--
        } else if (playerTeam == Team.SPECTATOR) {
            // 観戦者用の表示
            obj.getScore("§7[観戦モード]").score = line--
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
        // 観戦者は旗を持つことができない
        if (team == Team.SPECTATOR) return
        
        val carrier = when (team) {
            Team.RED -> redFlagCarrier
            Team.BLUE -> blueFlagCarrier
            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
        }
        
        if (carrier != player.uniqueId) return
        
        // キャリアをクリア
        when (team) {
            Team.RED -> redFlagCarrier = null
            Team.BLUE -> blueFlagCarrier = null
            Team.SPECTATOR -> {} // 観戦者は旗キャリアではない
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
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
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
                    Team.SPECTATOR -> null // 観戦者用（到達しないはず）
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
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
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
            Team.SPECTATOR -> {} // 観戦者は旗を持てない
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
                            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
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
                            Team.SPECTATOR -> {} // 観戦者は旗を持てない
                        }
                        
                        // ドロップフラグから削除
                        droppedFlags.entries.removeIf { (location, data) ->
                            data.first == flagTeam && location.distance(item.location) < 2.0
                        }
                        
                        // アイテムを削除
                        item.remove()
                        
                        // スポーン保護を解除（旗を持った時点で保護解除）
                        removeSpawnProtection(player)
                        
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
        
        // 観戦者は旗をキャプチャできない
        if (team == Team.SPECTATOR) return false
        
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
            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
        } ?: return false
        
        if (player.location.distance(ownFlagLocation) > 3.0) {
            return false
        }
        
        // 自分のチームの旗が自陣にあるかチェック
        val ownFlagIsAtBase = when (team) {
            Team.RED -> redFlagCarrier == null && !droppedFlags.any { it.value.first == Team.RED }
            Team.BLUE -> blueFlagCarrier == null && !droppedFlags.any { it.value.first == Team.BLUE }
            Team.SPECTATOR -> false // 観戦者用（到達しないはず）
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
            Team.SPECTATOR -> {} // 観戦者は旗を持てない
        }
        
        // グロー効果を解除
        player.isGlowing = false
        
        // 敵の旗を元の位置に戻す
        val enemyFlagLocation = when (carriedFlagTeam) {
            Team.RED -> redFlagLocation
            Team.BLUE -> blueFlagLocation
            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
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
            p.clearTitle() // 既存のタイトルをクリア
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
        // 建築フェーズでは、リスポーン時に基本アイテムを復元する
        when (phase) {
            GamePhase.BUILD -> {
                // 観戦者には建築フェーズアイテムを配布しない
                if (team != Team.SPECTATOR) {
                    // リスポーン時はブロックを再配布しない（消費型のため）
                    // ピッケルやシャベルなどの初期装備のみチェック
                
                // ショップアイテムがない場合のみ再配布
                val existingShopItem = player.inventory.getItem(8)
                if (existingShopItem?.type != Material.EMERALD || !plugin.shopManager.isShopItem(existingShopItem)) {
                    val shopItem = plugin.shopManager.createShopItem()
                    player.inventory.setItem(8, shopItem)
                }
                
                // 革防具がない場合は再配布（初回死亡時など）
                if (player.equipment?.helmet?.type != Material.LEATHER_HELMET) {
                    plugin.equipmentManager.giveArmor(player, team)
                }
                } // 観戦者チェックの終了
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
    
    /**
     * スポーン保護を即座に解除
     * 攻撃や旗取得時に呼び出される
     */
    fun removeSpawnProtection(player: Player) {
        if (spawnProtection.remove(player.uniqueId) != null) {
            player.isGlowing = false
            spawnProtectionTasks[player.uniqueId]?.cancel()
            spawnProtectionTasks.remove(player.uniqueId)
            player.sendMessage(Component.text(plugin.languageManager.getMessage("spawn.protection-cancelled"), NamedTextColor.YELLOW))
        }
    }
    
    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return String.format("%d:%02d", min, sec)
    }
    
    /**
     * アクションバーにエラーメッセージを表示（優先度付き）
     */
    fun showActionBarError(player: Player, message: String, duration: Long = 3000L) {
        val now = System.currentTimeMillis()
        actionBarErrorDisplay[player.uniqueId] = Pair(message, now + duration)
        actionBarPriority[player.uniqueId] = 100 // エラーメッセージは最高優先度
        player.sendActionBar(Component.text(message, NamedTextColor.RED))
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
                actionBarPriority.remove(player.uniqueId)
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
                                Team.SPECTATOR -> plugin.languageManager.getMessage("flag.flag-carrier-unknown") // 観戦者用（到達しないはず）
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
        
        // 観戦者はブロックを設置できない
        if (team == Team.SPECTATOR) return null
        
        val location = block.location
        
        plugin.logger.info("[CanPlaceBlock] Player: ${player.name}, Team: $team, Location: $location, BlockType: ${block.type}")
        
        // スポーン地点と旗周辺の建築制限チェック（3x3、Y座標全て）
        // 赤チームの旗周辺チェック
        redFlagLocation?.let { flagLoc ->
            if (kotlin.math.abs(location.blockX - flagLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - flagLoc.blockZ) <= 1) {
                showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-flag"))
                return null
            }
        }
        
        // 青チームの旗周辺チェック
        blueFlagLocation?.let { flagLoc ->
            if (kotlin.math.abs(location.blockX - flagLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - flagLoc.blockZ) <= 1) {
                showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-flag"))
                return null
            }
        }
        
        // 複数スポーン地点に対応
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(gameName)
        
        // 赤チームのスポーン地点周辺チェック（複数対応）
        val redSpawnLocations = config?.getAllRedSpawnLocations() ?: listOfNotNull(redSpawnLocation)
        for (spawnLoc in redSpawnLocations) {
            if (kotlin.math.abs(location.blockX - spawnLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - spawnLoc.blockZ) <= 1) {
                showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-spawn"))
                return null
            }
        }
        
        // 青チームのスポーン地点周辺チェック（複数対応）
        val blueSpawnLocations = config?.getAllBlueSpawnLocations() ?: listOfNotNull(blueSpawnLocation)
        for (spawnLoc in blueSpawnLocations) {
            if (kotlin.math.abs(location.blockX - spawnLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - spawnLoc.blockZ) <= 1) {
                showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-spawn"))
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
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
            } ?: return null
            
            // 旗から3ブロック以内かチェック（旗がルート）
            if (location.distance(teamFlagLocation) <= 3.0) {
                return teamFlagLocation
            }
            
            // スポーン地点から3ブロック以内かチェック（スポーン地点もルート）
            val teamSpawnLocations = when (team) {
                Team.RED -> config?.getAllRedSpawnLocations() ?: listOfNotNull(redSpawnLocation)
                Team.BLUE -> config?.getAllBlueSpawnLocations() ?: listOfNotNull(blueSpawnLocation)
                Team.SPECTATOR -> emptyList()
            }
            
            plugin.logger.info("[CanPlaceBlock] Checking ${teamSpawnLocations.size} spawn locations")
            
            // すべてのスポーン地点をチェック
            for (teamSpawnLocation in teamSpawnLocations) {
                if (location.distance(teamSpawnLocation) <= 3.0) {
                    plugin.logger.info("[CanPlaceBlock] Within 3 blocks of spawn at $teamSpawnLocation")
                    
                    // スポーン地点の最も近いブロックを親として返す
                    // ツリーに登録されているスポーン地点のブロックから選ぶ
                    val trees = teamBlockTrees[team] ?: mutableMapOf()
                    plugin.logger.info("[CanPlaceBlock] Tree size for $team: ${trees.size}")
                    
                    val spawnBlocks = trees.keys
                        .filter { 
                            it.blockY == teamSpawnLocation.blockY - 1 && // スポーン地点の床ブロック
                            kotlin.math.abs(it.blockX - teamSpawnLocation.blockX) <= 1 && // スポーン地点の3x3範囲内
                            kotlin.math.abs(it.blockZ - teamSpawnLocation.blockZ) <= 1
                        }
                    
                    plugin.logger.info("[CanPlaceBlock] Found ${spawnBlocks.size} spawn blocks in tree")
                    
                    val nearestBlock = spawnBlocks.minByOrNull { it.distance(location) }
                    
                    if (nearestBlock != null) {
                        plugin.logger.info("[CanPlaceBlock] Returning nearest spawn block: $nearestBlock")
                        return nearestBlock
                    } else {
                        plugin.logger.info("[CanPlaceBlock] No spawn blocks found in tree for this spawn location")
                    }
                }
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
            showActionBarError(player, plugin.languageManager.getMessage("action-bar.place-restriction-flag"))
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
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
            } ?: return null
            
            if (location.distance(teamFlagLocation) <= 3.0) {
                return teamFlagLocation
            }
            
            // スポーン地点から3ブロック以内かもチェック
            val teamSpawnLocation = when (team) {
                Team.RED -> redSpawnLocation
                Team.BLUE -> blueSpawnLocation
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
            }
            if (teamSpawnLocation != null && location.distance(teamSpawnLocation) <= 3.0) {
                // スポーン地点の最も近いブロックを親として返す
                // ツリーに登録されているスポーン地点のブロックから選ぶ
                val trees = teamBlockTrees[team] ?: mutableMapOf()
                return trees.keys
                    .filter { 
                        it.blockY == teamSpawnLocation.blockY - 1 && // スポーン地点の床ブロック
                        kotlin.math.abs(it.blockX - teamSpawnLocation.blockX) <= 1 && // スポーン地点の3x3範囲内
                        kotlin.math.abs(it.blockZ - teamSpawnLocation.blockZ) <= 1
                    }
                    .minByOrNull { it.distance(location) }
            }
            
            showActionBarError(player, plugin.languageManager.getMessage("action-bar.place-restriction-team"))
            return null
            
        } else {
            // その他のブロックは設置不可
            showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-block"))
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
            Material.DISPENSER, Material.TNT
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
                            Team.SPECTATOR -> false // 観戦者は敵ブロックでダメージを受けない
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
        // 観戦者にはチームブロックがない
        if (team == Team.SPECTATOR) return emptySet()
        
        val reachable = mutableSetOf<Location>()
        val queue = mutableListOf<Location>()
        
        // ビーコンの位置を起点として追加
        val beaconLocation = when (team) {
            Team.RED -> redFlagLocation
            Team.BLUE -> blueFlagLocation
            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
        } ?: return emptySet()
        
        // スポーン地点を取得（複数対応）
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(gameName)
        val spawnLocations = when (team) {
            Team.RED -> config?.getAllRedSpawnLocations() ?: listOfNotNull(redSpawnLocation ?: redFlagLocation)
            Team.BLUE -> config?.getAllBlueSpawnLocations() ?: listOfNotNull(blueSpawnLocation ?: blueFlagLocation)
            Team.SPECTATOR -> emptyList()
        }
        
        // ビーコン周辺3ブロック以内のブロックを起点に追加
        val teamBlocks = teamPlacedBlocks[team] ?: return emptySet()
        for (block in teamBlocks) {
            if (block.distance(beaconLocation) <= 3.0) {
                queue.add(block)
                reachable.add(block)
            }
        }
        
        // すべてのスポーン地点周辺3ブロック以内のブロックも起点に追加
        for (spawnLocation in spawnLocations) {
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
    
    /**
     * プレイヤーのインベントリから色付きコンクリート・ガラスを削除
     */
    private fun removeTeamColoredBlocks(player: Player) {
        val inventory = player.inventory
        val itemsToRemove = mutableListOf<Material>()
        
        // 削除対象のマテリアル
        itemsToRemove.addAll(listOf(
            Material.RED_CONCRETE,
            Material.BLUE_CONCRETE,
            Material.RED_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS
        ))
        
        // インベントリから削除（全てのチームカラーブロックを削除）
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i)
            if (item != null && itemsToRemove.contains(item.type)) {
                inventory.setItem(i, null)
            }
        }
    }
}