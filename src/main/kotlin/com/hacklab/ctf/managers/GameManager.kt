package com.hacklab.ctf.managers

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.MatchWrapper
import com.hacklab.ctf.config.ConfigManager
import com.hacklab.ctf.config.GameConfig
import com.hacklab.ctf.session.GameSetupSession
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.MatchMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
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
            player.sendMessage(Component.text("既に存在するゲーム名です", NamedTextColor.RED))
            return false
        }
        
        return setupSession.startCreateSession(player, gameName, player.world)
    }
    
    /**
     * ゲーム更新（対話形式）
     */
    fun startUpdateGame(player: Player, gameName: String): Boolean {
        val game = games[gameName.lowercase()] ?: run {
            player.sendMessage(Component.text("ゲームが見つかりません", NamedTextColor.RED))
            return false
        }
        
        if (game.state != GameState.WAITING) {
            player.sendMessage(Component.text("実行中のゲームは更新できません", NamedTextColor.RED))
            return false
        }
        
        val config = configManager.loadConfig(gameName) ?: run {
            player.sendMessage(Component.text("設定ファイルが見つかりません", NamedTextColor.RED))
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
    fun startGame(name: String, mode: MatchMode? = null, target: Int? = null): Boolean {
        val game = games[name.lowercase()] ?: return false
        val config = configManager.loadConfig(name) ?: return false
        
        if (!config.isValid()) {
            return false
        }
        
        // マッチモードが指定されていれば、マッチとして開始
        if (mode != null && target != null) {
            val matchWrapper = MatchWrapper(config.copy().apply {
                matchMode = mode
                matchTarget = target
            }, plugin)
            
            matches[name.lowercase()] = matchWrapper
            game.setMatchContext(matchWrapper)
            matchWrapper.isActive = true
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
}