package com.hacklab.ctf.managers

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.MatchWrapper
import com.hacklab.ctf.config.ConfigManager
import com.hacklab.ctf.config.GameConfig
import com.hacklab.ctf.session.GameSetupSession
import com.hacklab.ctf.map.CompressedMapManager
import com.hacklab.ctf.map.MapRegion
import com.hacklab.ctf.map.MapScanner
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.MatchMode
import com.hacklab.ctf.utils.Team
import com.hacklab.ctf.utils.WorldEditHelper
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * シンプル化されたゲーム管理クラス
 * 責務を明確に分離し、各機能を専用クラスに委譲
 */
class GameManager(private val plugin: Main) {
    
    // 管理対象
    private val games = ConcurrentHashMap<String, Game>()
    private val matches = ConcurrentHashMap<String, MatchWrapper>()
    
    // マップ管理
    private val mapManager = CompressedMapManager(plugin)
    private val mapScanner = MapScanner()
    private val mapPositions = ConcurrentHashMap<String, MapPositions>()
    private val tempMapPositions = ConcurrentHashMap<Player, MapPositions>() // プレイヤーごとの一時的なマップ範囲
    
    data class MapPositions(
        var pos1: Location? = null,
        var pos2: Location? = null
    )
    
    data class MapSaveResult(
        val success: Boolean,
        val errors: List<String> = emptyList(),
        val redSpawn: String? = null,
        val blueSpawn: String? = null,
        val redFlag: String? = null,
        val blueFlag: String? = null
    )
    private val playerGames = ConcurrentHashMap<UUID, String>()
    
    // 専用マネージャー
    private val configManager = ConfigManager(plugin)
    private val setupSession = GameSetupSession(plugin).apply {
        onCreateComplete = { config ->
            createGameFromConfig(config)
        }
        onUpdateComplete = { config ->
            updateGameFromConfig(config)
        }
    }
    
    init {
        loadAllGames()
    }
    
    /**
     * ゲーム作成（対話形式）
     */
    fun startCreateGame(player: Player, gameName: String): Boolean {
        if (games.containsKey(gameName.lowercase())) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("manager.game-name-exists"), NamedTextColor.RED))
            return false
        }
        
        // WorldEditの選択範囲を確認
        val worldEditSelection = if (WorldEditHelper.isWorldEditAvailable()) {
            WorldEditHelper.getPlayerSelection(player)
        } else null
        
        // プレイヤーの一時的なマップ領域または既存のゲームのマップ領域を確認
        val tempPositions = getTempMapPositions(player)
        val hasTempMapRegion = tempPositions?.pos1 != null && tempPositions.pos2 != null
        
        val gamePositions = mapPositions[gameName.lowercase()]
        val hasGameMapRegion = gamePositions?.pos1 != null && gamePositions.pos2 != null
        
        if (worldEditSelection != null || hasTempMapRegion || hasGameMapRegion) {
            // WorldEditの選択範囲を優先的に使用
            if (worldEditSelection != null) {
                mapPositions[gameName.lowercase()] = MapPositions(
                    worldEditSelection.first.clone(),
                    worldEditSelection.second.clone()
                )
                player.sendMessage(Component.text("WorldEditの選択範囲を使用します", NamedTextColor.GRAY))
            }
            // 一時的なマップ範囲がある場合は、ゲームのマップ範囲に移動
            else if (hasTempMapRegion && tempPositions != null) {
                mapPositions[gameName.lowercase()] = MapPositions(
                    tempPositions.pos1?.clone(),
                    tempPositions.pos2?.clone()
                )
                clearTempMapPositions(player)
                player.sendMessage(Component.text(plugin.languageManager.getMessage("manager.using-existing-map"), NamedTextColor.GRAY))
            }
            
            // 自動検出でゲーム作成
            val result = saveMap(gameName)
            if (result.success) {
                player.sendMessage(Component.text("ゲーム '$gameName' を作成しました！", NamedTextColor.GREEN))
                player.sendMessage(Component.text("検出結果:", NamedTextColor.AQUA))
                player.sendMessage(Component.text("- 赤チームスポーン: ${result.redSpawn}", NamedTextColor.RED))
                player.sendMessage(Component.text("- 青チームスポーン: ${result.blueSpawn}", NamedTextColor.BLUE))
                player.sendMessage(Component.text("- 赤チーム旗: ${result.redFlag}", NamedTextColor.RED))
                player.sendMessage(Component.text("- 青チーム旗: ${result.blueFlag}", NamedTextColor.BLUE))
            } else {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("manager.auto-detect-failed"), NamedTextColor.RED))
                result.errors.forEach { error ->
                    player.sendMessage(Component.text("- $error", NamedTextColor.YELLOW))
                }
                player.sendMessage(Component.text(plugin.languageManager.getMessage("manager.place-required-blocks"), NamedTextColor.YELLOW))
                player.sendMessage(Component.text("必要なブロック:", NamedTextColor.GRAY))
                player.sendMessage(Component.text("- 赤コンクリート: 赤チームスポーン", NamedTextColor.GRAY))
                player.sendMessage(Component.text("- 青コンクリート: 青チームスポーン", NamedTextColor.GRAY))
                player.sendMessage(Component.text("- ビーコン+赤ガラス: 赤チーム旗", NamedTextColor.GRAY))
                player.sendMessage(Component.text("- ビーコン+青ガラス: 青チーム旗", NamedTextColor.GRAY))
            }
            return true
        }
        
        // マップ領域が設定されていない場合
        player.sendMessage(Component.text(plugin.languageManager.getMessage("manager.map-area-not-set"), NamedTextColor.RED))
        player.sendMessage(Component.text("以下のいずれかの方法で範囲を設定してください:", NamedTextColor.YELLOW))
        if (WorldEditHelper.isWorldEditAvailable()) {
            player.sendMessage(Component.text("1. WorldEdit: //pos1 と //pos2", NamedTextColor.GRAY))
        }
        player.sendMessage(Component.text("2. CTFコマンド: /ctf setpos1 と /ctf setpos2", NamedTextColor.GRAY))
        return false
    }
    
    /**
     * ゲーム更新（対話形式）
     */
    fun startUpdateGame(player: Player, gameName: String): Boolean {
        val game = games[gameName.lowercase()] ?: run {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return false
        }
        
        if (game.state != GameState.WAITING) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("manager.cannot-update-running"), NamedTextColor.RED))
            return false
        }
        
        val config = configManager.loadConfig(gameName) ?: run {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("manager.config-not-found"), NamedTextColor.RED))
            return false
        }
        
        return setupSession.startUpdateSession(player, config)
    }
    
    /**
     * ゲーム削除
     */
    fun deleteGame(name: String): Boolean {
        val gameName = name.lowercase()
        val game = games[gameName] ?: return false
        
        // 実行中なら停止
        if (game.state != GameState.WAITING) {
            game.stop()
        }
        
        // プレイヤーを退出
        game.getAllPlayers().forEach { player ->
            removePlayerFromGame(player)
        }
        
        // 削除
        games.remove(gameName)
        matches.remove(gameName)
        configManager.deleteConfig(gameName)
        
        return true
    }
    
    /**
     * ゲーム開始（単体またはマッチ）
     */
    fun startGame(name: String, isMatch: Boolean = false, target: Int? = null): Boolean {
        plugin.logger.info("[GameManager] startGame called: name=$name, isMatch=$isMatch, target=$target")
        
        val game = games[name.lowercase()] ?: return false
        val config = configManager.loadConfig(name) ?: return false
        
        if (!config.isValid()) {
            plugin.logger.warning("[GameManager] Config is not valid for game $name")
            return false
        }
        
        // マッチとして開始
        if (isMatch && target != null) {
            plugin.logger.info("[GameManager] Starting as match with $target games")
            
            plugin.logger.info("[GameManager] Creating match with target: $target games")
            
            val matchWrapper = MatchWrapper(config.copy().apply {
                matchMode = MatchMode.FIXED_ROUNDS
                matchTarget = target
            }, plugin)
            
            plugin.logger.info("[GameManager] Match created with config.matchTarget: ${matchWrapper.config.matchTarget}")
            
            matches[name.lowercase()] = matchWrapper
            game.setMatchContext(matchWrapper)
            matchWrapper.isActive = true
            
            // ゲーム終了時のコールバックを設定
            game.setGameEndCallback { winner ->
                plugin.logger.info("[GameManager] Game end callback triggered for match $name")
                handleMatchGameEnd(name, winner)
            }
            
            plugin.logger.info("[GameManager] Match initialized successfully")
        } else {
            plugin.logger.info("[GameManager] Starting as single game")
        }
        
        return game.start()
    }
    
    /**
     * プレイヤーをゲームに追加
     */
    fun addPlayerToGame(player: Player, gameName: String, forceJoin: Boolean = false): Boolean {
        val game = games[gameName.lowercase()] ?: return false
        
        // 既存ゲームチェック
        val currentGame = getPlayerGame(player)
        if (currentGame != null && !forceJoin) {
            return false
        }
        
        currentGame?.let { removePlayerFromGame(player) }
        
        if (game.addPlayer(player)) {
            playerGames[player.uniqueId] = gameName.lowercase()
            matches[gameName.lowercase()]?.players?.put(player.uniqueId, player)
            return true
        }
        
        return false
    }
    
    /**
     * プレイヤーをゲームから削除
     */
    fun removePlayerFromGame(player: Player) {
        val game = getPlayerGame(player) ?: return
        game.removePlayer(player)
        playerGames.remove(player.uniqueId)
        
        val gameName = games.entries.find { it.value == game }?.key
        gameName?.let {
            matches[it]?.players?.remove(player.uniqueId)
        }
    }
    
    /**
     * プレイヤー切断処理
     */
    fun handlePlayerDisconnect(player: Player) {
        getPlayerGame(player)?.handleDisconnect(player)
        setupSession.clearSession(player)
    }
    
    /**
     * プレイヤー再接続処理
     */
    fun handlePlayerReconnect(player: Player) {
        val gameName = playerGames[player.uniqueId] ?: return
        val game = games[gameName] ?: return
        
        if (game.disconnectedPlayers.containsKey(player.uniqueId)) {
            game.handleReconnect(player)
        }
    }
    
    /**
     * チャット入力処理（セッション用）
     */
    fun handleChatInput(player: Player, message: String) {
        if (setupSession.hasActiveSession(player)) {
            setupSession.handleInput(player, message)
        }
    }
    
    /**
     * ゲッター
     */
    fun getGame(name: String): Game? = games[name.lowercase()]
    fun getMatch(name: String): MatchWrapper? = matches[name.lowercase()]
    fun getAllGames(): Map<String, Game> = games.toMap()
    fun getGameConfig(name: String): GameConfig? = configManager.loadConfig(name)
    fun getPlayerGame(player: Player): Game? {
        val gameName = playerGames[player.uniqueId] ?: return null
        return games[gameName]
    }
    
    fun isInSetupSession(player: Player): Boolean = setupSession.hasActiveSession(player)
    
    /**
     * 設定からゲーム作成
     */
    private fun createGameFromConfig(config: GameConfig) {
        val game = Game(config.name, plugin, config.world).apply {
            // 位置設定
            config.redFlagLocation?.let { setRedFlagLocation(it) }
            config.redSpawnLocation?.let { setRedSpawnLocation(it) }
            config.blueFlagLocation?.let { setBlueFlagLocation(it) }
            config.blueSpawnLocation?.let { setBlueSpawnLocation(it) }
            
            // ゲーム設定
            autoStartEnabled = config.autoStartEnabled
            minPlayers = config.minPlayers
            maxPlayersPerTeam = config.maxPlayersPerTeam
            respawnDelay = config.respawnDelay
            buildDuration = config.buildDuration
            buildPhaseGameMode = config.buildPhaseGameMode
            combatDuration = config.combatDuration
            resultDuration = config.resultDuration
        }
        
        games[config.name.lowercase()] = game
        configManager.saveConfig(config)
    }
    
    /**
     * 設定からゲーム更新
     */
    private fun updateGameFromConfig(config: GameConfig) {
        val game = games[config.name.lowercase()] ?: return
        
        // 位置設定更新
        config.redFlagLocation?.let { game.setRedFlagLocation(it) }
        config.redSpawnLocation?.let { game.setRedSpawnLocation(it) }
        config.blueFlagLocation?.let { game.setBlueFlagLocation(it) }
        config.blueSpawnLocation?.let { game.setBlueSpawnLocation(it) }
        
        // ゲーム設定更新
        game.minPlayers = config.minPlayers
        game.maxPlayersPerTeam = config.maxPlayersPerTeam
        game.respawnDelay = config.respawnDelay
        game.buildDuration = config.buildDuration
        game.buildPhaseGameMode = config.buildPhaseGameMode
        game.combatDuration = config.combatDuration
        game.resultDuration = config.resultDuration
        
        configManager.saveConfig(config)
    }
    
    /**
     * 全ゲーム読み込み
     */
    private fun loadAllGames() {
        configManager.loadAllConfigs().forEach { (name, config) ->
            createGameFromConfig(config)
            plugin.logger.info("Loaded game: $name")
        }
    }
    
    /**
     * マッチ内のゲーム終了処理
     */
    private fun handleMatchGameEnd(gameName: String, winner: Team?) {
        plugin.logger.info("[GameManager] handleMatchGameEnd called for $gameName, winner: $winner")
        
        val game = games[gameName.lowercase()] ?: return
        val match = matches[gameName.lowercase()] ?: return
        
        plugin.logger.info("[GameManager] Current match state - Game ${match.currentGameNumber}/${match.config.matchTarget}, isComplete: ${match.isMatchComplete()}")
        
        // マッチのスコアを更新
        match.onGameEnd(winner)
        
        // 勝敗表示
        val winnerText = when (winner) {
            Team.RED -> "§c赤チームの勝利！"
            Team.BLUE -> "§9青チームの勝利！"
            null -> "§e引き分け！"
        }
        
        game.getAllPlayers().forEach { player ->
            player.sendMessage("§6=== ゲーム ${match.currentGameNumber} 結果 ===")
            player.sendMessage(winnerText)
            player.sendMessage("§e現在のスコア: §c赤 ${match.matchWins[Team.RED]} §f- §9青 ${match.matchWins[Team.BLUE]}")
            
            
            if (match.isMatchComplete()) {
                val matchWinner = match.getMatchWinner(game)
                val matchWinnerText = when (matchWinner) {
                    Team.RED -> "§c§l赤チームがマッチに勝利！"
                    Team.BLUE -> "§9§l青チームがマッチに勝利！"
                    null -> "§e§lマッチは引き分け！"
                }
                player.sendMessage("")
                player.sendMessage("§6§l=== マッチ終了 ===")
                player.sendMessage(matchWinnerText)
                
                // 5ゲーム終了時で同点の場合、色ブロック数も表示
                if (match.currentGameNumber >= 5 && match.matchWins[Team.RED] == match.matchWins[Team.BLUE]) {
                    val redBlocks = game.teamPlacedBlocks[Team.RED]?.size ?: 0
                    val blueBlocks = game.teamPlacedBlocks[Team.BLUE]?.size ?: 0
                    player.sendMessage("§7色ブロック数: §c赤 $redBlocks §f- §9青 $blueBlocks")
                }
            } else {
                val intervalSeconds = match.config.matchIntervalDuration
                player.sendMessage("")
                player.sendMessage("§a${intervalSeconds}秒後に次のゲームが開始されます...")
            }
        }
        
        // マッチが完了したか確認
        if (match.isMatchComplete()) {
            // マッチ終了処理
            handleMatchComplete(gameName)
        } else {
            // インターバル表示を開始
            showMatchInterval(game, match, gameName)
        }
    }
    
    /**
     * マッチインターバル表示
     */
    private fun showMatchInterval(game: Game, match: MatchWrapper, gameName: String) {
        // 設定から休憩時間を取得
        var remainingSeconds = match.config.matchIntervalDuration
        
        // プレイヤーに次のゲームまでの時間を通知
        game.getAllPlayers().forEach { player ->
            player.sendMessage(Component.text(""))
            player.sendMessage(Component.text("${remainingSeconds}秒後に次のゲームが開始されます...", NamedTextColor.GREEN))
        }
        
        object : BukkitRunnable() {
            override fun run() {
                if (remainingSeconds > 5) {
                    // 5秒前まではカウントダウンを表示しない
                    remainingSeconds--
                    return
                }
                
                remainingSeconds--
                
                when (remainingSeconds) {
                    4 -> {
                        game.getAllPlayers().forEach { player ->
                            player.sendMessage(Component.text("5秒後に開始します...", NamedTextColor.YELLOW))
                        }
                    }
                    3 -> {
                        game.getAllPlayers().forEach { player ->
                            player.showTitle(net.kyori.adventure.title.Title.title(
                                Component.text("3", NamedTextColor.YELLOW),
                                Component.empty(),
                                net.kyori.adventure.title.Title.Times.times(
                                    java.time.Duration.ofMillis(0),
                                    java.time.Duration.ofMillis(1000),
                                    java.time.Duration.ofMillis(0)
                                )
                            ))
                            player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f)
                        }
                    }
                    2 -> {
                        game.getAllPlayers().forEach { player ->
                            player.showTitle(net.kyori.adventure.title.Title.title(
                                Component.text("2", NamedTextColor.GOLD),
                                Component.empty(),
                                net.kyori.adventure.title.Title.Times.times(
                                    java.time.Duration.ofMillis(0),
                                    java.time.Duration.ofMillis(1000),
                                    java.time.Duration.ofMillis(0)
                                )
                            ))
                            player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f)
                        }
                    }
                    1 -> {
                        game.getAllPlayers().forEach { player ->
                            player.showTitle(net.kyori.adventure.title.Title.title(
                                Component.text("1", NamedTextColor.RED),
                                Component.empty(),
                                net.kyori.adventure.title.Title.Times.times(
                                    java.time.Duration.ofMillis(0),
                                    java.time.Duration.ofMillis(1000),
                                    java.time.Duration.ofMillis(0)
                                )
                            ))
                            player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f)
                        }
                    }
                    0 -> {
                        game.getAllPlayers().forEach { player ->
                            player.showTitle(net.kyori.adventure.title.Title.title(
                                Component.text("START!", NamedTextColor.GREEN),
                                Component.empty(),
                                net.kyori.adventure.title.Title.Times.times(
                                    java.time.Duration.ofMillis(0),
                                    java.time.Duration.ofMillis(500),
                                    java.time.Duration.ofMillis(500)
                                )
                            ))
                            player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                        }
                        cancel()
                        // 次のゲームを開始
                        startNextMatchGame(gameName)
                    }
                    else -> {
                        // それ以外の場合は何もしない
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L) // 1秒ごとに実行
    }
    
    /**
     * 次のマッチゲームを開始
     */
    private fun startNextMatchGame(gameName: String) {
        plugin.logger.info("[GameManager] Starting next match game for $gameName")
        
        val game = games[gameName.lowercase()] ?: return
        val match = matches[gameName.lowercase()] ?: return
        
        plugin.logger.info("[GameManager] Current game state: ${game.state}, Match game number: ${match.currentGameNumber}")
        
        // 次のゲーム番号に進める
        match.nextGame()
        
        plugin.logger.info("[GameManager] Advanced to game ${match.currentGameNumber}")
        
        // ゲームの状態をリセット
        if (game.state != GameState.WAITING) {
            plugin.logger.info("[GameManager] Resetting game state from ${game.state} to WAITING")
            game.resetForNextMatchGame()
        }
        
        // ゲームを再開（プレイヤーは既に保持されている）
        if (!game.start()) {
            plugin.logger.warning("[GameManager] Failed to start next match game")
        } else {
            plugin.logger.info("[GameManager] Successfully started game ${match.currentGameNumber}")
        }
    }
    
    /**
     * マッチ完了処理
     */
    private fun handleMatchComplete(gameName: String) {
        val game = games[gameName.lowercase()] ?: return
        val match = matches[gameName.lowercase()] ?: return
        
        // ゲームを完全に停止
        game.stop()
        
        // マッチを削除
        matches.remove(gameName.lowercase())
        match.isActive = false
    }
    
    /**
     * マップ範囲の始点を設定
     */
    fun setMapPos1(gameName: String, location: Location) {
        val positions = mapPositions.getOrPut(gameName.lowercase()) { MapPositions() }
        positions.pos1 = location.clone()
    }
    
    /**
     * マップ範囲の終点を設定
     */
    fun setMapPos2(gameName: String, location: Location) {
        val positions = mapPositions.getOrPut(gameName.lowercase()) { MapPositions() }
        positions.pos2 = location.clone()
    }
    
    /**
     * 一時的なマップ範囲の始点を設定（プレイヤーごと）
     */
    fun setTempMapPos1(player: Player, location: Location) {
        val positions = tempMapPositions.getOrPut(player) { MapPositions() }
        positions.pos1 = location.clone()
    }
    
    /**
     * 一時的なマップ範囲の終点を設定（プレイヤーごと）
     */
    fun setTempMapPos2(player: Player, location: Location) {
        val positions = tempMapPositions.getOrPut(player) { MapPositions() }
        positions.pos2 = location.clone()
    }
    
    /**
     * プレイヤーの一時的なマップ範囲を取得
     */
    fun getTempMapPositions(player: Player): MapPositions? {
        return tempMapPositions[player]
    }
    
    /**
     * プレイヤーの一時的なマップ範囲を削除
     */
    fun clearTempMapPositions(player: Player) {
        tempMapPositions.remove(player)
    }
    
    /**
     * マップを保存し、自動的に旗とスポーンを検出
     */
    fun saveMap(gameName: String): MapSaveResult {
        val positions = mapPositions[gameName.lowercase()] 
            ?: return MapSaveResult(false, listOf(plugin.languageManager.getMessage("manager.map-range-not-set")))
        
        val pos1 = positions.pos1 
            ?: return MapSaveResult(false, listOf("始点（pos1）が設定されていません"))
        val pos2 = positions.pos2 
            ?: return MapSaveResult(false, listOf("終点（pos2）が設定されていません"))
        
        // ゲームが存在しない場合は新規作成モードとして処理（既存ゲームがある場合のみチェック）
        val isNewGame = !games.containsKey(gameName.lowercase())
        
        // ゲームが実行中の場合はエラー
        val game = getGame(gameName)
        if (game != null && game.state != GameState.WAITING) {
            return MapSaveResult(false, listOf(plugin.languageManager.getMessage("manager.cannot-save-running")))
        }
        
        // マップ領域を作成
        val region = MapRegion(pos1.world, pos1, pos2)
        
        // ブロックをスキャンして特定の位置を検出
        val scanResult = mapScanner.scan(region)
        
        if (!scanResult.isValid()) {
            return MapSaveResult(false, scanResult.errors)
        }
        
        // 新規ゲームの場合は設定を作成、既存ゲームの場合は更新
        if (isNewGame) {
            // 新規ゲーム作成
            val config = GameConfig(gameName, pos1.world).apply {
                redFlagLocation = scanResult.redFlags[0]
                blueFlagLocation = scanResult.blueFlags[0]
                redSpawnLocation = scanResult.redSpawns[0]
                blueSpawnLocation = scanResult.blueSpawns[0]
            }
            
            // 設定を保存
            configManager.saveConfig(config)
            
            // ゲームインスタンスを作成
            val newGame = Game(gameName, plugin, pos1.world)
            newGame.updateFromConfig(config)
            newGame.setMapBounds(pos1, pos2)  // マップ範囲を設定
            games[gameName.lowercase()] = newGame
        } else {
            // 既存ゲームの設定を更新
            val config = configManager.loadConfig(gameName)
            if (config != null) {
                config.redFlagLocation = scanResult.redFlags[0]
                config.blueFlagLocation = scanResult.blueFlags[0]
                config.redSpawnLocation = scanResult.redSpawns[0]
                config.blueSpawnLocation = scanResult.blueSpawns[0]
                configManager.saveConfig(config)
                
                // ゲームインスタンスも更新
                game?.updateFromConfig(config)
                game?.setMapBounds(pos1, pos2)  // マップ範囲を設定
            }
        }
        
        // 圧縮形式で保存
        if (!mapManager.saveMap(gameName, region)) {
            return MapSaveResult(false, listOf(plugin.languageManager.getMessage("manager.map-save-failed")))
        }
        
        // pos設定をクリア
        mapPositions.remove(gameName.lowercase())
        
        return MapSaveResult(
            success = true,
            redSpawn = formatLocation(scanResult.redSpawns[0]),
            blueSpawn = formatLocation(scanResult.blueSpawns[0]),
            redFlag = formatLocation(scanResult.redFlags[0]),
            blueFlag = formatLocation(scanResult.blueFlags[0])
        )
    }
    
    /**
     * ゲーム開始時にマップをリセット
     */
    fun resetGameMap(gameName: String, targetWorld: org.bukkit.World? = null): Boolean {
        return mapManager.loadAndRestoreMap(gameName, targetWorld)
    }
    
    private fun formatLocation(loc: Location): String {
        return "${loc.blockX}, ${loc.blockY}, ${loc.blockZ}"
    }
}