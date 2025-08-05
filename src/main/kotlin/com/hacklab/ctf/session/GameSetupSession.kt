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
        abstract val plugin: Main
    }
    
    // 作成セッション
    class CreateSession(
        player: Player,
        val config: GameConfig,
        var step: Step = Step.RED_FLAG,
        override val plugin: Main
    ) : Session(player) {
        
        enum class Step {
            RED_FLAG, RED_SPAWN, BLUE_FLAG, BLUE_SPAWN,
            BUILD_MODE, BUILD_TIME, COMBAT_TIME, RESULT_TIME,
            INTERMEDIATE_TIME, MATCH_MODE, MATCH_TARGET, COMPLETE
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
        var waitingForInput: Boolean = false,
        override val plugin: Main
    ) : Session(player) {
        
        enum class Menu {
            MAIN, RED_FLAG, RED_SPAWN, BLUE_FLAG, BLUE_SPAWN,
            BUILD_MODE, BUILD_TIME, COMBAT_TIME, RESULT_TIME,
            INTERMEDIATE_TIME
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
        val onComplete: (Player) -> Unit,
        override val plugin: Main
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
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.invalid-answer"), NamedTextColor.YELLOW))
                    return
                }
            }
        }
        
        override fun showCurrentStep() {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.map-auto-detect-prompt"), NamedTextColor.YELLOW))
            player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.map-auto-detect-hint"), NamedTextColor.GRAY))
        }
    }
    
    /**
     * 作成セッション開始
     */
    fun startCreateSession(player: Player, gameName: String, world: org.bukkit.World): Boolean {
        if (!validateGameName(gameName)) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.invalid-game-name"), NamedTextColor.RED))
            return false
        }
        
        val config = GameConfig(gameName, world)
        val session = CreateSession(player, config, plugin = plugin)
        activeSessions[player.uniqueId] = session
        
        startTimeout(player.uniqueId)
        showCreateStep(session)
        return true
    }
    
    /**
     * 更新セッション開始
     */
    fun startUpdateSession(player: Player, config: GameConfig): Boolean {
        val session = UpdateSession(player, config.copy(), plugin = plugin)
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
                    CreateSession.Step.RESULT_TIME -> setResultTime(session, message)
                    CreateSession.Step.INTERMEDIATE_TIME -> setIntermediateTime(session, message)
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
                "8" -> startUpdateValue(session, UpdateSession.Menu.RESULT_TIME)
                "9" -> startUpdateValue(session, UpdateSession.Menu.INTERMEDIATE_TIME)
                "10" -> convertToCreateSession(session)
                "11", "exit" -> cancelSession(session.player)
                else -> session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.invalid-selection"), NamedTextColor.RED))
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
                player.sendMessage(Component.text("=== ${plugin.languageManager.getMessage("setup.create-header", "name" to session.config.name)} ===", NamedTextColor.GOLD))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.red-flag-prompt"), NamedTextColor.YELLOW))
            }
            CreateSession.Step.RED_SPAWN -> {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.red-spawn-prompt"), NamedTextColor.YELLOW))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.skip-to-flag"), NamedTextColor.GRAY))
            }
            CreateSession.Step.BLUE_FLAG -> {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.blue-flag-prompt"), NamedTextColor.YELLOW))
            }
            CreateSession.Step.BLUE_SPAWN -> {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.blue-spawn-prompt"), NamedTextColor.YELLOW))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.skip-to-flag"), NamedTextColor.GRAY))
            }
            CreateSession.Step.BUILD_MODE -> {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.build-mode-prompt"), NamedTextColor.YELLOW))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.skip-adventure"), NamedTextColor.GRAY))
            }
            CreateSession.Step.BUILD_TIME -> {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.build-time-prompt"), NamedTextColor.YELLOW))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.skip-300"), NamedTextColor.GRAY))
            }
            CreateSession.Step.COMBAT_TIME -> {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.combat-time-prompt"), NamedTextColor.YELLOW))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.skip-600"), NamedTextColor.GRAY))
            }
            CreateSession.Step.RESULT_TIME -> {
                player.sendMessage(Component.text("§e結果表示フェーズの時間を秒単位で入力してください (例: 60)", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("§7'skip' でデフォルト値 (60秒) を使用", NamedTextColor.GRAY))
            }
            CreateSession.Step.INTERMEDIATE_TIME -> {
                player.sendMessage(Component.text("§eマッチ中間の作戦会議時間を秒単位で入力してください (例: 15)", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("§7'skip' でデフォルト値 (15秒) を使用", NamedTextColor.GRAY))
            }
            CreateSession.Step.MATCH_MODE -> {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.match-mode-prompt"), NamedTextColor.YELLOW))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.first-to-x-mode"), NamedTextColor.WHITE))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.fixed-rounds-mode"), NamedTextColor.WHITE))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.skip-first-to-x"), NamedTextColor.GRAY))
            }
            CreateSession.Step.MATCH_TARGET -> {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.game-count-prompt"), NamedTextColor.YELLOW))
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.skip-3"), NamedTextColor.GRAY))
            }
            CreateSession.Step.COMPLETE -> completeCreate(session)
        }
    }
    
    private fun showUpdateMenu(session: UpdateSession) {
        val player = session.player
        
        player.sendMessage(Component.text("=== ${plugin.languageManager.getMessage("setup.update-header", "name" to session.config.name)} ===", NamedTextColor.GOLD))
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.red-flag-menu"), NamedTextColor.WHITE))
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.red-spawn-menu"), NamedTextColor.WHITE))
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.blue-flag-menu"), NamedTextColor.WHITE))
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.blue-spawn-menu"), NamedTextColor.WHITE))
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.build-mode-menu"), NamedTextColor.WHITE))
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.build-time-menu"), NamedTextColor.WHITE))
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.combat-time-menu"), NamedTextColor.WHITE))
        player.sendMessage(Component.text("§f8. 結果表示時間を更新 (現在: ${session.config.resultDuration}秒)", NamedTextColor.WHITE))
        player.sendMessage(Component.text("§f9. マッチ中間の作戦会議時間を更新 (現在: ${session.config.intermediateDuration}秒)", NamedTextColor.WHITE))
        player.sendMessage(Component.text("§f10. すべて更新", NamedTextColor.WHITE))
        player.sendMessage(Component.text("§f11. 終了", NamedTextColor.WHITE))
    }
    
    private fun setLocationFromView(session: CreateSession) {
        val player = session.player
        val targetBlock = player.getTargetBlock(null, 100)
        
        if (targetBlock == null || targetBlock.type == Material.AIR) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.block-not-found"), NamedTextColor.RED))
            return
        }
        
        val location = targetBlock.location.add(0.5, 1.0, 0.5).apply {
            yaw = player.location.yaw
            pitch = 0f
        }
        
        when (session.step) {
            CreateSession.Step.RED_FLAG -> {
                session.config.redFlagLocation = location
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.red-flag-set", "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
                session.step = CreateSession.Step.RED_SPAWN
            }
            CreateSession.Step.RED_SPAWN -> {
                session.config.redSpawnLocation = location
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.red-spawn-set", "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
                session.step = CreateSession.Step.BLUE_FLAG
            }
            CreateSession.Step.BLUE_FLAG -> {
                session.config.blueFlagLocation = location
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.blue-flag-set", "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
                session.step = CreateSession.Step.BLUE_SPAWN
            }
            CreateSession.Step.BLUE_SPAWN -> {
                session.config.blueSpawnLocation = location
                player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.blue-spawn-set", "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
                session.step = CreateSession.Step.BUILD_MODE
            }
            else -> return
        }
        
        showCreateStep(session)
    }
    
    private fun skipCreateStep(session: CreateSession) {
        when (session.step) {
            CreateSession.Step.RED_FLAG, CreateSession.Step.BLUE_FLAG -> {
                session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.flag-required"), NamedTextColor.RED))
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
                session.step = CreateSession.Step.RESULT_TIME
                showCreateStep(session)
            }
            CreateSession.Step.RESULT_TIME -> {
                session.step = CreateSession.Step.INTERMEDIATE_TIME
                showCreateStep(session)
            }
            CreateSession.Step.INTERMEDIATE_TIME -> {
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
                session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.invalid-mode"), NamedTextColor.RED))
                return
            }
        }
        
        session.config.buildPhaseGameMode = mode
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.build-mode-set", "mode" to mode), NamedTextColor.GREEN))
        session.step = CreateSession.Step.BUILD_TIME
        showCreateStep(session)
    }
    
    private fun setBuildTime(session: CreateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 10 || time > 3600) {
            session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.time-range-error"), NamedTextColor.RED))
            return
        }
        
        session.config.buildDuration = time
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.build-time-set", "time" to time.toString()), NamedTextColor.GREEN))
        session.step = CreateSession.Step.COMBAT_TIME
        showCreateStep(session)
    }
    
    private fun setCombatTime(session: CreateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 10 || time > 3600) {
            session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.time-range-error"), NamedTextColor.RED))
            return
        }
        
        session.config.combatDuration = time
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.combat-time-set", "time" to time.toString()), NamedTextColor.GREEN))
        session.step = CreateSession.Step.RESULT_TIME
        showCreateStep(session)
    }
    
    private fun setResultTime(session: CreateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 5 || time > 300) {
            session.player.sendMessage(Component.text("§c時間は5〜300秒の範囲で入力してください", NamedTextColor.RED))
            return
        }
        
        session.config.resultDuration = time
        session.player.sendMessage(Component.text("§a結果表示時間を${time}秒に設定しました", NamedTextColor.GREEN))
        session.step = CreateSession.Step.INTERMEDIATE_TIME
        showCreateStep(session)
    }
    
    private fun setIntermediateTime(session: CreateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 5 || time > 120) {
            session.player.sendMessage(Component.text("§c時間は5〜120秒の範囲で入力してください", NamedTextColor.RED))
            return
        }
        
        session.config.intermediateDuration = time
        session.player.sendMessage(Component.text("§aマッチ中間の作戦会議時間を${time}秒に設定しました", NamedTextColor.GREEN))
        session.step = CreateSession.Step.MATCH_MODE
        showCreateStep(session)
    }
    
    private fun setMatchMode(session: CreateSession, input: String) {
        val mode = MatchMode.fromString(input)
        if (mode == null) {
            session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.invalid-mode"), NamedTextColor.RED))
            return
        }
        
        session.config.matchMode = mode
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.match-mode-set", "mode" to mode.displayName), NamedTextColor.GREEN))
        session.step = CreateSession.Step.MATCH_TARGET
        showCreateStep(session)
    }
    
    private fun setMatchTarget(session: CreateSession, input: String) {
        val target = input.toIntOrNull()
        if (target == null || target < 1 || target > 10) {
            session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.match-range-error"), NamedTextColor.RED))
            return
        }
        
        session.config.matchTarget = target
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.match-target-set", "target" to target.toString()), NamedTextColor.GREEN))
        session.step = CreateSession.Step.COMPLETE
        showCreateStep(session)
    }
    
    private fun completeCreate(session: CreateSession) {
        if (!session.config.isValid()) {
            session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.flag-position-required"), NamedTextColor.RED))
            cancelSession(session.player)
            return
        }
        
        activeSessions.remove(session.player.uniqueId)
        onCreateComplete?.invoke(session.config)
        
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.game-created", "name" to session.config.name), NamedTextColor.GREEN))
    }
    
    private fun startUpdateLocation(session: UpdateSession, menu: UpdateSession.Menu) {
        session.menu = menu
        session.waitingForInput = true
        
        val text = when (menu) {
            UpdateSession.Menu.RED_FLAG -> plugin.languageManager.getMessage("setup.red-flag-location")
            UpdateSession.Menu.RED_SPAWN -> plugin.languageManager.getMessage("setup.red-spawn-location")
            UpdateSession.Menu.BLUE_FLAG -> plugin.languageManager.getMessage("setup.blue-flag-location")
            UpdateSession.Menu.BLUE_SPAWN -> plugin.languageManager.getMessage("setup.blue-spawn-location")
            else -> return
        }
        
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.setting-prompt", "text" to text), NamedTextColor.YELLOW))
    }
    
    private fun startUpdateValue(session: UpdateSession, menu: UpdateSession.Menu) {
        session.menu = menu
        session.waitingForInput = true
        
        when (menu) {
            UpdateSession.Menu.BUILD_MODE -> {
                session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.build-mode-prompt"), NamedTextColor.YELLOW))
            }
            UpdateSession.Menu.BUILD_TIME -> {
                session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.build-time-prompt"), NamedTextColor.YELLOW))
            }
            UpdateSession.Menu.COMBAT_TIME -> {
                session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.combat-time-prompt"), NamedTextColor.YELLOW))
            }
            UpdateSession.Menu.RESULT_TIME -> {
                session.player.sendMessage(Component.text("§e結果表示時間を秒単位で入力してください (現在: ${session.config.resultDuration}秒)", NamedTextColor.YELLOW))
            }
            UpdateSession.Menu.INTERMEDIATE_TIME -> {
                session.player.sendMessage(Component.text("§eマッチ中間の作戦会議時間を秒単位で入力してください (現在: ${session.config.intermediateDuration}秒)", NamedTextColor.YELLOW))
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
            UpdateSession.Menu.RESULT_TIME -> updateResultTime(session, input)
            UpdateSession.Menu.INTERMEDIATE_TIME -> updateIntermediateTime(session, input)
            else -> {}
        }
    }
    
    private fun updateLocationFromView(session: UpdateSession) {
        val player = session.player
        val targetBlock = player.getTargetBlock(null, 100)
        
        if (targetBlock == null || targetBlock.type == Material.AIR) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.block-not-found"), NamedTextColor.RED))
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
        
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.updated"), NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun updateBuildMode(session: UpdateSession, input: String) {
        val mode = when (input.uppercase()) {
            "ADVENTURE", "SURVIVAL", "CREATIVE" -> input.uppercase()
            else -> {
                session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.invalid-mode"), NamedTextColor.RED))
                return
            }
        }
        
        session.config.buildPhaseGameMode = mode
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.updated"), NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun updateBuildTime(session: UpdateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 10 || time > 3600) {
            session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.time-range-error"), NamedTextColor.RED))
            return
        }
        
        session.config.buildDuration = time
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.updated"), NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun updateCombatTime(session: UpdateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 10 || time > 3600) {
            session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.time-range-error"), NamedTextColor.RED))
            return
        }
        
        session.config.combatDuration = time
        session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.updated"), NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun updateResultTime(session: UpdateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 5 || time > 300) {
            session.player.sendMessage(Component.text("§c時間は5〜300秒の範囲で入力してください", NamedTextColor.RED))
            return
        }
        
        session.config.resultDuration = time
        session.player.sendMessage(Component.text("§a結果表示時間を更新しました", NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun updateIntermediateTime(session: UpdateSession, input: String) {
        val time = input.toIntOrNull()
        if (time == null || time < 5 || time > 120) {
            session.player.sendMessage(Component.text("§c時間は5〜120秒の範囲で入力してください", NamedTextColor.RED))
            return
        }
        
        session.config.intermediateDuration = time
        session.player.sendMessage(Component.text("§aマッチ中間の作戦会議時間を更新しました", NamedTextColor.GREEN))
        onUpdateComplete?.invoke(session.config)
        
        session.waitingForInput = false
        session.menu = UpdateSession.Menu.MAIN
        showUpdateMenu(session)
    }
    
    private fun convertToCreateSession(session: UpdateSession) {
        activeSessions.remove(session.player.uniqueId)
        val createSession = CreateSession(session.player, session.config, plugin = plugin)
        activeSessions[session.player.uniqueId] = createSession
        showCreateStep(createSession)
    }
    
    private fun cancelSession(player: Player) {
        activeSessions.remove(player.uniqueId)
        player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.cancelled"), NamedTextColor.YELLOW))
    }
    
    private fun startTimeout(uuid: UUID) {
        object : BukkitRunnable() {
            override fun run() {
                val session = activeSessions[uuid] ?: return
                if (System.currentTimeMillis() - session.startTime > 60000) {
                    session.player.sendMessage(Component.text(plugin.languageManager.getMessage("setup.timeout"), NamedTextColor.RED))
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
        val session = MapAutoConfirmSession(player, gameName, callback, { p ->
            activeSessions.remove(p.uniqueId)
        }, plugin = plugin)
        activeSessions[player.uniqueId] = session
        session.showCurrentStep()
        startTimeout(player.uniqueId)
    }
    
    // コールバック
    var onCreateComplete: ((GameConfig) -> Unit)? = null
    var onUpdateComplete: ((GameConfig) -> Unit)? = null
}