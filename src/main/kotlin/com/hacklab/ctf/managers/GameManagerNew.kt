package com.hacklab.ctf.managers

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.Match
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import com.hacklab.ctf.utils.MatchMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.*

class GameManagerNew(private val plugin: Main) {
    
    // 複数ゲームの管理
    private val games = mutableMapOf<String, Game>()
    private val matches = mutableMapOf<String, Match>() // マッチ管理
    private val playerGames = mutableMapOf<UUID, String>() // プレイヤー -> ゲーム名
    
    // 対話形式作成・更新の状態管理
    private val creationSessions = mutableMapOf<UUID, GameCreationSession>()
    private val updateSessions = mutableMapOf<UUID, GameUpdateSession>()
    
    init {
        loadAllGames()
    }
    
    // ゲーム作成/更新セッション
    data class GameCreationSession(
        val gameName: String,
        val world: org.bukkit.World,
        var step: CreationStep = CreationStep.RED_FLAG,
        var redFlagLocation: Location? = null,
        var redSpawnLocation: Location? = null,
        var blueFlagLocation: Location? = null,
        var blueSpawnLocation: Location? = null,
        var buildPhaseGameMode: String = "ADVENTURE",
        var buildDuration: Int = 300,
        var combatDuration: Int = 600,
        var matchMode: MatchMode = MatchMode.FIRST_TO_X,
        var matchTarget: Int = 3,
        val startTime: Long = System.currentTimeMillis()
    )
    
    data class GameUpdateSession(
        val gameName: String,
        val game: Game,
        var currentMenu: UpdateMenu = UpdateMenu.MAIN,
        var waitingForInput: Boolean = false,
        val startTime: Long = System.currentTimeMillis()
    )
    
    enum class CreationStep {
        RED_FLAG,
        RED_SPAWN,
        BLUE_FLAG,
        BLUE_SPAWN,
        BUILD_GAMEMODE,
        BUILD_DURATION,
        COMBAT_DURATION,
        MATCH_MODE,
        MATCH_TARGET,
        COMPLETE
    }
    
    enum class UpdateMenu {
        MAIN,
        RED_FLAG,
        RED_SPAWN,
        BLUE_FLAG,
        BLUE_SPAWN,
        BUILD_GAMEMODE,
        BUILD_DURATION,
        COMBAT_DURATION,
        MATCH_MODE,
        MATCH_TARGET
    }
    
    // ゲーム管理メソッド
    fun createGame(name: String, world: org.bukkit.World): Boolean {
        if (games.containsKey(name.lowercase())) {
            return false
        }
        
        val game = Game(name, plugin, world)
        games[name.lowercase()] = game
        saveGame(name)
        return true
    }
    
    fun deleteGame(name: String): Boolean {
        val gameName = name.lowercase()
        val game = games[gameName] ?: return false
        
        // 実行中のゲームは停止
        if (game.state != GameState.WAITING) {
            game.stop()
        }
        
        // プレイヤーを全員退出させる
        game.getAllPlayers().forEach { player ->
            removePlayerFromGame(player)
        }
        
        // ゲームを削除
        games.remove(gameName)
        
        // YAMLファイルを削除
        val file = File(plugin.dataFolder, "games/$gameName.yml")
        if (file.exists()) {
            file.delete()
        }
        
        return true
    }
    
    fun getGame(name: String): Game? {
        return games[name.lowercase()]
    }
    
    fun getMatch(name: String): Match? {
        return matches[name.lowercase()]
    }
    
    fun getAllGames(): Map<String, Game> {
        return games.toMap()
    }
    
    fun getPlayerGame(player: Player): Game? {
        val gameName = playerGames[player.uniqueId] ?: return null
        return games[gameName]
    }
    
    fun addPlayerToGame(player: Player, gameName: String, forceJoin: Boolean = false): Boolean {
        val game = games[gameName.lowercase()] ?: return false
        
        // 既に他のゲームに参加している場合
        val currentGame = getPlayerGame(player)
        if (currentGame != null && !forceJoin) {
            // 確認ダイアログの処理は呼び出し側で行う
            return false
        }
        
        if (currentGame != null) {
            removePlayerFromGame(player)
        }
        
        if (game.addPlayer(player)) {
            playerGames[player.uniqueId] = gameName.lowercase()
            return true
        }
        
        return false
    }
    
    fun removePlayerFromGame(player: Player) {
        val game = getPlayerGame(player) ?: return
        game.removePlayer(player)
        playerGames.remove(player.uniqueId)
    }
    
    // プレイヤー切断・再接続処理
    fun handlePlayerDisconnect(player: Player) {
        val game = getPlayerGame(player) ?: return
        game.handleDisconnect(player)
        
        // 対話セッションをクリア
        creationSessions.remove(player.uniqueId)
        updateSessions.remove(player.uniqueId)
    }
    
    fun handlePlayerReconnect(player: Player) {
        val gameName = playerGames[player.uniqueId] ?: return
        val game = games[gameName] ?: return
        game.handleReconnect(player)
    }
    
    // 対話形式のゲーム作成
    fun startGameCreation(player: Player, gameName: String): Boolean {
        // ゲーム名の検証
        if (!validateGameName(gameName)) {
            player.sendMessage(Component.text("無効なゲーム名です。英数字とアンダースコアのみ使用可能です。", NamedTextColor.RED))
            return false
        }
        
        if (games.containsKey(gameName.lowercase())) {
            player.sendMessage(Component.text("そのゲーム名は既に使用されています。", NamedTextColor.RED))
            return false
        }
        
        val session = GameCreationSession(gameName, player.world)
        creationSessions[player.uniqueId] = session
        
        // タイムアウトタスク
        startSessionTimeout(player, true)
        
        showCreationStep(player, session)
        return true
    }
    
    fun handleCreationInput(player: Player, message: String) {
        val session = creationSessions[player.uniqueId] ?: return
        
        when (message.lowercase()) {
            "cancel" -> {
                cancelCreation(player)
                return
            }
            "skip" -> {
                skipCreationStep(player, session)
                return
            }
        }
        
        when (session.step) {
            CreationStep.RED_FLAG, CreationStep.RED_SPAWN,
            CreationStep.BLUE_FLAG, CreationStep.BLUE_SPAWN -> {
                if (message.lowercase() == "set") {
                    setLocationFromView(player, session)
                }
            }
            CreationStep.BUILD_GAMEMODE -> {
                handleGameModeInput(player, session, message)
            }
            CreationStep.BUILD_DURATION, CreationStep.COMBAT_DURATION -> {
                handleDurationInput(player, session, message)
            }
            CreationStep.MATCH_MODE -> {
                handleMatchModeInput(player, session, message)
            }
            CreationStep.MATCH_TARGET -> {
                handleMatchTargetInput(player, session, message)
            }
            CreationStep.COMPLETE -> {}
        }
    }
    
    private fun showCreationStep(player: Player, session: GameCreationSession) {
        when (session.step) {
            CreationStep.RED_FLAG -> {
                player.sendMessage(Component.text("=== ゲーム作成: ${session.gameName} ===", NamedTextColor.GOLD))
                player.sendMessage(Component.text("赤チームの旗を設置する場所を見て、チャットで 'set' と入力してください", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' でスキップ、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.RED_SPAWN -> {
                player.sendMessage(Component.text("赤チームのスポーン地点を見て、チャットで 'set' と入力してください", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' でスキップ（旗位置にスポーン）、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.BLUE_FLAG -> {
                player.sendMessage(Component.text("青チームの旗を設置する場所を見て、チャットで 'set' と入力してください", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' でスキップ、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.BLUE_SPAWN -> {
                player.sendMessage(Component.text("青チームのスポーン地点を見て、チャットで 'set' と入力してください", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' でスキップ（旗位置にスポーン）、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.BUILD_GAMEMODE -> {
                player.sendMessage(Component.text("建築フェーズのゲームモードを選択してください", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("ADVENTURE, SURVIVAL, CREATIVE", NamedTextColor.WHITE))
                player.sendMessage(Component.text("'skip' でデフォルト（ADVENTURE）、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.BUILD_DURATION -> {
                player.sendMessage(Component.text("建築フェーズの時間を秒単位で入力してください（例: 300）", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' でデフォルト（300秒）、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.COMBAT_DURATION -> {
                player.sendMessage(Component.text("戦闘フェーズの時間を秒単位で入力してください（例: 600）", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' でデフォルト（600秒）、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.MATCH_MODE -> {
                player.sendMessage(Component.text("マッチモードを選択してください", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("first_to_x: 先取モード（指定勝利数に先に到達）", NamedTextColor.WHITE))
                player.sendMessage(Component.text("fixed_rounds: 固定回数モード（指定回数実施して合計で勝敗決定）", NamedTextColor.WHITE))
                player.sendMessage(Component.text("'skip' でデフォルト（first_to_x）、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.MATCH_TARGET -> {
                val modeText = if (session.matchMode == MatchMode.FIRST_TO_X) "必要勝利数" else "ゲーム数"
                player.sendMessage(Component.text("${modeText}を入力してください（例: 3）", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' でデフォルト（3）、'cancel' でキャンセル", NamedTextColor.GRAY))
            }
            CreationStep.COMPLETE -> {
                completeCreation(player, session)
            }
        }
    }
    
    private fun setLocationFromView(player: Player, session: GameCreationSession) {
        val targetBlock = player.getTargetBlock(null, 100)
        if (targetBlock == null || targetBlock.type == Material.AIR) {
            player.sendMessage(Component.text("視線の先にブロックが見つかりません。", NamedTextColor.RED))
            return
        }
        
        val location = targetBlock.location.add(0.5, 1.0, 0.5)
        location.yaw = player.location.yaw
        location.pitch = 0f
        
        when (session.step) {
            CreationStep.RED_FLAG -> {
                session.redFlagLocation = location
                player.sendMessage(Component.text("赤チームの旗位置を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
                session.step = CreationStep.RED_SPAWN
            }
            CreationStep.RED_SPAWN -> {
                session.redSpawnLocation = location
                player.sendMessage(Component.text("赤チームのスポーン地点を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
                session.step = CreationStep.BLUE_FLAG
            }
            CreationStep.BLUE_FLAG -> {
                session.blueFlagLocation = location
                player.sendMessage(Component.text("青チームの旗位置を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
                session.step = CreationStep.BLUE_SPAWN
            }
            CreationStep.BLUE_SPAWN -> {
                session.blueSpawnLocation = location
                player.sendMessage(Component.text("青チームのスポーン地点を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
                session.step = CreationStep.BUILD_GAMEMODE
            }
            else -> return
        }
        
        showCreationStep(player, session)
    }
    
    private fun skipCreationStep(player: Player, session: GameCreationSession) {
        when (session.step) {
            CreationStep.RED_FLAG, CreationStep.BLUE_FLAG -> {
                player.sendMessage(Component.text("旗位置は必須です。スキップできません。", NamedTextColor.RED))
            }
            CreationStep.RED_SPAWN -> {
                player.sendMessage(Component.text("赤チームのスポーン地点をスキップしました（旗位置にスポーン）", NamedTextColor.YELLOW))
                session.step = CreationStep.BLUE_FLAG
                showCreationStep(player, session)
            }
            CreationStep.BLUE_SPAWN -> {
                player.sendMessage(Component.text("青チームのスポーン地点をスキップしました（旗位置にスポーン）", NamedTextColor.YELLOW))
                session.step = CreationStep.BUILD_GAMEMODE
                showCreationStep(player, session)
            }
            CreationStep.BUILD_GAMEMODE -> {
                player.sendMessage(Component.text("ゲームモードをデフォルト（ADVENTURE）に設定しました", NamedTextColor.YELLOW))
                session.step = CreationStep.BUILD_DURATION
                showCreationStep(player, session)
            }
            CreationStep.BUILD_DURATION -> {
                player.sendMessage(Component.text("建築フェーズ時間をデフォルト（300秒）に設定しました", NamedTextColor.YELLOW))
                session.step = CreationStep.COMBAT_DURATION
                showCreationStep(player, session)
            }
            CreationStep.COMBAT_DURATION -> {
                player.sendMessage(Component.text("戦闘フェーズ時間をデフォルト（600秒）に設定しました", NamedTextColor.YELLOW))
                session.step = CreationStep.MATCH_MODE
                showCreationStep(player, session)
            }
            CreationStep.MATCH_MODE -> {
                player.sendMessage(Component.text("マッチモードをデフォルト（first_to_x）に設定しました", NamedTextColor.YELLOW))
                session.step = CreationStep.MATCH_TARGET
                showCreationStep(player, session)
            }
            CreationStep.MATCH_TARGET -> {
                player.sendMessage(Component.text("マッチターゲットをデフォルト（3）に設定しました", NamedTextColor.YELLOW))
                session.step = CreationStep.COMPLETE
                showCreationStep(player, session)
            }
            CreationStep.COMPLETE -> {}
        }
    }
    
    private fun handleGameModeInput(player: Player, session: GameCreationSession, input: String) {
        val gameMode = when (input.uppercase()) {
            "ADVENTURE" -> GameMode.ADVENTURE
            "SURVIVAL" -> GameMode.SURVIVAL
            "CREATIVE" -> GameMode.CREATIVE
            else -> {
                player.sendMessage(Component.text("無効なゲームモードです。ADVENTURE, SURVIVAL, CREATIVE から選択してください。", NamedTextColor.RED))
                return
            }
        }
        
        session.buildPhaseGameMode = gameMode.name
        player.sendMessage(Component.text("建築フェーズのゲームモードを ${gameMode.name} に設定しました", NamedTextColor.GREEN))
        session.step = CreationStep.BUILD_DURATION
        showCreationStep(player, session)
    }
    
    private fun handleDurationInput(player: Player, session: GameCreationSession, input: String) {
        val duration = input.toIntOrNull()
        if (duration == null || duration < 10 || duration > 3600) {
            player.sendMessage(Component.text("無効な時間です。10〜3600の間で入力してください。", NamedTextColor.RED))
            return
        }
        
        when (session.step) {
            CreationStep.BUILD_DURATION -> {
                session.buildDuration = duration
                player.sendMessage(Component.text("建築フェーズの時間を ${duration}秒 に設定しました", NamedTextColor.GREEN))
                session.step = CreationStep.COMBAT_DURATION
            }
            CreationStep.COMBAT_DURATION -> {
                session.combatDuration = duration
                player.sendMessage(Component.text("戦闘フェーズの時間を ${duration}秒 に設定しました", NamedTextColor.GREEN))
                session.step = CreationStep.MATCH_MODE
            }
            else -> return
        }
        
        showCreationStep(player, session)
    }
    
    private fun handleMatchModeInput(player: Player, session: GameCreationSession, input: String) {
        val mode = MatchMode.fromString(input)
        if (mode == null) {
            player.sendMessage(Component.text("無効なモードです。first_to_x または fixed_rounds を入力してください。", NamedTextColor.RED))
            return
        }
        
        session.matchMode = mode
        player.sendMessage(Component.text("マッチモードを ${mode.displayName} に設定しました", NamedTextColor.GREEN))
        session.step = CreationStep.MATCH_TARGET
        showCreationStep(player, session)
    }
    
    private fun handleMatchTargetInput(player: Player, session: GameCreationSession, input: String) {
        val target = input.toIntOrNull()
        if (target == null || target < 1 || target > 10) {
            player.sendMessage(Component.text("無効な値です。1〜10の間で入力してください。", NamedTextColor.RED))
            return
        }
        
        session.matchTarget = target
        val targetText = if (session.matchMode == MatchMode.FIRST_TO_X) "${target}勝先取" else "${target}ゲーム"
        player.sendMessage(Component.text("マッチ設定を $targetText に設定しました", NamedTextColor.GREEN))
        session.step = CreationStep.COMPLETE
        showCreationStep(player, session)
    }
    
    private fun completeCreation(player: Player, session: GameCreationSession) {
        // 必須項目チェック
        if (session.redFlagLocation == null || session.blueFlagLocation == null) {
            player.sendMessage(Component.text("両チームの旗位置が必要です。", NamedTextColor.RED))
            cancelCreation(player)
            return
        }
        
        // ゲーム作成
        val game = Game(session.gameName, plugin, session.world)
        game.setRedFlagLocation(session.redFlagLocation!!)
        session.redSpawnLocation?.let { game.setRedSpawnLocation(it) }
        game.setBlueFlagLocation(session.blueFlagLocation!!)
        session.blueSpawnLocation?.let { game.setBlueSpawnLocation(it) }
        game.buildPhaseGameMode = session.buildPhaseGameMode
        game.buildDuration = session.buildDuration
        game.combatDuration = session.combatDuration
        
        // マッチ作成
        val match = Match(
            name = session.gameName,
            plugin = plugin,
            mode = session.matchMode,
            target = session.matchTarget,
            intervalDuration = plugin.config.getInt("match.interval-duration", 30)
        )
        match.buildDuration = session.buildDuration
        match.combatDuration = session.combatDuration
        match.buildPhaseGameMode = session.buildPhaseGameMode
        
        games[session.gameName.lowercase()] = game
        matches[session.gameName.lowercase()] = match
        saveGame(session.gameName)
        
        player.sendMessage(Component.text("ゲーム '${session.gameName}' を作成しました！", NamedTextColor.GREEN))
        player.sendMessage(Component.text("/ctf start ${session.gameName} でゲームを開始できます", NamedTextColor.YELLOW))
        
        creationSessions.remove(player.uniqueId)
    }
    
    private fun cancelCreation(player: Player) {
        creationSessions.remove(player.uniqueId)
        player.sendMessage(Component.text("ゲーム作成をキャンセルしました。", NamedTextColor.YELLOW))
    }
    
    // 対話形式のゲーム更新
    fun startGameUpdate(player: Player, gameName: String): Boolean {
        val game = games[gameName.lowercase()] ?: return false
        
        if (game.state != GameState.WAITING) {
            player.sendMessage(Component.text("実行中のゲームは更新できません。", NamedTextColor.RED))
            return false
        }
        
        val session = GameUpdateSession(gameName, game)
        updateSessions[player.uniqueId] = session
        
        // タイムアウトタスク
        startSessionTimeout(player, false)
        
        showUpdateMenu(player, session)
        return true
    }
    
    fun handleUpdateInput(player: Player, message: String) {
        val session = updateSessions[player.uniqueId] ?: return
        
        if (message.lowercase() == "cancel") {
            cancelUpdate(player)
            return
        }
        
        if (session.currentMenu == UpdateMenu.MAIN) {
            handleUpdateMenuSelection(player, session, message)
        } else if (session.waitingForInput) {
            handleUpdateValueInput(player, session, message)
        }
    }
    
    private fun showUpdateMenu(player: Player, session: GameUpdateSession) {
        player.sendMessage(Component.text("=== ゲーム更新: ${session.gameName} ===", NamedTextColor.GOLD))
        player.sendMessage(Component.text("更新する項目を選択してください：", NamedTextColor.YELLOW))
        player.sendMessage(Component.text("1. 赤チーム旗位置を更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("2. 赤チームスポーン地点を更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("3. 青チーム旗位置を更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("4. 青チームスポーン地点を更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("5. 建築フェーズゲームモードを更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("6. 建築フェーズ時間を更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("7. 戦闘フェーズ時間を更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("8. すべて更新（create と同じ手順）", NamedTextColor.WHITE))
        player.sendMessage(Component.text("9. 終了", NamedTextColor.WHITE))
        player.sendMessage(Component.text("番号を入力してください（'cancel' でキャンセル）", NamedTextColor.GRAY))
    }
    
    private fun handleUpdateMenuSelection(player: Player, session: GameUpdateSession, input: String) {
        when (input) {
            "1" -> {
                session.currentMenu = UpdateMenu.RED_FLAG
                session.waitingForInput = true
                player.sendMessage(Component.text("赤チームの旗を設置する場所を見て、チャットで 'set' と入力してください", NamedTextColor.YELLOW))
            }
            "2" -> {
                session.currentMenu = UpdateMenu.RED_SPAWN
                session.waitingForInput = true
                player.sendMessage(Component.text("赤チームのスポーン地点を見て、チャットで 'set' と入力してください", NamedTextColor.YELLOW))
            }
            "3" -> {
                session.currentMenu = UpdateMenu.BLUE_FLAG
                session.waitingForInput = true
                player.sendMessage(Component.text("青チームの旗を設置する場所を見て、チャットで 'set' と入力してください", NamedTextColor.YELLOW))
            }
            "4" -> {
                session.currentMenu = UpdateMenu.BLUE_SPAWN
                session.waitingForInput = true
                player.sendMessage(Component.text("青チームのスポーン地点を見て、チャットで 'set' と入力してください", NamedTextColor.YELLOW))
            }
            "5" -> {
                session.currentMenu = UpdateMenu.BUILD_GAMEMODE
                session.waitingForInput = true
                player.sendMessage(Component.text("建築フェーズのゲームモードを選択してください (ADVENTURE/SURVIVAL/CREATIVE)", NamedTextColor.YELLOW))
            }
            "6" -> {
                session.currentMenu = UpdateMenu.BUILD_DURATION
                session.waitingForInput = true
                player.sendMessage(Component.text("建築フェーズの時間を秒単位で入力してください（例: 300）", NamedTextColor.YELLOW))
            }
            "7" -> {
                session.currentMenu = UpdateMenu.COMBAT_DURATION
                session.waitingForInput = true
                player.sendMessage(Component.text("戦闘フェーズの時間を秒単位で入力してください（例: 600）", NamedTextColor.YELLOW))
            }
            "8" -> {
                // 全更新は作成と同じ流れにリダイレクト
                updateSessions.remove(player.uniqueId)
                val creationSession = GameCreationSession(session.gameName, player.world)
                creationSessions[player.uniqueId] = creationSession
                showCreationStep(player, creationSession)
            }
            "9", "exit" -> {
                cancelUpdate(player)
            }
            else -> {
                player.sendMessage(Component.text("無効な選択です。1-9の番号を入力してください。", NamedTextColor.RED))
            }
        }
    }
    
    private fun handleUpdateValueInput(player: Player, session: GameUpdateSession, input: String) {
        when (session.currentMenu) {
            UpdateMenu.RED_FLAG, UpdateMenu.RED_SPAWN,
            UpdateMenu.BLUE_FLAG, UpdateMenu.BLUE_SPAWN -> {
                if (input.lowercase() == "set") {
                    updateLocationFromView(player, session)
                }
            }
            UpdateMenu.BUILD_GAMEMODE -> {
                updateGameMode(player, session, input)
            }
            UpdateMenu.BUILD_DURATION, UpdateMenu.COMBAT_DURATION -> {
                updateDuration(player, session, input)
            }
            else -> {}
        }
    }
    
    private fun updateLocationFromView(player: Player, session: GameUpdateSession) {
        val targetBlock = player.getTargetBlock(null, 100)
        if (targetBlock == null || targetBlock.type == Material.AIR) {
            player.sendMessage(Component.text("視線の先にブロックが見つかりません。", NamedTextColor.RED))
            return
        }
        
        val location = targetBlock.location.add(0.5, 1.0, 0.5)
        location.yaw = player.location.yaw
        location.pitch = 0f
        
        when (session.currentMenu) {
            UpdateMenu.RED_FLAG -> {
                session.game.setRedFlagLocation(location)
                player.sendMessage(Component.text("赤チームの旗位置を更新しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            UpdateMenu.RED_SPAWN -> {
                session.game.setRedSpawnLocation(location)
                player.sendMessage(Component.text("赤チームのスポーン地点を更新しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            UpdateMenu.BLUE_FLAG -> {
                session.game.setBlueFlagLocation(location)
                player.sendMessage(Component.text("青チームの旗位置を更新しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            UpdateMenu.BLUE_SPAWN -> {
                session.game.setBlueSpawnLocation(location)
                player.sendMessage(Component.text("青チームのスポーン地点を更新しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            else -> return
        }
        
        saveGame(session.gameName)
        session.waitingForInput = false
        session.currentMenu = UpdateMenu.MAIN
        showUpdateMenu(player, session)
    }
    
    private fun updateGameMode(player: Player, session: GameUpdateSession, input: String) {
        val gameMode = when (input.uppercase()) {
            "ADVENTURE" -> GameMode.ADVENTURE
            "SURVIVAL" -> GameMode.SURVIVAL
            "CREATIVE" -> GameMode.CREATIVE
            else -> {
                player.sendMessage(Component.text("無効なゲームモードです。ADVENTURE, SURVIVAL, CREATIVE から選択してください。", NamedTextColor.RED))
                return
            }
        }
        
        session.game.buildPhaseGameMode = gameMode.name
        player.sendMessage(Component.text("建築フェーズのゲームモードを ${gameMode.name} に更新しました", NamedTextColor.GREEN))
        
        saveGame(session.gameName)
        session.waitingForInput = false
        session.currentMenu = UpdateMenu.MAIN
        showUpdateMenu(player, session)
    }
    
    private fun updateDuration(player: Player, session: GameUpdateSession, input: String) {
        val duration = input.toIntOrNull()
        if (duration == null || duration < 10 || duration > 3600) {
            player.sendMessage(Component.text("無効な時間です。10〜3600の間で入力してください。", NamedTextColor.RED))
            return
        }
        
        when (session.currentMenu) {
            UpdateMenu.BUILD_DURATION -> {
                session.game.buildDuration = duration
                player.sendMessage(Component.text("建築フェーズの時間を ${duration}秒 に更新しました", NamedTextColor.GREEN))
            }
            UpdateMenu.COMBAT_DURATION -> {
                session.game.combatDuration = duration
                player.sendMessage(Component.text("戦闘フェーズの時間を ${duration}秒 に更新しました", NamedTextColor.GREEN))
            }
            else -> return
        }
        
        saveGame(session.gameName)
        session.waitingForInput = false
        session.currentMenu = UpdateMenu.MAIN
        showUpdateMenu(player, session)
    }
    
    private fun cancelUpdate(player: Player) {
        updateSessions.remove(player.uniqueId)
        player.sendMessage(Component.text("ゲーム更新を終了しました。", NamedTextColor.YELLOW))
    }
    
    // タイムアウト処理
    private fun startSessionTimeout(player: Player, isCreation: Boolean) {
        object : BukkitRunnable() {
            override fun run() {
                if (isCreation) {
                    if (creationSessions.containsKey(player.uniqueId)) {
                        val session = creationSessions[player.uniqueId]!!
                        if (System.currentTimeMillis() - session.startTime > 60000) {
                            player.sendMessage(Component.text("操作がタイムアウトしました。", NamedTextColor.RED))
                            cancelCreation(player)
                        }
                    }
                } else {
                    if (updateSessions.containsKey(player.uniqueId)) {
                        val session = updateSessions[player.uniqueId]!!
                        if (System.currentTimeMillis() - session.startTime > 60000) {
                            player.sendMessage(Component.text("操作がタイムアウトしました。", NamedTextColor.RED))
                            cancelUpdate(player)
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 1200L) // 60秒後
    }
    
    // ゲーム保存・読み込み
    private fun saveGame(name: String) {
        val game = games[name.lowercase()] ?: return
        
        val gamesDir = File(plugin.dataFolder, "games")
        if (!gamesDir.exists()) {
            gamesDir.mkdirs()
        }
        
        val file = File(gamesDir, "${name.lowercase()}.yml")
        val config = YamlConfiguration()
        
        // 基本情報
        config.set("name", game.name)
        config.set("created", System.currentTimeMillis())
        config.set("world", game.world.name)
        
        // チーム設定
        game.getRedFlagLocation()?.let {
            config.set("teams.red.flag-location.x", it.x)
            config.set("teams.red.flag-location.y", it.y)
            config.set("teams.red.flag-location.z", it.z)
        }
        game.getRedSpawnLocation()?.let {
            config.set("teams.red.spawn-location.x", it.x)
            config.set("teams.red.spawn-location.y", it.y)
            config.set("teams.red.spawn-location.z", it.z)
        }
        game.getBlueFlagLocation()?.let {
            config.set("teams.blue.flag-location.x", it.x)
            config.set("teams.blue.flag-location.y", it.y)
            config.set("teams.blue.flag-location.z", it.z)
        }
        game.getBlueSpawnLocation()?.let {
            config.set("teams.blue.spawn-location.x", it.x)
            config.set("teams.blue.spawn-location.y", it.y)
            config.set("teams.blue.spawn-location.z", it.z)
        }
        
        // ゲーム設定
        config.set("settings.min-players", game.minPlayers)
        config.set("settings.max-players-per-team", game.maxPlayersPerTeam)
        config.set("settings.respawn-delay", game.respawnDelay)
        config.set("settings.phases.build-duration", game.buildDuration)
        config.set("settings.phases.build-gamemode", game.buildPhaseGameMode)
        config.set("settings.phases.combat-duration", game.combatDuration)
        
        // マッチ設定
        val match = matches[name.lowercase()]
        if (match != null) {
            config.set("settings.match.mode", match.mode.name)
            config.set("settings.match.target", match.target)
            config.set("settings.match.interval-duration", plugin.config.getInt("match.interval-duration", 30))
        }
        
        config.save(file)
    }
    
    private fun loadAllGames() {
        val gamesDir = File(plugin.dataFolder, "games")
        if (!gamesDir.exists()) {
            return
        }
        
        gamesDir.listFiles { file -> file.extension == "yml" }?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val name = config.getString("name") ?: return@forEach
                val worldName = config.getString("world") ?: return@forEach
                val world = plugin.server.getWorld(worldName) ?: return@forEach
                
                val game = Game(name, plugin, world)
                
                // 位置情報の読み込み
                if (config.contains("teams.red.flag-location")) {
                    game.setRedFlagLocation(Location(
                        world,
                        config.getDouble("teams.red.flag-location.x"),
                        config.getDouble("teams.red.flag-location.y"),
                        config.getDouble("teams.red.flag-location.z")
                    ))
                }
                if (config.contains("teams.red.spawn-location")) {
                    game.setRedSpawnLocation(Location(
                        world,
                        config.getDouble("teams.red.spawn-location.x"),
                        config.getDouble("teams.red.spawn-location.y"),
                        config.getDouble("teams.red.spawn-location.z")
                    ))
                }
                if (config.contains("teams.blue.flag-location")) {
                    game.setBlueFlagLocation(Location(
                        world,
                        config.getDouble("teams.blue.flag-location.x"),
                        config.getDouble("teams.blue.flag-location.y"),
                        config.getDouble("teams.blue.flag-location.z")
                    ))
                }
                if (config.contains("teams.blue.spawn-location")) {
                    game.setBlueSpawnLocation(Location(
                        world,
                        config.getDouble("teams.blue.spawn-location.x"),
                        config.getDouble("teams.blue.spawn-location.y"),
                        config.getDouble("teams.blue.spawn-location.z")
                    ))
                }
                
                // ゲーム設定の読み込み
                game.minPlayers = config.getInt("settings.min-players", game.minPlayers)
                game.maxPlayersPerTeam = config.getInt("settings.max-players-per-team", game.maxPlayersPerTeam)
                game.respawnDelay = config.getInt("settings.respawn-delay", game.respawnDelay)
                game.buildDuration = config.getInt("settings.phases.build-duration", game.buildDuration)
                config.getString("settings.phases.build-gamemode")?.let {
                    game.buildPhaseGameMode = it
                }
                game.combatDuration = config.getInt("settings.phases.combat-duration", game.combatDuration)
                
                // マッチ設定の読み込み
                if (config.contains("settings.match.mode")) {
                    val matchMode = MatchMode.fromString(config.getString("settings.match.mode", "first_to_x")!!) ?: MatchMode.FIRST_TO_X
                    val matchTarget = config.getInt("settings.match.target", 3)
                    val intervalDuration = config.getInt("settings.match.interval-duration", 30)
                    
                    val match = Match(
                        name = name,
                        plugin = plugin,
                        mode = matchMode,
                        target = matchTarget,
                        intervalDuration = intervalDuration
                    )
                    match.buildDuration = game.buildDuration
                    match.combatDuration = game.combatDuration
                    match.buildPhaseGameMode = game.buildPhaseGameMode
                    
                    matches[name.lowercase()] = match
                }
                
                games[name.lowercase()] = game
                plugin.logger.info("Loaded game: $name")
                
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load game from ${file.name}: ${e.message}")
            }
        }
    }
    
    private fun validateGameName(name: String): Boolean {
        if (name.length > 32) return false
        if (name.matches(Regex("[a-zA-Z0-9_]+"))) {
            val reserved = listOf("all", "list", "help")
            return !reserved.contains(name.lowercase())
        }
        return false
    }
    
    // ChatListener用のヘルパーメソッド
    fun isInCreationSession(player: Player): Boolean {
        return creationSessions.containsKey(player.uniqueId)
    }
    
    fun isInUpdateSession(player: Player): Boolean {
        return updateSessions.containsKey(player.uniqueId)
    }
}