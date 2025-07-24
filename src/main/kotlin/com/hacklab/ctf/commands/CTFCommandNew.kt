package com.hacklab.ctf.commands

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManagerNew
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
    
    private val gameManager = plugin.gameManager as GameManagerNew
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
            else -> {
                sendHelpMessage(sender)
                return true
            }
        }
    }

    private fun handleCreateCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます")
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません", NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text("使用方法: /ctf create <ゲーム名>", NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        if (gameManager.startGameCreation(sender, gameName)) {
            // 成功時のメッセージは GameManager 側で表示
        }
        
        return true
    }
    
    private fun handleUpdateCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます")
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません", NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text("使用方法: /ctf update <ゲーム名>", NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        if (!gameManager.startGameUpdate(sender, gameName)) {
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
        }
        
        return true
    }
    
    private fun handleDeleteCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません", NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text("使用方法: /ctf delete <ゲーム名>", NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        if (gameManager.deleteGame(gameName)) {
            sender.sendMessage(Component.text("ゲーム '$gameName' を削除しました", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
        }
        
        return true
    }
    
    private fun handleListCommand(sender: CommandSender): Boolean {
        val games = gameManager.getAllGames()
        
        if (games.isEmpty()) {
            sender.sendMessage(Component.text("現在ゲームはありません", NamedTextColor.YELLOW))
            return true
        }
        
        sender.sendMessage(Component.text("===== CTFゲーム一覧 =====", NamedTextColor.GOLD))
        
        games.entries.forEachIndexed { index, (name, game) ->
            val status = when {
                game.state == GameState.WAITING && game.getRedFlagLocation() == null -> "設定中"
                game.state == GameState.WAITING -> "待機中"
                game.state == GameState.RUNNING -> "実行中"
                else -> "終了中"
            }
            
            val redSize = game.redTeam.size
            val blueSize = game.blueTeam.size
            
            val statusColor = when (status) {
                "実行中" -> NamedTextColor.GREEN
                "待機中" -> NamedTextColor.YELLOW
                "設定中" -> NamedTextColor.GRAY
                else -> NamedTextColor.RED
            }
            
            sender.sendMessage(
                Component.text("${index + 1}. $name ", NamedTextColor.WHITE)
                    .append(Component.text("[$status]", statusColor))
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text("赤: ${redSize}名", NamedTextColor.RED))
                    .append(Component.text(", ", NamedTextColor.GRAY))
                    .append(Component.text("青: ${blueSize}名", NamedTextColor.BLUE))
            )
            
            if (game.getRedFlagLocation() == null || game.getBlueFlagLocation() == null) {
                sender.sendMessage(Component.text("   ⚠ 旗が未設定です", NamedTextColor.YELLOW))
            }
        }
        
        return true
    }
    
    private fun handleStartCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません", NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text("使用方法: /ctf start <ゲーム名> [モード] [目標値]", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("モード: first_to_x (先取) | fixed_rounds (固定ラウンド)", NamedTextColor.GRAY))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
            return true
        }
        
        // マッチパラメータの解析
        var matchMode: MatchMode? = null
        var matchTarget: Int? = null
        
        if (args.size >= 3) {
            // モード指定あり
            matchMode = when (args[2].lowercase()) {
                "first_to_x", "first-to-x", "firsttox" -> MatchMode.FIRST_TO_X
                "fixed_rounds", "fixed-rounds", "fixedrounds" -> MatchMode.FIXED_ROUNDS
                else -> {
                    sender.sendMessage(Component.text("無効なモード: ${args[2]}", NamedTextColor.RED))
                    sender.sendMessage(Component.text("使用可能: first_to_x, fixed_rounds", NamedTextColor.YELLOW))
                    return true
                }
            }
            
            if (args.size >= 4) {
                // 目標値指定あり
                matchTarget = args[3].toIntOrNull()
                if (matchTarget == null || matchTarget < 1) {
                    sender.sendMessage(Component.text("目標値は1以上の数値で指定してください", NamedTextColor.RED))
                    return true
                }
            }
        }
        
        // マッチシステムの使用判定
        if (matchMode != null || matchTarget != null) {
            // マッチパラメータが指定されている場合は必ずマッチシステムを使用
            val existingMatch = gameManager.getMatch(gameName)
            
            if (existingMatch != null && existingMatch.isActive) {
                sender.sendMessage(Component.text("既にマッチが実行中です", NamedTextColor.RED))
                return true
            }
            
            // 新しいマッチを作成または既存のマッチを更新
            val finalMode = matchMode ?: existingMatch?.mode ?: MatchMode.valueOf(plugin.config.getString("match.default-mode", "FIRST_TO_X")!!.uppercase())
            val finalTarget = matchTarget ?: existingMatch?.target ?: plugin.config.getInt("match.default-target", 3)
            
            // 新しいマッチを作成して開始
            gameManager.startMatchWithParams(gameName, finalMode, finalTarget)
            
            sender.sendMessage(Component.text("マッチ '$gameName' を開始しました！", NamedTextColor.GREEN))
            sender.sendMessage(Component.text("モード: ${finalMode.displayName} (${finalTarget})", NamedTextColor.YELLOW))
        } else {
            // パラメータ指定なしの場合
            val existingMatch = gameManager.getMatch(gameName)
            
            if (existingMatch != null) {
                // 既存のマッチがある場合はそれを使用
                existingMatch.startMatch(game)
                sender.sendMessage(Component.text("マッチ '$gameName' を開始しました！", NamedTextColor.GREEN))
                sender.sendMessage(Component.text("モード: ${existingMatch.mode.displayName} (${existingMatch.target})", NamedTextColor.YELLOW))
            } else {
                // 従来の単一ゲーム
                if (game.start()) {
                    sender.sendMessage(Component.text("ゲーム '$gameName' を開始しました！", NamedTextColor.GREEN))
                }
            }
        }
        
        return true
    }
    
    private fun handleStopCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません", NamedTextColor.RED))
            return true
        }
        
        if (args.size < 2) {
            sender.sendMessage(Component.text("使用方法: /ctf stop <ゲーム名>", NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        val match = gameManager.getMatch(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
            return true
        }
        
        if (match != null && match.isActive) {
            // マッチを停止
            match.stopMatch()
            sender.sendMessage(Component.text("マッチ '$gameName' を停止しました", NamedTextColor.GREEN))
        } else if (game.state != GameState.WAITING) {
            // 単一ゲームを停止
            game.stop()
            sender.sendMessage(Component.text("ゲーム '$gameName' を停止しました", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("ゲーム '$gameName' は実行されていません", NamedTextColor.RED))
        }
        
        return true
    }
    
    private fun handleJoinCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます")
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
                    sender.sendMessage(Component.text("ゲーム '$gameName' に参加しました", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("ゲームへの参加に失敗しました", NamedTextColor.RED))
                }
            } else {
                // 確認ダイアログ表示
                confirmations[sender] = ConfirmationData(gameName, System.currentTimeMillis())
                
                sender.sendMessage(Component.text("既に '${currentGame.name}' に参加中です。", NamedTextColor.YELLOW))
                sender.sendMessage(Component.text("退出して '$gameName' に参加しますか？", NamedTextColor.YELLOW))
                sender.sendMessage(Component.text("参加する場合は30秒以内にもう一度同じコマンドを実行してください。", NamedTextColor.GRAY))
                
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
                    sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
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
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます")
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
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます")
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
                sender.sendMessage(Component.text("チームは 'red' または 'blue' を指定してください", NamedTextColor.RED))
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
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます")
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません", NamedTextColor.RED))
            return true
        }
        
        if (args.size < 3) {
            sender.sendMessage(Component.text("使用方法: /ctf setflag <ゲーム名> <red|blue>", NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
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
                sender.sendMessage(Component.text("チームは 'red' または 'blue' を指定してください", NamedTextColor.RED))
                return true
            }
        }
        
        // 設定を保存
        gameManager.saveGame(gameName)
        
        return true
    }
    
    private fun handleSetSpawnCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます")
            return true
        }
        
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("このコマンドを実行する権限がありません", NamedTextColor.RED))
            return true
        }
        
        if (args.size < 3) {
            sender.sendMessage(Component.text("使用方法: /ctf setspawn <ゲーム名> <red|blue>", NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
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
                sender.sendMessage(Component.text("赤チームのスポーン地点を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            "blue" -> {
                game.setBlueSpawnLocation(location)
                sender.sendMessage(Component.text("青チームのスポーン地点を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            else -> {
                sender.sendMessage(Component.text("チームは 'red' または 'blue' を指定してください", NamedTextColor.RED))
                return true
            }
        }
        
        // 設定を保存
        gameManager.saveGame(gameName)
        
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
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
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
        val match = gameManager.getMatch(gameName)
        if (match != null && match.isActive) {
            sender.sendMessage(Component.text("", NamedTextColor.WHITE))
            sender.sendMessage(Component.text("=== マッチ情報 ===", NamedTextColor.GOLD))
            sender.sendMessage(Component.text("モード: ${match.mode.displayName}", NamedTextColor.WHITE))
            sender.sendMessage(Component.text("進行状況: ${match.getMatchStatus()}", NamedTextColor.YELLOW))
            val wins = match.getMatchWins()
            sender.sendMessage(Component.text("マッチスコア - 赤: ${wins[com.hacklab.ctf.utils.Team.RED]} 青: ${wins[com.hacklab.ctf.utils.Team.BLUE]}", NamedTextColor.WHITE))
        }
        
        return true
    }
    
    private fun sendHelpMessage(sender: CommandSender) {
        sender.sendMessage(Component.text("===== CTF コマンド =====", NamedTextColor.GOLD))
        
        if (sender.hasPermission("ctf.admin")) {
            sender.sendMessage(Component.text("/ctf create <ゲーム名> - 新規ゲーム作成（対話形式）", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf update <ゲーム名> - ゲーム設定の更新（対話形式）", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf delete <ゲーム名> - ゲーム削除", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf start <ゲーム名> - ゲーム開始", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf stop <ゲーム名> - ゲーム停止", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf setflag <ゲーム名> <red|blue> - 旗位置設定", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/ctf setspawn <ゲーム名> <red|blue> - スポーン地点設定", NamedTextColor.YELLOW))
        }
        
        sender.sendMessage(Component.text("/ctf list - ゲーム一覧表示", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf join <ゲーム名> - ゲーム参加", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf leave - ゲーム退出", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf team [red|blue] - チーム確認・変更", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("/ctf status [ゲーム名] - ゲーム状態確認", NamedTextColor.YELLOW))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        val completions = mutableListOf<String>()
        
        when (args.size) {
            1 -> {
                val subcommands = mutableListOf("list", "join", "leave", "team", "status")
                if (sender.hasPermission("ctf.admin")) {
                    subcommands.addAll(listOf("create", "update", "delete", "start", "stop", "setflag", "setspawn"))
                }
                completions.addAll(subcommands.filter { it.startsWith(args[0].lowercase()) })
            }
            2 -> {
                when (args[0].lowercase()) {
                    "update", "delete", "start", "stop", "join", "status", "setflag", "setspawn" -> {
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
                        completions.addAll(listOf("first_to_x", "fixed_rounds").filter { it.startsWith(args[2].lowercase()) })
                    }
                }
            }
            4 -> {
                when (args[0].lowercase()) {
                    "start" -> {
                        // 目標値の提案
                        if (args[2].lowercase() in listOf("first_to_x", "fixed_rounds")) {
                            completions.addAll(listOf("1", "3", "5", "7", "10").filter { it.startsWith(args[3]) })
                        }
                    }
                }
            }
        }
        
        return completions
    }
}

// GameManagerNew に saveGame メソッドを公開する拡張関数
fun GameManagerNew.saveGame(name: String) {
    val method = this::class.java.getDeclaredMethod("saveGame", String::class.java)
    method.isAccessible = true
    method.invoke(this, name)
}