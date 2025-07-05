package com.hacklab.ctf.commands

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManagerNew
import com.hacklab.ctf.utils.GameState
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
                game.state == GameState.WAITING && game.redFlagLocation == null -> "設定中"
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
            
            if (game.redFlagLocation == null || game.blueFlagLocation == null) {
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
            sender.sendMessage(Component.text("使用方法: /ctf start <ゲーム名>", NamedTextColor.YELLOW))
            return true
        }
        
        val gameName = args[1]
        val game = gameManager.getGame(gameName)
        
        if (game == null) {
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
            return true
        }
        
        if (game.start()) {
            sender.sendMessage(Component.text("ゲーム '$gameName' を開始しました！", NamedTextColor.GREEN))
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
        
        if (game == null) {
            sender.sendMessage(Component.text("ゲーム '$gameName' が見つかりません", NamedTextColor.RED))
            return true
        }
        
        if (game.state == GameState.WAITING) {
            sender.sendMessage(Component.text("ゲーム '$gameName' は実行されていません", NamedTextColor.RED))
            return true
        }
        
        game.stop()
        sender.sendMessage(Component.text("ゲーム '$gameName' を停止しました", NamedTextColor.GREEN))
        
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
                game.redFlagLocation = location
                sender.sendMessage(Component.text("赤チームの旗位置を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            "blue" -> {
                game.blueFlagLocation = location
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
                game.redSpawnLocation = location
                sender.sendMessage(Component.text("赤チームのスポーン地点を設定しました: ${location.blockX}, ${location.blockY}, ${location.blockZ}", NamedTextColor.GREEN))
            }
            "blue" -> {
                game.blueSpawnLocation = location
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
        sender.sendMessage(Component.text("/ctf status [ゲーム名] - ゲーム状態確認", NamedTextColor.YELLOW))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        val completions = mutableListOf<String>()
        
        when (args.size) {
            1 -> {
                val subcommands = mutableListOf("list", "join", "leave", "status")
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
                }
            }
            3 -> {
                when (args[0].lowercase()) {
                    "setflag", "setspawn" -> {
                        completions.addAll(listOf("red", "blue").filter { it.startsWith(args[2].lowercase()) })
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