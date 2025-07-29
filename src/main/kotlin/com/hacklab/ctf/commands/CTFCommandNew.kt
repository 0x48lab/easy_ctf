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
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

class CTFCommandNew(private val plugin: Main) : CommandExecutor, TabCompleter {
    
    private val gameManager = plugin.gameManager as GameManager
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
            "setflag" -> return handleSetFlagCommand(sender, args)
            "setspawn" -> return handleSetSpawnCommand(sender, args)
            "status" -> return handleStatusCommand(sender, args)
            "info" -> return handleInfoCommand(sender, args)
            // マップ関連コマンド
            "setpos1" -> return handleSetPos1Command(sender, args)
            "setpos2" -> return handleSetPos2Command(sender, args)
            "savemap" -> return handleSaveMapCommand(sender, args)
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
            sender.sendMessage(Component.text("使用方法: /ctf create <ゲーム名>", NamedTextColor.YELLOW))
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
            sender.sendMessage(Component.text("使用方法: /ctf update <ゲーム名>", NamedTextColor.YELLOW))
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
            sender.sendMessage(Component.text("使用方法: /ctf delete <ゲーム名>", NamedTextColor.YELLOW))
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
                Component.text("${index + 1}. $name ", NamedTextColor.WHITE)
                    .append(Component.text("[$status]", statusColor))
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text(plugin.languageManager.getMessage("command.list-red-count", "count" to redSize.toString()), NamedTextColor.RED))
                    .append(Component.text(", ", NamedTextColor.GRAY))
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
                    sender.sendMessage(Component.text("使用方法: /ctf start <ゲーム名> [single|match] [ゲーム数]", NamedTextColor.RED))
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
            sender.sendMessage(Component.text("使用方法: /ctf stop <ゲーム名>", NamedTextColor.YELLOW))
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
            // マッチを停止
            game.stop()
            matchWrapper.isActive = false
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.match-stopped", "name" to gameName), NamedTextColor.GREEN))
        } else if (game.state != GameState.WAITING) {
            // 単一ゲームを停止
            game.stop()
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
            sender.sendMessage(Component.text("使用方法: /ctf join <ゲーム名>", NamedTextColor.YELLOW))
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
                    sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
                } else if (game.state != GameState.WAITING) {
                    sender.sendMessage(Component.text("ゲームが${game.state}状態のため参加できません", NamedTextColor.RED))
                } else if (game.redTeam.size + game.blueTeam.size >= game.maxPlayersPerTeam * 2) {
                    sender.sendMessage(Component.text("ゲームが満員です（最大${game.maxPlayersPerTeam * 2}名）", NamedTextColor.RED))
                } else {
                    sender.sendMessage(Component.text("参加に失敗しました", NamedTextColor.RED))
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
            sender.sendMessage(Component.text("現在ゲームに参加していません", NamedTextColor.RED))
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
            sender.sendMessage(Component.text("現在ゲームに参加していません", NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            // 現在のチームを表示
            val currentTeam = currentGame.getPlayerTeam(sender.uniqueId)
            if (currentTeam != null) {
                sender.sendMessage(Component.text("現在のチーム: ${currentTeam.displayName}", currentTeam.color))
                sender.sendMessage(Component.text("チーム変更: /ctf team <red|blue>", NamedTextColor.GRAY))
            }
            return true
        }
        
        // ゲームが既に開始している場合は変更不可
        if (currentGame.state != com.hacklab.ctf.utils.GameState.WAITING) {
            sender.sendMessage(Component.text("ゲーム開始後はチーム変更できません", NamedTextColor.RED))
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
            sender.sendMessage(Component.text("既に${newTeam.displayName}に所属しています", newTeam.color))
            return true
        }
        
        // チーム人数チェック
        val targetTeamSize = if (newTeam == com.hacklab.ctf.utils.Team.RED) currentGame.redTeam.size else currentGame.blueTeam.size
        if (targetTeamSize >= currentGame.maxPlayersPerTeam) {
            sender.sendMessage(Component.text("${newTeam.displayName}は満員です（最大${currentGame.maxPlayersPerTeam}名）", NamedTextColor.RED))
            return true
        }
        
        // チーム変更処理
        if (currentTeam != null) {
            // 現在のチームから削除
            when (currentTeam) {
                com.hacklab.ctf.utils.Team.RED -> currentGame.redTeam.remove(sender.uniqueId)
                com.hacklab.ctf.utils.Team.BLUE -> currentGame.blueTeam.remove(sender.uniqueId)
            }
        }
        
        // 新しいチームに追加
        when (newTeam) {
            com.hacklab.ctf.utils.Team.RED -> currentGame.redTeam.add(sender.uniqueId)
            com.hacklab.ctf.utils.Team.BLUE -> currentGame.blueTeam.add(sender.uniqueId)
        }
        
        // メッセージ送信
        sender.sendMessage(Component.text("${newTeam.displayName}に変更しました！", newTeam.color))
        
        // 他のプレイヤーに通知
        currentGame.getAllPlayers().forEach { player ->
            if (player != sender) {
                player.sendMessage(Component.text("${sender.name}が${newTeam.displayName}に移動しました", NamedTextColor.YELLOW))
            }
        }
        
        // スコアボード更新
        currentGame.updateScoreboard()
        
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
            sender.sendMessage(Component.text("使用方法: /ctf setflag <ゲーム名> <red|blue>", NamedTextColor.YELLOW))
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
            sender.sendMessage(Component.text("視線の先にブロックが見つかりません", NamedTextColor.RED))
            return true
        }
        
        val location = targetBlock.location.add(0.5, 1.0, 0.5)
        location.yaw = sender.location.yaw
        location.pitch = 0f
        
        when (args[2].lowercase()) {
            "red" -> {
                game.setRedFlagLocation(location)
                sender.sendMessage(Component.text("赤チームの旗位置を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            "blue" -> {
                game.setBlueFlagLocation(location)
                sender.sendMessage(Component.text("青チームの旗位置を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
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
            sender.sendMessage(Component.text("使用方法: /ctf setspawn <ゲーム名> <red|blue>", NamedTextColor.YELLOW))
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
            sender.sendMessage(Component.text("視線の先にブロックが見つかりません", NamedTextColor.RED))
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
            sender.sendMessage(Component.text("使用方法: /ctf status [ゲーム名]", NamedTextColor.YELLOW))
            return true
        }
        
        val game = gameManager.getGame(gameName)
        if (game == null) {
            sender.sendMessage(Component.text(plugin.languageManager.getMessage("command.game-not-found", "name" to gameName), NamedTextColor.RED))
            return true
        }
        
        sender.sendMessage(Component.text("=== ゲーム: $gameName ===", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("状態: ${game.state}", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("フェーズ: ${game.phase}", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("赤チーム: ${game.redTeam.size}名", NamedTextColor.RED))
        sender.sendMessage(Component.text("青チーム: ${game.blueTeam.size}名", NamedTextColor.BLUE))
        
        if (game.state == GameState.RUNNING) {
            sender.sendMessage(Component.text("スコア - 赤: ${game.score[com.hacklab.ctf.utils.Team.RED]} 青: ${game.score[com.hacklab.ctf.utils.Team.BLUE]}", NamedTextColor.WHITE))
        }
        
        // マッチ情報（ある場合）
        val matchWrapper = gameManager.getMatch(gameName)
        if (matchWrapper != null && matchWrapper.isActive) {
            sender.sendMessage(Component.text("", NamedTextColor.WHITE))
            sender.sendMessage(Component.text("=== マッチ情報 ===", NamedTextColor.GOLD))
            sender.sendMessage(Component.text("モード: ${matchWrapper.config.matchMode.displayName}", NamedTextColor.WHITE))
            sender.sendMessage(Component.text("進行状況: ${matchWrapper.getMatchStatus()}", NamedTextColor.YELLOW))
            val wins = matchWrapper.matchWins
            sender.sendMessage(Component.text("マッチスコア - 赤: ${wins[com.hacklab.ctf.utils.Team.RED]} 青: ${wins[com.hacklab.ctf.utils.Team.BLUE]}", NamedTextColor.WHITE))
        }
        
        return true
    }
    
    private fun handleInfoCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(Component.text("使用方法: /ctf info <ゲーム名>", NamedTextColor.YELLOW))
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
        sender.sendMessage(Component.text("===== ゲーム設定: $gameName =====").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
        
        // 基本設定
        sender.sendMessage(Component.text("【基本設定】").color(NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("最小プレイヤー数: ${game.minPlayers}人", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("チーム最大人数: ${game.maxPlayersPerTeam}人", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("ワールド: ${game.world.name}", NamedTextColor.WHITE))
        
        // フェーズ設定
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("【フェーズ設定】").color(NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("建築フェーズ: ${game.buildDuration}秒 (${game.buildDuration / 60}分)", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("建築時ゲームモード: ${game.buildPhaseGameMode}", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("戦闘フェーズ: ${game.combatDuration}秒 (${game.combatDuration / 60}分)", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("作戦会議フェーズ: ${game.resultDuration}秒", NamedTextColor.WHITE))
        
        // 位置設定
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("【位置設定】").color(NamedTextColor.YELLOW))
        
        val redFlag = game.getRedFlagLocation()
        if (redFlag != null) {
            sender.sendMessage(Component.text("赤旗: X:${redFlag.blockX} Y:${redFlag.blockY} Z:${redFlag.blockZ}", NamedTextColor.RED))
        } else {
            sender.sendMessage(Component.text("赤旗: 未設定", NamedTextColor.RED))
        }
        
        val redSpawn = game.getRedSpawnLocation()
        if (redSpawn != null) {
            sender.sendMessage(Component.text("赤スポーン: X:${redSpawn.blockX} Y:${redSpawn.blockY} Z:${redSpawn.blockZ}", NamedTextColor.RED))
        } else {
            sender.sendMessage(Component.text("赤スポーン: 旗位置と同じ", NamedTextColor.RED))
        }
        
        val blueFlag = game.getBlueFlagLocation()
        if (blueFlag != null) {
            sender.sendMessage(Component.text("青旗: X:${blueFlag.blockX} Y:${blueFlag.blockY} Z:${blueFlag.blockZ}", NamedTextColor.BLUE))
        } else {
            sender.sendMessage(Component.text("青旗: 未設定", NamedTextColor.BLUE))
        }
        
        val blueSpawn = game.getBlueSpawnLocation()
        if (blueSpawn != null) {
            sender.sendMessage(Component.text("青スポーン: X:${blueSpawn.blockX} Y:${blueSpawn.blockY} Z:${blueSpawn.blockZ}", NamedTextColor.BLUE))
        } else {
            sender.sendMessage(Component.text("青スポーン: 旗位置と同じ", NamedTextColor.BLUE))
        }
        
        // 通貨設定
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("【通貨設定】").color(NamedTextColor.YELLOW))
        val initialCurrency = plugin.config.getInt("currency.initial", 50)
        sender.sendMessage(Component.text("初期資金: ${initialCurrency}G", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("キル報酬: 10G (旗キャリア: 20G)", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("キャプチャー報酬: 30G", NamedTextColor.WHITE))
        
        // その他
        sender.sendMessage(Component.text("", NamedTextColor.WHITE))
        sender.sendMessage(Component.text("【その他】").color(NamedTextColor.YELLOW))
        val respawnBase = plugin.config.getInt("default-game.respawn-delay-base", 10)
        val respawnPerDeath = plugin.config.getInt("default-game.respawn-delay-per-death", 2)
        val respawnMax = plugin.config.getInt("default-game.respawn-delay-max", 20)
        sender.sendMessage(Component.text("リスポーン時間: ${respawnBase}秒 (+${respawnPerDeath}秒/死亡, 最大${respawnMax}秒)", NamedTextColor.WHITE))
        
        sender.sendMessage(Component.text("================================").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
        
        return true
    }
    
    private fun sendHelpMessage(sender: CommandSender) {
        sender.sendMessage(Component.text("===== CTF コマンド =====", NamedTextColor.GOLD))
        
        if (sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("/ctf create <ゲーム名> - 新規ゲーム作成（対話形式）", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf update <ゲーム名> - ゲーム設定の更新（対話形式）", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf delete <ゲーム名> - ゲーム削除", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf start <ゲーム名> [single|match] [ゲーム数] - ゲーム開始", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("  ※設定ファイルにマッチ設定がある場合は自動でマッチモード", NamedTextColor.GRAY))
            sender.sendMessage(Component.text("  ※'single'を指定すると強制的に単一ゲーム", NamedTextColor.GRAY))
            sender.sendMessage(Component.text("/ctf stop <ゲーム名> - ゲーム停止", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf setflag <ゲーム名> <red|blue> - 旗位置設定", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf setspawn <ゲーム名> <red|blue> - スポーン地点設定", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf setpos1 [ゲーム名] - マップ領域の始点を設定", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf setpos2 [ゲーム名] - マップ領域の終点を設定", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf savemap <ゲーム名> - マップを保存（自動検出）", NamedTextColor.YELLOW))
        }
        
        sender.sendMessage(Component.text("/ctf list - ゲーム一覧表示", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf join <ゲーム名> - ゲーム参加", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf leave - ゲーム退出", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf team [red|blue] - チーム確認・変更", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf status [ゲーム名] - ゲーム状態確認", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf info <ゲーム名> - ゲーム設定の詳細表示", NamedTextColor.YELLOW))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        val completions = mutableListOf<String>()
        
        when (args.size) {
            1 -> {
                val subcommands = mutableListOf("list", "join", "leave", "team", "status", "info")
                if (sender.hasPermission("ctf.admin")) {
                    subcommands.addAll(listOf("create", "update", "delete", "start", "stop", "setflag", "setspawn", "setpos1", "setpos2", "savemap"))
                }
                completions.addAll(subcommands.filter { it.startsWith(args[0].lowercase()) })
            }
            2 -> {
                when (args[0].lowercase()) {
                    "update", "delete", "start", "stop", "join", "status", "info", "setflag", "setspawn", "setpos1", "setpos2", "savemap" -> {
                        completions.addAll(gameManager.getAllGames().keys.filter { it.startsWith(args[1].lowercase()) })
                    }
                    "team" -> {
                        completions.addAll(listOf("red", "blue").filter { it.startsWith(args[1].lowercase()) })
                    }
                }
            }
            3 -> {
                when (args[0].lowercase()) {
                    "setflag", "setspawn" -> {
                        completions.addAll(listOf("red", "blue").filter { it.startsWith(args[2].lowercase()) })
                    }
                    "start" -> {
                        completions.addAll(listOf("single", "match").filter { it.startsWith(args[2].lowercase()) })
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
        
        // ゲーム名なしで一時的な範囲として設定
        if (args.size < 2) {
            gameManager.setTempMapPos1(sender, sender.location)
            sender.sendMessage(Component.text("マップ始点を設定しました: ${sender.location.blockX}, ${sender.location.blockY}, ${sender.location.blockZ}", NamedTextColor.GREEN))
            sender.sendMessage(Component.text("この範囲は次の /ctf create で使用されます", NamedTextColor.GRAY))
        } else {
            // ゲーム名ありの場合は既存のゲームに対して設定
            val gameName = args[1]
            gameManager.setMapPos1(gameName, sender.location)
            sender.sendMessage(Component.text("ゲーム $gameName の始点を設定しました: ${sender.location.blockX}, ${sender.location.blockY}, ${sender.location.blockZ}", NamedTextColor.GREEN))
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
        
        // ゲーム名なしで一時的な範囲として設定
        if (args.size < 2) {
            gameManager.setTempMapPos2(sender, sender.location)
            sender.sendMessage(Component.text("マップ終点を設定しました: ${sender.location.blockX}, ${sender.location.blockY}, ${sender.location.blockZ}", NamedTextColor.GREEN))
            sender.sendMessage(Component.text("この範囲は次の /ctf create で使用されます", NamedTextColor.GRAY))
        } else {
            // ゲーム名ありの場合は既存のゲームに対して設定
            val gameName = args[1]
            gameManager.setMapPos2(gameName, sender.location)
            sender.sendMessage(Component.text("ゲーム $gameName の終点を設定しました: ${sender.location.blockX}, ${sender.location.blockY}, ${sender.location.blockZ}", NamedTextColor.GREEN))
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
            sender.sendMessage(Component.text("使用方法: /ctf savemap <ゲーム名>", NamedTextColor.YELLOW))
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
            sender.sendMessage(Component.text("WorldEditの選択範囲を使用します", NamedTextColor.GRAY))
        }
        // 一時的なマップ範囲がある場合
        else if (hasTempMapRegion && tempPositions != null) {
            gameManager.setMapPos1(gameName, tempPositions.pos1!!)
            gameManager.setMapPos2(gameName, tempPositions.pos2!!)
            gameManager.clearTempMapPositions(sender)
            sender.sendMessage(Component.text("一時的なマップ範囲を使用します", NamedTextColor.GRAY))
        }
        // ゲーム固有の範囲も確認が必要な場合があるため、そのまま実行
        
        val result = gameManager.saveMap(gameName)
        
        if (result.success) {
            sender.sendMessage(Component.text("マップを正常に保存しました！", NamedTextColor.GREEN))
            sender.sendMessage(Component.text("検出結果:", NamedTextColor.AQUA))
            sender.sendMessage(Component.text("- 赤チームスポーン: ${result.redSpawn}", NamedTextColor.RED))
            sender.sendMessage(Component.text("- 青チームスポーン: ${result.blueSpawn}", NamedTextColor.BLUE))
            sender.sendMessage(Component.text("- 赤チーム旗: ${result.redFlag}", NamedTextColor.RED))
            sender.sendMessage(Component.text("- 青チーム旗: ${result.blueFlag}", NamedTextColor.BLUE))
        } else {
            sender.sendMessage(Component.text("マップの保存に失敗しました", NamedTextColor.RED))
            result.errors.forEach { error ->
                sender.sendMessage(Component.text("- $error", NamedTextColor.YELLOW))
            }
        }
        
        return true
    }
    
    private fun validateGameName(name: String): Boolean {
        return name.matches(Regex("[a-zA-Z0-9_]+")) && name.length <= 32 && name.lowercase() !in listOf("all", "list", "help")
    }
}

