package com.hacklab.ctf.commands

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManager
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.MatchMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

class CTFCommandNew(private val plugin: Main) : CommandExecutor, TabCompleter {
    
    private val gameManager = plugin.gameManager
    private val confirmations = mutableMapOf<Player, ConfirmationData>()
    
    data class ConfirmationData(
        val gameName: String,
        val timestamp: Long
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sendHelpMessage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "create" -> return handleCreateCommand(sender, args)
            "update" -> return handleUpdateCommand(sender, args)
            "delete" -> return handleDeleteCommand(sender, args)
            "list" -> return handleListCommand(sender)
            "start" -> return handleStartCommand(sender, args)
            "stop" -> return handleStopCommand(sender, args)
            "join" -> return handleJoinCommand(sender, args)
            "leave" -> return handleLeaveCommand(sender)
            "team" -> return handleTeamCommand(sender, args)
            "spectator" -> return handleSpectatorCommand(sender, args)
            "setflag" -> return handleSetFlagCommand(sender, args)
            "setspawn" -> return handleSetSpawnCommand(sender, args)
            "addspawn" -> return handleAddSpawnCommand(sender, args)
            "removespawn" -> return handleRemoveSpawnCommand(sender, args)
            "listspawns" -> return handleListSpawnsCommand(sender, args)
            "status" -> return handleStatusCommand(sender, args)
            "info" -> return handleInfoCommand(sender, args)
            // マップ関連コマンド
            "setpos1" -> return handleSetPos1Command(sender, args)
            "setpos2" -> return handleSetPos2Command(sender, args)
            "savemap" -> return handleSaveMapCommand(sender, args)
            // 管理者向けプレイヤー管理コマンド
            "addplayer" -> return handleAddPlayerCommand(sender, args)
            "changeteam" -> return handleChangeTeamCommand(sender, args)
            // 統計関連コマンド
            "stats" -> return handleStatsCommand(sender, args)
            "leaderboard" -> return handleLeaderboardCommand(sender, args)
            "resetstats" -> return handleResetStatsCommand(sender, args)
            "balance" -> return handleBalanceCommand(sender, args)
            else -> {
                sendHelpMessage(sender)
                return true
            }
        }
    }

    private fun handleCreateCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-create"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        gameManager.startCreateGame(sender, gameName)
        
        return true
    }
    
    private fun handleUpdateCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-update"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        gameManager.startUpdateGame(sender, gameName)
        
        return true
    }
    
    private fun handleDeleteCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-delete"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        if (gameManager.deleteGame(gameName)) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-deleted", "name" to gameName), NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
        }
        
        return true
    }
    
    private fun handleListCommand(sender: CommandSender): Boolean {
        val games = gameManager.getAllGames()
        
        if (games.isEmpty()) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-games"), NamedTextColor.YELLOW))
            return true
        }
        
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-list-header"), NamedTextColor.GOLD))
        
        games.entries.forEachIndexed { index, (name, game) ->
            val status = when {
                game.state == GameState.WAITING && game.getRedFlagLocation() == null -> plugin.languageManager.getMessage("command.status-configuring")
                game.state == GameState.WAITING -> plugin.languageManager.getMessage("command.status-waiting")
                game.state == GameState.RUNNING -> plugin.languageManager.getMessage("command.status-running")
                else -> plugin.languageManager.getMessage("command.status-ending")
            }
            
            val redSize = game.redTeam.size
            val blueSize = game.blueTeam.size
            
            val statusColor = when (status) {
                plugin.languageManager.getMessage("command.status-running") -> NamedTextColor.GREEN
                plugin.languageManager.getMessage("command.status-waiting") -> NamedTextColor.YELLOW
                plugin.languageManager.getMessage("command.status-configuring") -> NamedTextColor.GRAY
                else -> NamedTextColor.RED
            }
            
            sender.sendMessage(
                Component.text(plugin.languageManager.getMessage("command.list-item", "index" to (index + 1).toString(), "name" to name), NamedTextColor.WHITE)
                    .append(Component.text(plugin.languageManager.getMessage("command.list-status", "status" to status), statusColor))
                    .append(Component.text(plugin.languageManager.getMessage("ui.separator-dash"), NamedTextColor.GRAY))
                    .append(Component.text(plugin.languageManager.getMessage("command.list-red-count", "count" to redSize.toString()), NamedTextColor.RED))
                    .append(Component.text(plugin.languageManager.getMessage("ui.separator-comma"), NamedTextColor.GRAY))
                    .append(Component.text(plugin.languageManager.getMessage("command.list-blue-count", "count" to blueSize.toString()), NamedTextColor.BLUE))
            )
            
            if (game.getRedFlagLocation() == null || game.getBlueFlagLocation() == null) {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.flags-not-set-warning"), NamedTextColor.YELLOW))
            }
        }
        
        return true
    }
    
    private fun handleStartCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-start"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.start-mode-hint"), NamedTextColor.GRAY))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.auto-param-hint"), NamedTextColor.GRAY))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        // YAMLファイルからゲーム設定を取得
        val gameConfig = gameManager.getGameConfig(gameName)
        
        // マッチモードの判定
        var isMatch = false
        var matchTarget = plugin.config.getInt("match.default-target", 3)
        
        if (args.size >= 3) {
            when (args[2].lowercase()) {
                "single" -> {
                    // 明示的にシングルゲームモード
                    isMatch = false
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.single-game-start"), NamedTextColor.YELLOW))
                }
                "match" -> {
                    // 明示的にマッチモード
                    isMatch = true
                    if (args.size >= 4) {
                        try {
                            matchTarget = args[3].toInt()
                            if (matchTarget < 1) {
                                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-count-min"), NamedTextColor.RED))
                                return true
                            }
                        } catch (e: NumberFormatException) {
                            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.invalid-game-count"), NamedTextColor.RED))
                            return true
                        }
                    } else if (gameConfig != null && gameConfig.matchTarget > 1) {
                        // YAML設定から取得
                        matchTarget = gameConfig.matchTarget
                    }
                }
                else -> {
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.invalid-usage"), NamedTextColor.RED))
                    return true
                }
            }
        } else {
            // パラメータが指定されていない場合、YAML設定から自動判定
            if (gameConfig != null && gameConfig.matchMode == MatchMode.FIXED_ROUNDS && gameConfig.matchTarget > 1) {
                isMatch = true
                matchTarget = gameConfig.matchTarget
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.match-start-config", "count" to matchTarget.toString()), NamedTextColor.YELLOW))
            } else {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.single-game-start"), NamedTextColor.YELLOW))
            }
        }
        
        if (gameManager.startGame(gameName, isMatch, if (isMatch) matchTarget else null)) {
            if (isMatch) {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.match-started", "count" to matchTarget.toString()), NamedTextColor.GREEN))
            } else {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-started"), NamedTextColor.GREEN))
            }
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-start-failed"), NamedTextColor.RED))
        }
        
        return true
    }
    
    private fun handleStopCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-stop"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        val matchWrapper = gameManager.getMatch(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        if (matchWrapper != null && matchWrapper.isActive) {
            // マッチを強制停止
            game.stop(forceStop = true)
            matchWrapper.isActive = false
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.match-stopped", "name" to gameName), NamedTextColor.GREEN))
        } else if (game.state != GameState.WAITING) {
            // 単一ゲームを強制停止
            game.stop(forceStop = true)
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-stopped", "name" to gameName), NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-running", "name" to gameName), NamedTextColor.RED))
        }
        
        return true
    }
    
    private fun handleJoinCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-join"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val currentGame = gameManager.getPlayerGame(sender)
        
        // 既に他のゲームに参加している場合
        if (currentGame != null && currentGame.name.lowercase() != gameName.lowercase()) {
            // 確認待ちチェック
            val confirmation = confirmations[sender]
            if (confirmation != null && 
                confirmation.gameName == gameName && 
                System.currentTimeMillis() - confirmation.timestamp < 30000) {
                // 確認済みなので参加
                confirmations.remove(sender)
                if (gameManager.addPlayerToGame(sender, gameName, true)) {
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-joined", "name" to gameName), NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.join-failed"), NamedTextColor.RED))
                }
            } else {
                // 確認ダイアログ表示
                confirmations[sender] = ConfirmationData(gameName, System.currentTimeMillis())
                
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.already-in-game", "name" to currentGame.name), NamedTextColor.YELLOW))
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.join-confirm", "name" to gameName), NamedTextColor.YELLOW))
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.join-confirm-time"), NamedTextColor.GRAY))
                
                // 30秒後にタイムアウト
                object : BukkitRunnable() {
                    override fun run() {
                        confirmations.remove(sender)
                    }
                }.runTaskLater(plugin, 600L)
            }
        } else {
            // 新規参加
            if (gameManager.addPlayerToGame(sender, gameName)) {
                // 成功メッセージは Game 側で表示
            } else {
                val game = gameManager.getGame(gameName)
                if (game == null) {
                    sender.sendMessage(plugin.languageManager.getMessageAsComponent("command.game-not-found", "name" to gameName))
                } else if (game.state != GameState.WAITING) {
                    sender.sendMessage(plugin.languageManager.getMessageAsComponent("command.cannot-join-state", "state" to game.state.toString()))
                } else if (game.redTeam.size + game.blueTeam.size >= game.maxPlayersPerTeam * 2) {
                    sender.sendMessage(plugin.languageManager.getMessageAsComponent("command.game-full", "max" to (game.maxPlayersPerTeam * 2).toString()))
                } else {
                    sender.sendMessage(plugin.languageManager.getMessageAsComponent("command.join-failed"))
                }
            }
        }
        
        return true
    }
    
    private fun handleLeaveCommand(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        val game = gameManager.getPlayerGame(sender)
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.not-in-game"), NamedTextColor.RED))
            return true
        }
        
        gameManager.removePlayerFromGame(sender)
        // 退出メッセージは Game 側で表示
        
        return true
    }
    
    private fun handleTeamCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        val currentGame = gameManager.getPlayerGame(sender)
        if (currentGame == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.not-in-game"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            // 現在のチームを表示
            val currentTeam = currentGame.getPlayerTeam(sender.uniqueId)
            if (currentTeam != null) {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.current-team", "team" to plugin.languageManager.getMessage("teams.${currentTeam.name.lowercase()}")), currentTeam.color))
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.team-change-usage"), NamedTextColor.GRAY))
            }
            return true
        }
        
        // ゲームが既に開始している場合は変更不可
        if (currentGame.state != com.hacklab.ctf.utils.GameState.WAITING) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.cannot-change-team-after-start"), NamedTextColor.RED))
            return true
        }
        
        val newTeam = when (args[1].lowercase()) {
            "red" -> com.hacklab.ctf.utils.Team.RED
            "blue" -> com.hacklab.ctf.utils.Team.BLUE
            else -> {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.invalid-team"), NamedTextColor.RED))
                return true
            }
        }
        
        val currentTeam = currentGame.getPlayerTeam(sender.uniqueId)
        if (currentTeam == newTeam) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.already-in-team", "team" to plugin.languageManager.getMessage("teams.${newTeam.name.lowercase()}")), newTeam.color))
            return true
        }
        
        // チーム人数チェック
        val targetTeamSize = if (newTeam == com.hacklab.ctf.utils.Team.RED) currentGame.redTeam.size else currentGame.blueTeam.size
        if (targetTeamSize >= currentGame.maxPlayersPerTeam) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.team-full", "team" to plugin.languageManager.getMessage("teams.${newTeam.name.lowercase()}"), "max" to currentGame.maxPlayersPerTeam.toString()), NamedTextColor.RED))
            return true
        }
        
        // チーム変更処理
        if (currentTeam != null) {
            // 現在のチームから削除
            when (currentTeam) {
                com.hacklab.ctf.utils.Team.RED -> currentGame.redTeam.remove(sender.uniqueId)
                com.hacklab.ctf.utils.Team.BLUE -> currentGame.blueTeam.remove(sender.uniqueId)
                com.hacklab.ctf.utils.Team.SPECTATOR -> currentGame.spectators.remove(sender.uniqueId)
            }
        }
        
        // 新しいチームに追加
        when (newTeam) {
            com.hacklab.ctf.utils.Team.RED -> currentGame.redTeam.add(sender.uniqueId)
            com.hacklab.ctf.utils.Team.BLUE -> currentGame.blueTeam.add(sender.uniqueId)
            com.hacklab.ctf.utils.Team.SPECTATOR -> {} // 観戦者への変更はspectatorコマンドで行う
        }
        
        // メッセージ送信
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.team-changed", "team" to plugin.languageManager.getMessage("teams.${newTeam.name.lowercase()}")), newTeam.color))
        
        // 他のプレイヤーに通知
        currentGame.getAllPlayers().forEach { player ->
            if (player != sender) {
                player.sendMessage(Component.text(plugin.languageManager.getMessage("command.player-changed-team", "player" to sender.name, "team" to plugin.languageManager.getMessage("teams.${newTeam.name.lowercase()}")), NamedTextColor.YELLOW))
            }
        }
        
        // タブリストの色を更新
        currentGame.updatePlayerTabColor(sender)
        
        // スコアボード更新
        currentGame.updateScoreboard()
        
        return true
    }
    
    private fun handleSpectatorCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        // 引数なしでも処理可能（現在参加しているゲームで観戦者になる）
        val gameName: String? = if (args.size >= 2) {
            args[1]
        } else {
            // 現在参加しているゲームを取得
            val currentGame = gameManager.getPlayerGame(sender)
            currentGame?.name
        }
        
        if (gameName == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("spectator.usage"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("spectator.hint"), NamedTextColor.GRAY))
            return true
        }
        
        val game = gameManager.getGame(gameName)
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        // 既に他のゲームに参加している場合の処理
        val currentGame = gameManager.getPlayerGame(sender)
        if (currentGame != null && currentGame.name != gameName) {
            gameManager.removePlayerFromGame(sender)
        }
        
        // 観戦者として追加
        if (gameManager.addPlayerAsSpectator(sender, gameName)) {
            // メッセージはGame側で表示されるため、ここでは追加処理のみ
            
            // ゲームが実行中の場合、適切な位置にテレポート
            if (game.state == GameState.RUNNING || game.state == GameState.STARTING) {
                val centerLocation = game.getCenterLocation()
                if (centerLocation != null) {
                    sender.teleport(centerLocation)
                }
            }
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("spectator.join-failed"), NamedTextColor.RED))
        }
        
        return true
    }
    
    private fun handleSetFlagCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 3) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-setflag"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        val targetBlock = sender.getTargetBlock(null, 100)
        if (targetBlock == null || targetBlock.type == org.bukkit.Material.AIR) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-block-found"), NamedTextColor.RED))
            return true
        }
        
        val location = targetBlock.location.add(0.5, 1.0, 0.5)
        location.yaw = sender.location.yaw
        location.pitch = 0f
        
        when (args[2].lowercase()) {
            "red" -> {
                game.setRedFlagLocation(location)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.red-flag-set", "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
            }
            "blue" -> {
                game.setBlueFlagLocation(location)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.blue-flag-set", "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
            }
            else -> {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.invalid-team"), NamedTextColor.RED))
                return true
            }
        }
        
        // 設定は新しいGameManagerでは自動保存される
        
        return true
    }
    
    private fun handleSetSpawnCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 3) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-setspawn"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        val targetBlock = sender.getTargetBlock(null, 100)
        if (targetBlock == null || targetBlock.type == org.bukkit.Material.AIR) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-block-found"), NamedTextColor.RED))
            return true
        }
        
        val location = targetBlock.location.add(0.5, 1.0, 0.5)
        location.yaw = sender.location.yaw
        location.pitch = 0f
        
        when (args[2].lowercase()) {
            "red" -> {
                game.setRedSpawnLocation(location)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.red-spawn-set", "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
            }
            "blue" -> {
                game.setBlueSpawnLocation(location)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.blue-spawn-set", "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
            }
            else -> {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.invalid-team"), NamedTextColor.RED))
                return true
            }
        }
        
        // 設定は新しいGameManagerでは自動保存される
        
        return true
    }
    
    private fun handleStatusCommand(sender: CommandSender, args: Array<String>): Boolean {
        val gameName = if (args.size >= 2) {
            args[1]
        } else if (sender is Player) {
            gameManager.getPlayerGame(sender)?.name
        } else {
            null
        }
        
        if (gameName == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-status"), NamedTextColor.YELLOW))
            return true
        }
        
        val game = gameManager.getGame(gameName)
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.status-header", "name" to gameName), NamedTextColor.GOLD))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.status-state", "state" to game.state.toString()), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.status-phase", "phase" to game.phase.toString()), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.status-red-team", "count" to game.redTeam.size.toString()), NamedTextColor.RED))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.status-blue-team", "count" to game.blueTeam.size.toString()), NamedTextColor.BLUE))
        
        if (game.state == GameState.RUNNING) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.status-score", "red" to (game.score[com.hacklab.ctf.utils.Team.RED] ?: 0).toString(), "blue" to (game.score[com.hacklab.ctf.utils.Team.BLUE] ?: 0).toString()), NamedTextColor.WHITE))
        }
        
        // マッチ情報（ある場合）
        val matchWrapper = gameManager.getMatch(gameName)
        if (matchWrapper != null && matchWrapper.isActive) {
            sender.sendMessage(Component.text("", NamedTextColor.WHITE))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.match-info-header"), NamedTextColor.GOLD))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.match-mode", "mode" to plugin.languageManager.getMessage("match.mode.${matchWrapper.config.matchMode.name.lowercase()}")), NamedTextColor.WHITE))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.match-progress", "status" to matchWrapper.getMatchStatus()), NamedTextColor.YELLOW))
            val wins = matchWrapper.matchWins
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.match-score", "red" to (wins[com.hacklab.ctf.utils.Team.RED] ?: 0).toString(), "blue" to (wins[com.hacklab.ctf.utils.Team.BLUE] ?: 0).toString()), NamedTextColor.WHITE))
        }
        
        return true
    }
    
    private fun handleInfoCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-info"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        // ゲーム設定情報を表示
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-header", "name" to gameName)).color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
        
        // 基本設定
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-basic-settings")).color(NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-min-players", "count" to game.minPlayers.toString()), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-max-players", "count" to game.maxPlayersPerTeam.toString()), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-world", "world" to game.world.name), NamedTextColor.WHITE))
        
        // フェーズ設定
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-phase-settings")).color(NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-build-phase", "seconds" to game.buildDuration.toString(), "minutes" to (game.buildDuration / 60).toString()), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-build-mode", "mode" to game.buildPhaseGameMode), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-combat-phase", "seconds" to game.combatDuration.toString(), "minutes" to (game.combatDuration / 60).toString()), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-result-phase", "seconds" to game.resultDuration.toString()), NamedTextColor.WHITE))
        
        // 位置設定
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-position-settings")).color(NamedTextColor.YELLOW))
        
        val redFlag = game.getRedFlagLocation()
        if (redFlag != null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-red-flag", "x" to redFlag.blockX.toString(), "y" to redFlag.blockY.toString(), "z" to redFlag.blockZ.toString()), NamedTextColor.RED))
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-red-flag-not-set"), NamedTextColor.RED))
        }
        
        // GameConfigを取得
        val config = gameManager.getGameConfig(gameName)
        
        // スポーン地点情報（複数対応）
        val redSpawnLocations = config?.getAllRedSpawnLocations() ?: emptyList()
        if (redSpawnLocations.isEmpty()) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-red-spawn-same"), NamedTextColor.RED))
        } else if (redSpawnLocations.size == 1) {
            val spawn = redSpawnLocations[0]
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-red-spawn", "x" to spawn.blockX.toString(), "y" to spawn.blockY.toString(), "z" to spawn.blockZ.toString()), NamedTextColor.RED))
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-red-spawn-multiple", "count" to redSpawnLocations.size.toString()), NamedTextColor.RED))
        }
        
        val blueFlag = game.getBlueFlagLocation()
        if (blueFlag != null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-blue-flag", "x" to blueFlag.blockX.toString(), "y" to blueFlag.blockY.toString(), "z" to blueFlag.blockZ.toString()), NamedTextColor.BLUE))
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-blue-flag-not-set"), NamedTextColor.BLUE))
        }
        
        // スポーン地点情報（複数対応）
        val blueSpawnLocations = config?.getAllBlueSpawnLocations() ?: emptyList()
        if (blueSpawnLocations.isEmpty()) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-blue-spawn-same"), NamedTextColor.BLUE))
        } else if (blueSpawnLocations.size == 1) {
            val spawn = blueSpawnLocations[0]
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-blue-spawn", "x" to spawn.blockX.toString(), "y" to spawn.blockY.toString(), "z" to spawn.blockZ.toString()), NamedTextColor.BLUE))
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-blue-spawn-multiple", "count" to blueSpawnLocations.size.toString()), NamedTextColor.BLUE))
        }
        
        // 通貨設定
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-currency-settings")).color(NamedTextColor.YELLOW))
        val initialCurrency = plugin.config.getInt("currency.initial", 50)
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-initial-currency", "amount" to initialCurrency.toString()), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-kill-reward"), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-capture-reward"), NamedTextColor.WHITE))
        
        // その他
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-other-settings")).color(NamedTextColor.YELLOW))
        val respawnBase = plugin.config.getInt("default-game.respawn-delay-base", 0)
        val respawnPerDeath = plugin.config.getInt("default-game.respawn-delay-per-death", 0)
        val respawnMax = plugin.config.getInt("default-game.respawn-delay-max", 0)
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-respawn-time", "base" to respawnBase.toString(), "perDeath" to respawnPerDeath.toString(), "max" to respawnMax.toString()), NamedTextColor.WHITE))
        
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.info-footer")).color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
        
        return true
    }
    
    private fun sendHelpMessage(sender: CommandSender) {
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-header"), NamedTextColor.GOLD))
        
        if (sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-create"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-update"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-delete"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-start"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-start-note1"), NamedTextColor.GRAY))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-start-note2"), NamedTextColor.GRAY))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-stop"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-setflag"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-setspawn"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-addspawn"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-removespawn"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-listspawns"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-setpos1"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-setpos2"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-savemap"), NamedTextColor.YELLOW))
            // 管理者用プレイヤー管理コマンド
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-addplayer"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-changeteam"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-resetstats"), NamedTextColor.YELLOW))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-balance"), NamedTextColor.YELLOW))
        }
        
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-list"), NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-join"), NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-leave"), NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-team"), NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-spectator"), NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-status"), NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-info"), NamedTextColor.YELLOW))
        // 統計コマンド
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-stats"), NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.help-leaderboard"), NamedTextColor.YELLOW))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        val completions = mutableListOf<String>()
        
        when (args.size) {
            1 -> {
                val subcommands = mutableListOf("list", "join", "leave", "team", "spectator", "status", "info", "stats", "leaderboard")
                if (sender.hasPermission("ctf.admin")) {
                    subcommands.addAll(listOf("create", "update", "delete", "start", "stop", "setflag", "setspawn", "setpos1", "setpos2", "savemap", 
                        "addspawn", "removespawn", "listspawns", "addplayer", "changeteam", "resetstats", "balance"))
                }
                completions.addAll(subcommands.filter { it.startsWith(args[0].lowercase()) })
            }
            2 -> {
                when (args[0].lowercase()) {
                    "update", "delete", "start", "stop", "join", "spectator", "status", "info", "setflag", "setspawn", 
                    "setpos1", "setpos2", "savemap", "balance", "addspawn", "removespawn", "listspawns", "addplayer" -> {
                        completions.addAll(gameManager.getAllGames().keys.filter { it.startsWith(args[1].lowercase()) })
                    }
                    "team" -> {
                        completions.addAll(listOf("red", "blue").filter { it.startsWith(args[1].lowercase()) })
                    }
                    "stats", "resetstats", "changeteam" -> {
                        completions.addAll(plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) })
                    }
                    "leaderboard" -> {
                        completions.addAll(listOf("score", "kills", "captures", "winrate").filter { it.startsWith(args[1].lowercase()) })
                    }
                }
            }
            3 -> {
                when (args[0].lowercase()) {
                    "setflag", "setspawn", "addspawn" -> {
                        completions.addAll(listOf("red", "blue").filter { it.startsWith(args[2].lowercase()) })
                    }
                    "addplayer" -> {
                        completions.addAll(plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) })
                    }
                    "changeteam" -> {
                        completions.addAll(listOf("red", "blue").filter { it.startsWith(args[2].lowercase()) })
                    }
                    "removespawn" -> {
                        completions.addAll(listOf("red", "blue").filter { it.startsWith(args[2].lowercase()) })
                    }
                    "start" -> {
                        completions.addAll(listOf("single", "match").filter { it.startsWith(args[2].lowercase()) })
                    }
                    "balance" -> {
                        completions.addAll(listOf("apply").filter { it.startsWith(args[2].lowercase()) })
                    }
                }
            }
            4 -> {
                when (args[0].lowercase()) {
                    "start" -> {
                        if (args[2].equals("match", ignoreCase = true)) {
                            completions.addAll(listOf("3", "5", "7", "10").filter { it.startsWith(args[3]) })
                        }
                    }
                    "addplayer" -> {
                        completions.addAll(listOf("red", "blue").filter { it.startsWith(args[3].lowercase()) })
                    }
                    "removespawn" -> {
                        // 番号の補完（1-10）
                        completions.addAll((1..10).map { it.toString() }.filter { it.startsWith(args[3]) })
                    }
                }
            }
        }
        
        return completions
    }
    
    private fun handleSetPos1Command(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        // プレイヤーの実際の位置を使用（Y座標も含む）
        val playerLoc = sender.location
        
        // ゲーム名なしで一時的な範囲として設定
        if (args.size < 2) {
            gameManager.setTempMapPos1(sender, playerLoc)
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.map-start-set", 
                "x" to playerLoc.blockX.toString(), 
                "y" to playerLoc.blockY.toString(), 
                "z" to playerLoc.blockZ.toString()), NamedTextColor.GREEN))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.map-temp-hint"), NamedTextColor.GRAY))
        } else {
            // ゲーム名ありの場合は既存のゲームに対して設定
            val gameName = args[1]
            gameManager.setMapPos1(gameName, playerLoc)
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.map-start-set-game", 
                "name" to gameName, 
                "x" to playerLoc.blockX.toString(), 
                "y" to playerLoc.blockY.toString(), 
                "z" to playerLoc.blockZ.toString()), NamedTextColor.GREEN))
        }
        
        return true
    }
    
    private fun handleSetPos2Command(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        // プレイヤーの実際の位置を使用（Y座標も含む）
        val playerLoc = sender.location
        
        // ゲーム名なしで一時的な範囲として設定
        if (args.size < 2) {
            gameManager.setTempMapPos2(sender, playerLoc)
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.map-end-set", 
                "x" to playerLoc.blockX.toString(), 
                "y" to playerLoc.blockY.toString(), 
                "z" to playerLoc.blockZ.toString()), NamedTextColor.GREEN))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.map-temp-hint"), NamedTextColor.GRAY))
        } else {
            // ゲーム名ありの場合は既存のゲームに対して設定
            val gameName = args[1]
            gameManager.setMapPos2(gameName, playerLoc)
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.map-end-set-game", 
                "name" to gameName, 
                "x" to playerLoc.blockX.toString(), 
                "y" to playerLoc.blockY.toString(), 
                "z" to playerLoc.blockZ.toString()), NamedTextColor.GREEN))
        }
        
        return true
    }
    
    private fun handleSaveMapCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-savemap"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        
        // WorldEditの選択範囲を確認
        val worldEditSelection = if (com.hacklab.ctf.utils.WorldEditHelper.isWorldEditAvailable()) {
            com.hacklab.ctf.utils.WorldEditHelper.getPlayerSelection(sender)
        } else null
        
        // プレイヤーの一時的なマップ範囲を確認
        val tempPositions = gameManager.getTempMapPositions(sender)
        val hasTempMapRegion = tempPositions?.pos1 != null && tempPositions.pos2 != null
        
        // WorldEditの選択範囲を優先的に使用
        if (worldEditSelection != null) {
            gameManager.setMapPos1(gameName, worldEditSelection.first)
            gameManager.setMapPos2(gameName, worldEditSelection.second)
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.using-worldedit"), NamedTextColor.GRAY))
        }
        // 一時的なマップ範囲がある場合
        else if (hasTempMapRegion && tempPositions != null) {
            gameManager.setMapPos1(gameName, tempPositions.pos1!!)
            gameManager.setMapPos2(gameName, tempPositions.pos2!!)
            gameManager.clearTempMapPositions(sender)
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.using-temp-region"), NamedTextColor.GRAY))
        }
        // ゲーム固有の範囲も確認が必要な場合があるため、そのまま実行
        
        val result = gameManager.saveMap(gameName)
        
        if (result.success) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.map-saved"), NamedTextColor.GREEN))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.detection-result"), NamedTextColor.AQUA))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.red-spawn-detected", "location" to (result.redSpawn ?: "unknown")), NamedTextColor.RED))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.blue-spawn-detected", "location" to (result.blueSpawn ?: "unknown")), NamedTextColor.BLUE))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.red-flag-detected", "location" to (result.redFlag ?: "unknown")), NamedTextColor.RED))
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.blue-flag-detected", "location" to (result.blueFlag ?: "unknown")), NamedTextColor.BLUE))
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.map-save-failed"), NamedTextColor.RED))
            result.errors.forEach { error ->
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("ui.error-item", "error" to error), NamedTextColor.YELLOW))
            }
        }
        
        return true
    }
    
    private fun handleAddSpawnCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.languageManager.getMessage("command.player-only"))
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 3) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-addspawn"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val team = args[2].lowercase()
        
        if (team !in listOf("red", "blue")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.invalid-team"), NamedTextColor.RED))
            return true
        }
        
        val config = gameManager.getGameConfig(gameName)
        if (config == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        val location = sender.location.clone()
        
        // 距離の検証
        val isRedTeam = team == "red"
        val validationError = config.validateSpawnDistance(location, isRedTeam)
        if (validationError != null) {
            sender.sendMessage(Component.text(validationError, NamedTextColor.RED))
            return true
        }
        
        // チームに応じてスポーン地点を追加
        when (team) {
            "red" -> {
                config.redSpawnLocations.add(location)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.red-spawn-added", "number" to config.redSpawnLocations.size.toString(), "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
            }
            "blue" -> {
                config.blueSpawnLocations.add(location)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.blue-spawn-added", "number" to config.blueSpawnLocations.size.toString(), "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.GREEN))
            }
        }
        
        // 設定を保存してゲームを更新
        gameManager.updateGameConfig(config)
        
        return true
    }
    
    private fun handleRemoveSpawnCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 4) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-removespawn"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val team = args[2].lowercase()
        val index = args[3].toIntOrNull()
        
        if (team !in listOf("red", "blue")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.invalid-team"), NamedTextColor.RED))
            return true
        }
        
        if (index == null || index < 1) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.invalid-spawn-number"), NamedTextColor.RED))
            return true
        }
        
        val config = gameManager.getGameConfig(gameName)
        if (config == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        // チームに応じてスポーン地点を削除
        when (team) {
            "red" -> {
                if (index > config.redSpawnLocations.size) {
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-not-found", "index" to index.toString()), NamedTextColor.RED))
                    return true
                }
                val removed = config.redSpawnLocations.removeAt(index - 1)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.red-spawn-removed", "index" to index.toString(), "x" to removed.blockX.toString(), "y" to removed.blockY.toString(), "z" to removed.blockZ.toString()), NamedTextColor.GREEN))
            }
            "blue" -> {
                if (index > config.blueSpawnLocations.size) {
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-not-found", "index" to index.toString()), NamedTextColor.RED))
                    return true
                }
                val removed = config.blueSpawnLocations.removeAt(index - 1)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.blue-spawn-removed", "index" to index.toString(), "x" to removed.blockX.toString(), "y" to removed.blockY.toString(), "z" to removed.blockZ.toString()), NamedTextColor.GREEN))
            }
        }
        
        // 設定を保存してゲームを更新
        gameManager.updateGameConfig(config)
        
        return true
    }
    
    private fun handleListSpawnsCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.usage-listspawns"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val config = gameManager.getGameConfig(gameName)
        
        if (config == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-list-header", "name" to gameName), NamedTextColor.GOLD))
        
        // 赤チームのスポーン地点
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.red-team-label"), NamedTextColor.RED))
        if (config.redSpawnLocation != null) {
            val loc = config.redSpawnLocation!!
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-legacy", "x" to loc.blockX.toString(), "y" to loc.blockY.toString(), "z" to loc.blockZ.toString()), NamedTextColor.GRAY))
        }
        config.redSpawnLocations.forEachIndexed { index, location ->
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-location", "number" to (index + 1).toString(), "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.WHITE))
        }
        if (config.redSpawnLocations.isEmpty() && config.redSpawnLocation == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-not-set"), NamedTextColor.GRAY))
        }
        
        // 青チームのスポーン地点
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.blue-team-label"), NamedTextColor.BLUE))
        if (config.blueSpawnLocation != null) {
            val loc = config.blueSpawnLocation!!
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-legacy", "x" to loc.blockX.toString(), "y" to loc.blockY.toString(), "z" to loc.blockZ.toString()), NamedTextColor.GRAY))
        }
        config.blueSpawnLocations.forEachIndexed { index, location ->
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-location", "number" to (index + 1).toString(), "x" to location.blockX.toString(), "y" to location.blockY.toString(), "z" to location.blockZ.toString()), NamedTextColor.WHITE))
        }
        if (config.blueSpawnLocations.isEmpty() && config.blueSpawnLocation == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.spawn-not-set"), NamedTextColor.GRAY))
        }
        
        return true
    }

    private fun handleAddPlayerCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("command.no-permission"))
            return true
        }
        
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /ctf addplayer <game> <player> [red|blue]", NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val targetPlayerName = args[2]
        val targetPlayer = plugin.server.getPlayer(targetPlayerName)
        
        if (targetPlayer == null) {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("admin.player-not-found", "player" to targetPlayerName))
            return true
        }
        
        val game = gameManager.getGame(gameName)
        if (game == null) {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("command.game-not-found", "name" to gameName))
            return true
        }
        
        // チーム指定があれば取得
        val team = if (args.size >= 4) {
            when (args[3].lowercase()) {
                "red" -> com.hacklab.ctf.utils.Team.RED
                "blue" -> com.hacklab.ctf.utils.Team.BLUE
                else -> null
            }
        } else null
        
        // 既存のゲームから削除
        val currentGame = gameManager.getPlayerGame(targetPlayer)
        if (currentGame != null) {
            gameManager.removePlayerFromGame(targetPlayer)
        }
        
        // 管理者権限でゲームに追加
        if (gameManager.addPlayerToGameAsAdmin(targetPlayer, gameName, team)) {
            // 成功メッセージ
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("admin.player-added", 
                "player" to targetPlayer.name, 
                "game" to gameName))
            
            // 対象プレイヤーに通知
            targetPlayer.sendMessage(plugin.languageManager.getMessageAsComponent("admin.force-joined", 
                "admin" to sender.name,
                "game" to gameName))
            
            // ゲーム内の他のプレイヤーに通知
            game.getAllPlayers().forEach { player ->
                if (player != targetPlayer) {
                    player.sendMessage(plugin.languageManager.getMessageAsComponent("admin.player-joined-by-admin",
                        "player" to targetPlayer.name,
                        "admin" to sender.name))
                }
            }
        } else {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("admin.add-player-failed"))
        }
        
        return true
    }
    
    private fun handleChangeTeamCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("command.no-permission"))
            return true
        }
        
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /ctf changeteam <player> <red|blue>", NamedTextColor.YELLOW))
            return true
        }
        
        val targetPlayerName = args[1]
        val targetPlayer = plugin.server.getPlayer(targetPlayerName)
        
        if (targetPlayer == null) {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("admin.player-not-found", "player" to targetPlayerName))
            return true
        }
        
        val game = gameManager.getPlayerGame(targetPlayer)
        if (game == null) {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("admin.player-not-in-game", "player" to targetPlayerName))
            return true
        }
        
        val newTeam = when (args[2].lowercase()) {
            "red" -> com.hacklab.ctf.utils.Team.RED
            "blue" -> com.hacklab.ctf.utils.Team.BLUE
            else -> {
                sender.sendMessage(Component.text("Invalid team. Use 'red' or 'blue'", NamedTextColor.RED))
                return true
            }
        }
        
        val currentTeam = game.getPlayerTeam(targetPlayer.uniqueId)
        if (currentTeam == newTeam) {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("admin.already-in-team", 
                "player" to targetPlayer.name,
                "team" to plugin.languageManager.getMessage("teams.${newTeam.name.lowercase()}")))
            return true
        }
        
        // チーム変更を実行
        if (game.changePlayerTeam(targetPlayer, newTeam, isAdminAction = true)) {
            // 成功メッセージ
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("admin.team-changed",
                "player" to targetPlayer.name,
                "team" to plugin.languageManager.getMessage("teams.${newTeam.name.lowercase()}")))
            
            // 対象プレイヤーに通知
            targetPlayer.sendMessage(plugin.languageManager.getMessageAsComponent("admin.force-team-changed",
                "admin" to sender.name,
                "team" to plugin.languageManager.getMessage("teams.${newTeam.name.lowercase()}")))
            
            // ゲーム内の他のプレイヤーに通知
            game.getAllPlayers().forEach { player ->
                if (player != targetPlayer) {
                    player.sendMessage(plugin.languageManager.getMessageAsComponent("admin.player-team-changed-by-admin",
                        "player" to targetPlayer.name,
                        "team" to plugin.languageManager.getMessage("teams.${newTeam.name.lowercase()}"),
                        "admin" to sender.name))
                }
            }
        } else {
            sender.sendMessage(plugin.languageManager.getMessageAsComponent("admin.change-team-failed"))
        }
        
        return true
    }
    
    private fun handleStatsCommand(sender: CommandSender, args: Array<String>): Boolean {
        val targetPlayerName = if (args.size >= 2) args[1] else sender.name
        val targetPlayer = plugin.server.getOfflinePlayer(targetPlayerName)
        
        if (!targetPlayer.hasPlayedBefore() && targetPlayer.name != sender.name) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.player-not-found", "player" to targetPlayerName), NamedTextColor.RED))
            return true
        }
        
        val stats = plugin.playerStatisticsManager.getPlayerStats(targetPlayer.uniqueId)
        val rank = plugin.playerStatisticsManager.getPlayerRank(targetPlayer.uniqueId)
        
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.header", "player" to (targetPlayer.name ?: targetPlayerName)), NamedTextColor.GOLD))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.rank", "rank" to rank.toString()), NamedTextColor.YELLOW))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.skill-score", "score" to String.format("%.1f", stats.calculateSkillScore())), NamedTextColor.AQUA))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.kills", "kills" to stats.totalKills.toString()), NamedTextColor.GREEN))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.deaths", "deaths" to stats.totalDeaths.toString()), NamedTextColor.RED))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.kd-ratio", "ratio" to String.format("%.2f", stats.getKDRatio())), NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.captures", "captures" to stats.totalCaptures.toString()), NamedTextColor.BLUE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.games-played", "games" to stats.gamesPlayed.toString()), NamedTextColor.GRAY))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.games-won", "games" to stats.gamesWon.toString()), NamedTextColor.GREEN))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.win-rate", "rate" to String.format("%.1f%%", stats.getWinRate())), NamedTextColor.YELLOW))
        
        return true
    }
    
    private fun handleLeaderboardCommand(sender: CommandSender, args: Array<String>): Boolean {
        val category = if (args.size >= 2) args[1].lowercase() else "score"
        val limit = if (args.size >= 3) args[2].toIntOrNull() ?: 10 else 10
        
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        
        when (category) {
            "score", "skill" -> {
                val topPlayers = plugin.playerStatisticsManager.getTopPlayersByScore(limit)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.score-header"), NamedTextColor.GOLD))
                topPlayers.forEachIndexed { index, (uuid, score) ->
                    val playerName = plugin.server.getOfflinePlayer(uuid).name ?: "Unknown"
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.score-entry", 
                        "rank" to (index + 1).toString(),
                        "player" to playerName,
                        "score" to String.format("%.1f", score)), NamedTextColor.WHITE))
                }
            }
            "kills" -> {
                val topPlayers = plugin.playerStatisticsManager.getTopPlayersByKills(limit)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.kills-header"), NamedTextColor.GOLD))
                topPlayers.forEachIndexed { index, (uuid, kills) ->
                    val playerName = plugin.server.getOfflinePlayer(uuid).name ?: "Unknown"
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.kills-entry",
                        "rank" to (index + 1).toString(),
                        "player" to playerName,
                        "kills" to kills.toString()), NamedTextColor.WHITE))
                }
            }
            "captures" -> {
                val topPlayers = plugin.playerStatisticsManager.getTopPlayersByCaptures(limit)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.captures-header"), NamedTextColor.GOLD))
                topPlayers.forEachIndexed { index, (uuid, captures) ->
                    val playerName = plugin.server.getOfflinePlayer(uuid).name ?: "Unknown"
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.captures-entry",
                        "rank" to (index + 1).toString(),
                        "player" to playerName,
                        "captures" to captures.toString()), NamedTextColor.WHITE))
                }
            }
            "winrate", "wins" -> {
                val topPlayers = plugin.playerStatisticsManager.getTopPlayersByWinRate(limit, 5)
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.winrate-header"), NamedTextColor.GOLD))
                topPlayers.forEachIndexed { index, (uuid, winRate) ->
                    val playerName = plugin.server.getOfflinePlayer(uuid).name ?: "Unknown"
                    val stats = plugin.playerStatisticsManager.getPlayerStats(uuid)
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.winrate-entry",
                        "rank" to (index + 1).toString(),
                        "player" to playerName,
                        "rate" to String.format("%.1f%%", winRate),
                        "games" to stats.gamesPlayed.toString()), NamedTextColor.WHITE))
                }
            }
            else -> {
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.usage"), NamedTextColor.YELLOW))
                sender.sendMessage(Component.text(plugin.languageManager.getMessage("leaderboard.categories"), NamedTextColor.GRAY))
                return true
            }
        }
        
        return true
    }
    
    private fun handleResetStatsCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.reset-usage"), NamedTextColor.YELLOW))
            return true
        }
        
        val targetPlayerName = args[1]
        val targetPlayer = plugin.server.getOfflinePlayer(targetPlayerName)
        
        if (!targetPlayer.hasPlayedBefore()) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.player-not-found", "player" to targetPlayerName), NamedTextColor.RED))
            return true
        }
        
        plugin.playerStatisticsManager.resetPlayerStats(targetPlayer.uniqueId)
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("stats.reset-success", "player" to (targetPlayer.name ?: targetPlayerName)), NamedTextColor.GREEN))
        
        return true
    }
    
    private fun handleBalanceCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.no-permission"), NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.usage"), NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        if (game.state != com.hacklab.ctf.utils.GameState.WAITING) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.cannot-balance-running"), NamedTextColor.RED))
            return true
        }
        
        val allPlayers = game.getAllPlayers().toList()
        if (allPlayers.size < 2) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.insufficient-players"), NamedTextColor.RED))
            return true
        }
        
        // 現在のバランス情報を表示
        val currentBalance = plugin.teamBalancer.getTeamBalanceInfo(game.redTeam.mapNotNull { plugin.server.getPlayer(it) }, game.blueTeam.mapNotNull { plugin.server.getPlayer(it) })
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.current-balance"), NamedTextColor.GOLD))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.red-team", 
            "size" to currentBalance["redTeamSize"].toString(),
            "score" to String.format("%.1f", currentBalance["redTeamScore"] as Double)), NamedTextColor.RED))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.blue-team",
            "size" to currentBalance["blueTeamSize"].toString(), 
            "score" to String.format("%.1f", currentBalance["blueTeamScore"] as Double)), NamedTextColor.BLUE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.score-difference",
            "diff" to String.format("%.1f", currentBalance["scoreDifference"] as Double)), NamedTextColor.YELLOW))
        
        // 新しいバランスを計算
        val balancedTeams = plugin.teamBalancer.balanceTeams(allPlayers)
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.proposed-balance"), NamedTextColor.GOLD))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.red-team",
            "size" to balancedTeams.redTeam.size.toString(),
            "score" to String.format("%.1f", balancedTeams.redTeamTotalScore)), NamedTextColor.RED))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.blue-team",
            "size" to balancedTeams.blueTeam.size.toString(),
            "score" to String.format("%.1f", balancedTeams.blueTeamTotalScore)), NamedTextColor.BLUE))
        sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.score-difference",
            "diff" to String.format("%.1f", balancedTeams.getScoreDifference())), NamedTextColor.YELLOW))
        
        // 実際にチーム再配置を実行するかどうかの確認
        if (args.size >= 3 && args[2].equals("apply", ignoreCase = true)) {
            // チームを再配置
            game.redTeam.clear()
            game.blueTeam.clear()
            
            balancedTeams.redTeam.forEach { player -> game.redTeam.add(player.uniqueId) }
            balancedTeams.blueTeam.forEach { player -> game.blueTeam.add(player.uniqueId) }
            
            // プレイヤーに通知
            game.getAllPlayers().forEach { player ->
                val newTeam = if (player.uniqueId in game.redTeam) "red" else "blue"
                player.sendMessage(Component.text(plugin.languageManager.getMessage("balance.team-assigned",
                    "team" to plugin.languageManager.getMessage("teams.$newTeam")), if (newTeam == "red") NamedTextColor.RED else NamedTextColor.BLUE))
                game.updatePlayerTabColor(player)
            }
            
            game.updateScoreboard()
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.applied"), NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("balance.apply-hint"), NamedTextColor.GRAY))
        }
        
        return true
    }

    private fun validateGameName(name: String): Boolean {
        return name.matches(Regex("[a-zA-Z0-9_]+")) && name.length <= 32 && name.lowercase() !in listOf("all", "list", "help")
    }
}

