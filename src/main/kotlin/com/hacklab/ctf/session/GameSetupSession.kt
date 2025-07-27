package com.hacklab.ctf.session

import com.hacklab.ctf.Main
import com.hacklab.ctf.config.GameConfig
import com.hacklab.ctf.utils.MatchMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ゲームの対話形式作成・更新を管理
 * GameManagerから分離してシンプルに
 */
class GameSetupSession(private val plugin: Main) {
    
    private val activeSessions = ConcurrentHashMap<UUID, Session>()
    
    // セッションの基底クラス
    sealed class Session(
        val player: Player,
        val startTime: Long = System.currentTimeMillis()
    ) {
        abstract fun handleInput(input: String)
        abstract fun showCurrentStep()
    }
    
    // 作成セッション
    class CreateSession(
        player: Player,
        val config: GameConfig,
        var step: Step = Step.RED_FLAG
    ) : Session(player) {
        
        enum class Step {
            RED_FLAG, RED_SPAWN, BLUE_FLAG, BLUE_SPAWN,
            BUILD_MODE, BUILD_TIME, COMBAT_TIME,
            MATCH_MODE, MATCH_TARGET, COMPLETE
        }
        
        override fun handleInput(input: String) {
            // セッション管理クラスで処理
        }
        
        override fun showCurrentStep() {
            // セッション管理クラスで処理
        }
    }
    
    // 更新セッション
    class UpdateSession(
        player: Player,
        val config: GameConfig,
        var menu: Menu = Menu.MAIN,
        var waitingForInput: Boolean = false
    ) : Session(player) {
        
        enum class Menu {
            MAIN, RED_FLAG, RED_SPAWN, BLUE_FLAG, BLUE_SPAWN,
            BUILD_MODE, BUILD_TIME, COMBAT_TIME
        }
        
        override fun handleInput(input: String) {
            // セッション管理クラスで処理
        }
        
        override fun showCurrentStep() {
            // セッション管理クラスで処理
        }
    }
    
    // マップ自動確認セッション
    class MapAutoConfirmSession(
        player: Player,
        val gameName: String,
        val callback: (Boolean) -> Unit,
        val onComplete: (Player) -> Unit
    ) : Session(player) {
        
        override fun handleInput(input: String) {
            when (input.lowercase()) {
                "y", "yes", "はい" -> {
                    callback(true)
                    onComplete(player)
                }
                "n", "no", "いいえ" -> {
                    callback(false)
                    onComplete(player)
                }
                else -> {
                    player.sendMessage(Component.text("Y または n で回答してください", NamedTextColor.YELLOW))
                    return
                }
            }
        }
        
        override fun showCurrentStep() {
            player.sendMessage(Component.text("マップ領域が設定されています。自動検出でゲームを作成しますか？", NamedTextColor.YELLOW))
            player.sendMessage(Component.text("[Y/n] Yで自動作成、nで対話形式", NamedTextColor.GRAY))
        }
    }
    
    /**
     * 作成セッション開始
     */
    fun startCreateSession(player: Player, gameName: String, world: org.bukkit.World): Boolean {
        if (!validateGameName(gameName)) {
            player.sendMessage(Component.text("無効なゲーム名です。英数字とアンダースコアのみ使用可能です。", NamedTextColor.RED))
            return false
        }
        
        val config = GameConfig(gameName, world)
        val session = CreateSession(player, config)
        activeSessions[player.uniqueId] = session
        
        startTimeout(player.uniqueId)
        showCreateStep(session)
        return true
    }
    
    /**
     * 更新セッション開始
     */
    fun startUpdateSession(player: Player, config: GameConfig): Boolean {
        val session = UpdateSession(player, config.copy())
        activeSessions[player.uniqueId] = session
        
        startTimeout(player.uniqueId)
        showUpdateMenu(session)
        return true
    }
    
    /**
     * 入力処理
     */
    fun handleInput(player: Player, message: String) {
        val session = activeSessions[player.uniqueId] ?: return
        
        when (message.lowercase()) {
            "cancel" -> {
                cancelSession(player)
                return
            }
        }
        
        when (session) {
            is CreateSession -> handleCreateInput(session, message)
            is UpdateSession -> handleUpdateInput(session, message)
            is MapAutoConfirmSession -> session.handleInput(message)
        }
    }
    
    private fun handleCreateInput(session: CreateSession, message: String) {
        when (message.lowercase()) {
            "skip" -> skipCreateStep(session)
            else -> {
                when (session.step) {
                    CreateSession.Step.RED_FLAG,
                    CreateSession.Step.RED_SPAWN,
                    CreateSession.Step.BLUE_FLAG,
                    CreateSession.Step.BLUE_SPAWN -> {
                        if (message.lowercase() == "set") {
                            setLocationFromView(session)
                        }
                    }
                    CreateSession.Step.BUILD_MODE -> setBuildMode(session, message)
                    CreateSession.Step.BUILD_TIME -> setBuildTime(session, message)
                    CreateSession.Step.COMBAT_TIME -> setCombatTime(session, message)
                    CreateSession.Step.MATCH_MODE -> setMatchMode(session, message)
                    CreateSession.Step.MATCH_TARGET -> setMatchTarget(session, message)
                    CreateSession.Step.COMPLETE -> {}
                }
            }
        }
    }
    
    private fun handleUpdateInput(session: UpdateSession, message: String) {
        if (!session.waitingForInput) {
            // メニュー選択
            when (message) {
                "1" -> startUpdateLocation(session, UpdateSession.Menu.RED_FLAG)
                "2" -> startUpdateLocation(session, UpdateSession.Menu.RED_SPAWN)
                "3" -> startUpdateLocation(session, UpdateSession.Menu.BLUE_FLAG)
                "4" -> startUpdateLocation(session, UpdateSession.Menu.BLUE_SPAWN)
                "5" -> startUpdateValue(session, UpdateSession.Menu.BUILD_MODE)
                "6" -> startUpdateValue(session, UpdateSession.Menu.BUILD_TIME)
                "7" -> startUpdateValue(session, UpdateSession.Menu.COMBAT_TIME)
                "8" -> convertToCreateSession(session)
                "9", "exit" -> cancelSession(session.player)
                else -> session.player.sendMessage(Component.text("無効な選択です。", NamedTextColor.RED))
            }
        } else {
            // 値の更新
            updateValue(session, message)
        }
    }
    
    private fun showCreateStep(session: CreateSession) {
        val player = session.player
        
        when (session.step) {
            CreateSession.Step.RED_FLAG -> {
                player.sendMessage(Component.text("=== ゲーム作成: ${session.config.name} ===", NamedTextColor.GOLD))
                player.sendMessage(Component.text("赤チームの旗を設置する場所を見て 'set' と入力", NamedTextColor.YELLOW))
            }
            CreateSession.Step.RED_SPAWN -> {
                player.sendMessage(Component.text("赤チームのスポーン地点を見て 'set' と入力", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' で旗位置にスポーン", NamedTextColor.GRAY))
            }
            CreateSession.Step.BLUE_FLAG -> {
                player.sendMessage(Component.text("青チームの旗を設置する場所を見て 'set' と入力", NamedTextColor.YELLOW))
            }
            CreateSession.Step.BLUE_SPAWN -> {
                player.sendMessage(Component.text("青チームのスポーン地点を見て 'set' と入力", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' で旗位置にスポーン", NamedTextColor.GRAY))
            }
            CreateSession.Step.BUILD_MODE -> {
                player.sendMessage(Component.text("建築フェーズのゲームモード (ADVENTURE/SURVIVAL/CREATIVE)", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' でADVENTURE", NamedTextColor.GRAY))
            }
            CreateSession.Step.BUILD_TIME -> {
                player.sendMessage(Component.text("建築フェーズの時間（秒）", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' で300秒", NamedTextColor.GRAY))
            }
            CreateSession.Step.COMBAT_TIME -> {
                player.sendMessage(Component.text("戦闘フェーズの時間（秒）", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' で600秒", NamedTextColor.GRAY))
            }
            CreateSession.Step.MATCH_MODE -> {
                player.sendMessage(Component.text("マッチモード", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("first_to_x: 先取モード", NamedTextColor.WHITE))
                player.sendMessage(Component.text("fixed_rounds: 固定回数モード", NamedTextColor.WHITE))
                player.sendMessage(Component.text("'skip' でfirst_to_x", NamedTextColor.GRAY))
            }
            CreateSession.Step.MATCH_TARGET -> {
                player.sendMessage(Component.text("ゲーム数を入力", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("'skip' で3", NamedTextColor.GRAY))
            }
            CreateSession.Step.COMPLETE -> completeCreate(session)
        }
    }
    
    private fun showUpdateMenu(session: UpdateSession) {
        val player = session.player
        
        player.sendMessage(Component.text("=== ゲーム更新: ${session.config.name} ===", NamedTextColor.GOLD))
        player.sendMessage(Component.text("1. 赤チーム旗位置", NamedTextColor.WHITE))
        player.sendMessage(Component.text("2. 赤チームスポーン", NamedTextColor.WHITE))
        player.sendMessage(Component.text("3. 青チーム旗位置", NamedTextColor.WHITE))
        player.sendMessage(Component.text("4. 青チームスポーン", NamedTextColor.WHITE))
        player.sendMessage(Component.text("5. 建築ゲームモード", NamedTextColor.WHITE))
        player.sendMessage(Component.text("6. 建築時間", NamedTextColor.WHITE))
        player.sendMessage(Component.text("7. 戦闘時間", NamedTextColor.WHITE))
        player.sendMessage(Component.text("8. すべて更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("9. 終了", NamedTextColor.WHITE))
    }
    
    private fun setLocationFromView(session: CreateSession) {
        val player = session.player
        val targetBlock = player.getTargetBlock(null, 100)
        
        if (targetBlock == null || targetBlock.type == Material.AIR) {
            player.sendMessage(Component.text("ブロックが見つかりません", NamedTextColor.RED))
            return
        }
        
        val location = targetBlock.location.add(0.5, 1.0, 0.5).apply {
            yaw = player.location.yaw
            pitch = 0f
        }
        
        when (session.step) {
            CreateSession.Step.RED_FLAG -> {
                session.config.redFlagLocation = location
                player.sendMessage(Component.text("赤旗: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
                session.step = CreateSession.Step.RED_SPAWN
            }
            CreateSession.Step.RED_SPAWN -> {
                session.config.redSpawnLocation = location
                player.sendMessage(Component.text("赤スポーン: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
                session.step = CreateSession.Step.BLUE_FLAG
            }
            CreateSession.Step.BLUE_FLAG -> {
                session.config.blueFlagLocation = location
                player.sendMessage(Component.text("青旗: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
                session.step = CreateSession.Step.BLUE_SPAWN
            }
            CreateSession.Step.BLUE_SPAWN -> {
                session.config.blueSpawnLocation = location
                player.sendMessage(Component.text("青スポーン: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
                session.step = CreateSession.Step.BUILD_MODE
            }
            else -> return
        }
        
        showCreateStep(session)
    }
    
    private fun skipCreateStep(session: CreateSession) {
        when (session.step) {
            CreateSession.Step.RED_FLAG, CreateSession.Step.BLUE_FLAG -> {
                session.player.sendMessage(Component.text("旗位置は必須です", NamedTextColor.RED))
            }
            CreateSession.Step.RED_SPAWN -> {
                session.step = CreateSession.Step.BLUE_FLAG
                showCreateStep(session)
            }
            CreateSession.Step.BLUE_SPAWN -> {
                session.step = CreateSession.Step.BUILD_MODE
                showCreateStep(session)
            }
            CreateSession.Step.BUILD_MODE -> {
                session.step = CreateSession.Step.BUILD_TIME
                showCreateStep(session)
            }
            CreateSession.Step.BUILD_TIME -> {
                session.step = CreateSession.Step.COMBAT_TIME
                showCreateStep(session)
            }
            CreateSession.Step.COMBAT_TIME -> {
                session.step = CreateSession.Step.MATCH_MODE
                showCreateStep(session)
            }
            CreateSession.Step.MATCH_MODE -> {
                session.step = CreateSession.Step.MATCH_TARGET
                showCreateStep(session)
            }
            CreateSession.Step.MATCH_TARGET -> {
                session.step = CreateSession.Step.COMPLETE
                showCreateStep(session)
            }
            CreateSession.Step.COMPLETE -> {}
        }
    }
    
    private fun setBuildMode(session: CreateSession, input: String) {
        val mode = when (input.uppercase()) {
            "ADVENTURE", "SURVIVAL", "CREATIVE" -> input.uppercase()
            else -> {
                session.player.sendMessage(Component.text("無効なモード", NamedTextColor.RED))
                return
            }
        }
        
        session.config.buildPhaseGameMode = mode
        session.player.sendMessage(Component.text("建築モード: $mode", NamedTextColor.GREEN))
        session.step = CreateSession.Step.BUILD_TIME
        showCreateStep(session)
    }
    
    private fun setBuildTime(session: CreateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 10 || time > 3600) {
            session.player.sendMessage(Component.text("10〜3600の間で入力", NamedTextColor.RED))
            return
        }
        
        session.config.buildDuration = time
        session.player.sendMessage(Component.text("建築時間: ${time}秒", NamedTextColor.GREEN))
        session.step = CreateSession.Step.COMBAT_TIME
        showCreateStep(session)
    }
    
    private fun setCombatTime(session: CreateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 10 || time > 3600) {
            session.player.sendMessage(Component.text("10〜3600の間で入力", NamedTextColor.RED))
            return
        }
        
        session.config.combatDuration = time
        session.player.sendMessage(Component.text("戦闘時間: ${time}秒", NamedTextColor.GREEN))
        session.step = CreateSession.Step.MATCH_MODE
        showCreateStep(session)
    }
    
    private fun setMatchMode(session: CreateSession, input: String) {
        val mode = MatchMode.fromString(input)
        if (mode == null) {
            session.player.sendMessage(Component.text("無効なモード", NamedTextColor.RED))
            return
        }
        
        session.config.matchMode = mode
        session.player.sendMessage(Component.text("マッチモード: ${mode.displayName}", NamedTextColor.GREEN))
        session.step = CreateSession.Step.MATCH_TARGET
        showCreateStep(session)
    }
    
    private fun setMatchTarget(session: CreateSession, input: String) {
        val target = input.toIntOrNull()
        if (target == null || target < 1 || target > 10) {
            session.player.sendMessage(Component.text("1〜10の間で入力", NamedTextColor.RED))
            return
        }
        
        session.config.matchTarget = target
        session.player.sendMessage(Component.text("目標: $target", NamedTextColor.GREEN))
        session.step = CreateSession.Step.COMPLETE
        showCreateStep(session)
    }
    
    private fun completeCreate(session: CreateSession) {
        if (!session.config.isValid()) {
            session.player.sendMessage(Component.text("旗位置が必要です", NamedTextColor.RED))
            cancelSession(session.player)
            return
        }
        
        activeSessions.remove(session.player.uniqueId)
        onCreateComplete?.invoke(session.config)
        
        session.player.sendMessage(Component.text("ゲーム '${session.config.name}' を作成しました！", NamedTextColor.GREEN))
    }
    
    private fun startUpdateLocation(session: UpdateSession, menu: UpdateSession.Menu) {
        session.menu = menu
        session.waitingForInput = true
        
        val text = when (menu) {
            UpdateSession.Menu.RED_FLAG -> "赤チームの旗位置"
            UpdateSession.Menu.RED_SPAWN -> "赤チームのスポーン"
            UpdateSession.Menu.BLUE_FLAG -> "青チームの旗位置"
            UpdateSession.Menu.BLUE_SPAWN -> "青チームのスポーン"
            else -> return
        }
        
        session.player.sendMessage(Component.text("$text を見て 'set' と入力", NamedTextColor.YELLOW))
    }
    
    private fun startUpdateValue(session: UpdateSession, menu: UpdateSession.Menu) {
        session.menu = menu
        session.waitingForInput = true
        
        when (menu) {
            UpdateSession.Menu.BUILD_MODE -> {
                session.player.sendMessage(Component.text("ゲームモード (ADVENTURE/SURVIVAL/CREATIVE)", NamedTextColor.YELLOW))
            }
            UpdateSession.Menu.BUILD_TIME -> {
                session.player.sendMessage(Component.text("建築時間（秒）", NamedTextColor.YELLOW))
            }
            UpdateSession.Menu.COMBAT_TIME -> {
                session.player.sendMessage(Component.text("戦闘時間（秒）", NamedTextColor.YELLOW))
            }
            else -> {}
        }
    }
    
    private fun updateValue(session: UpdateSession, input: String) {
        when (session.menu) {
            UpdateSession.Menu.RED_FLAG,
            UpdateSession.Menu.RED_SPAWN,
            UpdateSession.Menu.BLUE_FLAG,
            UpdateSession.Menu.BLUE_SPAWN -> {
                if (input.lowercase() == "set") {
                    updateLocationFromView(session)
                }
            }
            UpdateSession.Menu.BUILD_MODE -> updateBuildMode(session, input)
            UpdateSession.Menu.BUILD_TIME -> updateBuildTime(session, input)
            UpdateSession.Menu.COMBAT_TIME -> updateCombatTime(session, input)
            else -> {}
        }
    }
    
    private fun updateLocationFromView(session: UpdateSession) {
        val player = session.player
        val targetBlock = player.getTargetBlock(null, 100)
        
        if (targetBlock == null || targetBlock.type == Material.AIR) {
            player.sendMessage(Component.text("ブロックが見つかりません", NamedTextColor.RED))
            return
        }
        
        val location = targetBlock.location.add(0.5, 1.0, 0.5).apply {
            yaw = player.location.yaw
            pitch = 0f
        }
        
        when (session.menu) {
            UpdateSession.Menu.RED_FLAG -> session.config.redFlagLocation = location
            UpdateSession.Menu.RED_SPAWN -> session.config.redSpawnLocation = location
            UpdateSession.Menu.BLUE_FLAG -> session.config.blueFlagLocation = location
            UpdateSession.Menu.BLUE_SPAWN -> session.config.blueSpawnLocation = location
            else -> return
        }
        
        player.sendMessage(Component.text("更新しました", NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun updateBuildMode(session: UpdateSession, input: String) {
        val mode = when (input.uppercase()) {
            "ADVENTURE", "SURVIVAL", "CREATIVE" -> input.uppercase()
            else -> {
                session.player.sendMessage(Component.text("無効なモード", NamedTextColor.RED))
                return
            }
        }
        
        session.config.buildPhaseGameMode = mode
        session.player.sendMessage(Component.text("更新しました", NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun updateBuildTime(session: UpdateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 10 || time > 3600) {
            session.player.sendMessage(Component.text("10〜3600の間で入力", NamedTextColor.RED))
            return
        }
        
        session.config.buildDuration = time
        session.player.sendMessage(Component.text("更新しました", NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun updateCombatTime(session: UpdateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 10 || time > 3600) {
            session.player.sendMessage(Component.text("10〜3600の間で入力", NamedTextColor.RED))
            return
        }
        
        session.config.combatDuration = time
        session.player.sendMessage(Component.text("更新しました", NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun convertToCreateSession(session: UpdateSession) {
        activeSessions.remove(session.player.uniqueId)
        val createSession = CreateSession(session.player, session.config)
        activeSessions[session.player.uniqueId] = createSession
        showCreateStep(createSession)
    }
    
    private fun cancelSession(player: Player) {
        activeSessions.remove(player.uniqueId)
        player.sendMessage(Component.text("キャンセルしました", NamedTextColor.YELLOW))
    }
    
    private fun startTimeout(uuid: UUID) {
        object : BukkitRunnable() {
            override fun run() {
                val session = activeSessions[uuid] ?: return
                if (System.currentTimeMillis() - session.startTime > 60000) {
                    session.player.sendMessage(Component.text("タイムアウトしました", NamedTextColor.RED))
                    cancelSession(session.player)
                }
            }
        }.runTaskLater(plugin, 1200L) // 60秒
    }
    
    private fun validateGameName(name: String): Boolean {
        if (name.length > 32) return false
        if (!name.matches(Regex("[a-zA-Z0-9_]+"))) return false
        return !listOf("all", "list", "help").contains(name.lowercase())
    }
    
    fun hasActiveSession(player: Player): Boolean = activeSessions.containsKey(player.uniqueId)
    
    fun clearSession(player: Player) = activeSessions.remove(player.uniqueId)
    
    /**
     * マップ自動確認セッション開始
     */
    fun waitForMapAutoConfirm(player: Player, gameName: String, callback: (Boolean) -> Unit) {
        val session = MapAutoConfirmSession(player, gameName, callback) { p ->
            activeSessions.remove(p.uniqueId)
        }
        activeSessions[player.uniqueId] = session
        session.showCurrentStep()
        startTimeout(player.uniqueId)
    }
    
    // コールバック
    var onCreateComplete: ((GameConfig) -> Unit)? = null
    var onUpdateComplete: ((GameConfig) -> Unit)? = null
}