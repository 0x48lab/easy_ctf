package com.hacklab.ctf.commands

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManager
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.Team
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CTFCommand(private val plugin: Main) : CommandExecutor, TabCompleter {
    
    private val gameManager: GameManager = plugin.gameManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sendHelpMessage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "start" -> return handleStartCommand(sender)
            "stop" -> return handleStopCommand(sender)
            "join" -> return handleJoinCommand(sender, args)
            "leave" -> return handleLeaveCommand(sender)
            "setflag" -> return handleSetFlagCommand(sender, args)
            "setspawn" -> return handleSetSpawnCommand(sender, args)
            "setteam" -> return handleSetTeamCommand(sender, args)
            "status" -> return handleStatusCommand(sender)
            else -> {
                sendHelpMessage(sender)
                return true
            }
        }
    }

    private fun handleStartCommand(sender: CommandSender): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to start the game!")
            return true
        }

        if (gameManager.getGameState() != GameState.WAITING) {
            sender.sendMessage("${ChatColor.RED}Game is already running or ending!")
            return true
        }

        gameManager.startGame()
        sender.sendMessage("${ChatColor.GREEN}CTF Game started!")
        return true
    }

    private fun handleStopCommand(sender: CommandSender): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to stop the game!")
            return true
        }

        if (gameManager.getGameState() == GameState.WAITING) {
            sender.sendMessage("${ChatColor.RED}No game is currently running!")
            return true
        }

        gameManager.stopGame()
        sender.sendMessage("${ChatColor.GREEN}CTF Game stopped!")
        return true
    }

    private fun handleJoinCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}Only players can join teams!")
            return true
        }

        if (args.size < 2) {
            // チーム指定がない場合は現在のチーム人数を表示
            val (redSize, blueSize) = gameManager.getTeamSizes()
            val maxPlayersPerTeam = plugin.config.getInt("game.max-players-per-team", 10)
            
            sender.sendMessage(plugin.languageManager.getTeamsMessage("team-status-title"))
            sender.sendMessage(plugin.languageManager.getTeamsMessage("red-team-size", 
                "size" to redSize.toString(), 
                "max" to maxPlayersPerTeam.toString()
            ))
            sender.sendMessage(plugin.languageManager.getTeamsMessage("blue-team-size", 
                "size" to blueSize.toString(), 
                "max" to maxPlayersPerTeam.toString()
            ))
            sender.sendMessage("${ChatColor.GRAY}Usage: /ctf join <red|blue>")
            return true
        }

        val team = try {
            Team.valueOf(args[1].uppercase())
        } catch (e: IllegalArgumentException) {
            sender.sendMessage("${ChatColor.RED}Invalid team! Use 'red' or 'blue'")
            return true
        }

        if (gameManager.getGameState() == GameState.RUNNING) {
            sender.sendMessage("${ChatColor.RED}Cannot join teams while game is running!")
            return true
        }

        gameManager.joinTeam(sender, team)
        return true
    }

    private fun handleLeaveCommand(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}Only players can leave teams!")
            return true
        }

        val currentTeam = gameManager.getPlayerTeam(sender)
        if (currentTeam == null) {
            sender.sendMessage("${ChatColor.RED}You are not on any team!")
            return true
        }

        gameManager.leaveTeam(sender)
        return true
    }

    private fun handleSetFlagCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}Only players can set flag locations!")
            return true
        }

        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to set flag locations!")
            return true
        }

        if (args.size < 2) {
            sender.sendMessage("${ChatColor.RED}Usage: /ctf setflag <red|blue>")
            return true
        }

        val team = try {
            Team.valueOf(args[1].uppercase())
        } catch (e: IllegalArgumentException) {
            sender.sendMessage("${ChatColor.RED}Invalid team! Use 'red' or 'blue'")
            return true
        }

        gameManager.setFlagBase(team, sender.location)
        val teamColor = if (team == Team.RED) "${ChatColor.RED}RED" else "${ChatColor.BLUE}BLUE"
        sender.sendMessage("${ChatColor.GREEN}Flag base for $teamColor${ChatColor.GREEN} team set at your location!")
        return true
    }

    private fun handleSetSpawnCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}Only players can set spawn locations!")
            return true
        }

        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to set spawn locations!")
            return true
        }

        if (args.size < 2) {
            sender.sendMessage("${ChatColor.RED}Usage: /ctf setspawn <red|blue>")
            return true
        }

        val team = try {
            Team.valueOf(args[1].uppercase())
        } catch (e: IllegalArgumentException) {
            sender.sendMessage("${ChatColor.RED}Invalid team! Use 'red' or 'blue'")
            return true
        }

        gameManager.setTeamSpawn(team, sender.location)
        val teamColor = if (team == Team.RED) "${ChatColor.RED}RED" else "${ChatColor.BLUE}BLUE"
        sender.sendMessage("${ChatColor.GREEN}Spawn for $teamColor${ChatColor.GREEN} team set at your location!")
        return true
    }

    private fun handleStatusCommand(sender: CommandSender): Boolean {
        val (redSize, blueSize) = gameManager.getTeamSizes()
        val maxPlayersPerTeam = plugin.config.getInt("game.max-players-per-team", 10)
        val gameState = gameManager.getGameState()
        val gameStateText = when (gameState) {
            GameState.WAITING -> plugin.languageManager.getGameStateMessage("waiting")
            GameState.STARTING -> plugin.languageManager.getGameStateMessage("starting")
            GameState.RUNNING -> plugin.languageManager.getGameStateMessage("running")
            GameState.ENDING -> plugin.languageManager.getGameStateMessage("ending")
        }
        
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("status-title"))
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("status-game-state", 
            "color" to getGameStateColor().toString(), 
            "state" to gameStateText
        ))
        
        if (gameManager.getGameState() == GameState.RUNNING) {
            val phaseText = when (gameManager.getCurrentPhase()) {
                GamePhase.BUILD -> plugin.languageManager.getUIMessage("phase-build")
                GamePhase.COMBAT -> plugin.languageManager.getUIMessage("phase-combat")  
                GamePhase.RESULT -> plugin.languageManager.getUIMessage("phase-result")
            }
            sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("status-current-phase", "phase" to phaseText))
        }
        
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("status-team-sizes"))
        sender.sendMessage("  " + plugin.languageManager.getTeamsMessage("red-team-size", 
            "size" to redSize.toString(), 
            "max" to maxPlayersPerTeam.toString()
        ))
        sender.sendMessage("  " + plugin.languageManager.getTeamsMessage("blue-team-size", 
            "size" to blueSize.toString(), 
            "max" to maxPlayersPerTeam.toString()
        ))
        
        if (sender is Player) {
            val team = gameManager.getPlayerTeam(sender)
            val teamText = when (team) {
                null -> "${ChatColor.GRAY}None"
                Team.RED -> "${ChatColor.RED}RED"
                Team.BLUE -> "${ChatColor.BLUE}BLUE"
            }
            sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("status-your-team", "team" to teamText))
        }
        
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("status-footer"))
        return true
    }

    private fun sendHelpMessage(sender: CommandSender) {
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-title"))
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-join-show"))
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-join-team"))
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-leave"))
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-status"))
        
        if (sender.hasPermission("ctf.admin")) {
            sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-admin-title"))
            sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-start"))
            sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-stop"))
            sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-setflag"))
            sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-setspawn"))
            sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-setteam"))
        }
        
        sender.sendMessage(plugin.languageManager.getCommandExtendedMessage("help-footer"))
    }

    private fun handleSetTeamCommand(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("ctf.admin")) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to set player teams!")
            return true
        }

        if (args.size < 3) {
            sender.sendMessage("${ChatColor.RED}Usage: /ctf setteam <player> <red|blue>")
            return true
        }

        val targetPlayerName = args[1]
        val teamName = args[2].lowercase()

        val targetPlayer = Bukkit.getPlayer(targetPlayerName)
        if (targetPlayer == null) {
            sender.sendMessage("${ChatColor.RED}Player '$targetPlayerName' not found!")
            return true
        }

        val team = when (teamName) {
            "red" -> Team.RED
            "blue" -> Team.BLUE
            else -> {
                sender.sendMessage("${ChatColor.RED}Invalid team! Use 'red' or 'blue'")
                return true
            }
        }

        val success = gameManager.setPlayerTeam(targetPlayer, team)
        if (success) {
            val teamColor = if (team == Team.RED) ChatColor.RED else ChatColor.BLUE
            val teamDisplayName = if (team == Team.RED) "Red" else "Blue"
            
            sender.sendMessage("${ChatColor.GREEN}Successfully set ${targetPlayer.name} to ${teamColor}${teamDisplayName} Team${ChatColor.GREEN}!")
            targetPlayer.sendMessage("${ChatColor.YELLOW}An administrator has set you to the ${teamColor}${teamDisplayName} Team${ChatColor.YELLOW}!")
        } else {
            sender.sendMessage("${ChatColor.RED}Failed to set team for ${targetPlayer.name}. The team might be full.")
        }

        return true
    }

    private fun getGameStateColor(): ChatColor {
        return when (gameManager.getGameState()) {
            GameState.WAITING -> ChatColor.YELLOW
            GameState.STARTING -> ChatColor.GOLD
            GameState.RUNNING -> ChatColor.GREEN
            GameState.ENDING -> ChatColor.RED
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>
    ): List<String> {
        val completions = mutableListOf<String>()

        when (args.size) {
            1 -> {
                val commands = if (sender.hasPermission("ctf.admin")) {
                    listOf("start", "stop", "join", "leave", "setflag", "setspawn", "setteam", "status")
                } else {
                    listOf("join", "leave", "status")
                }
                
                commands.filter { it.lowercase().startsWith(args[0].lowercase()) }
                    .forEach { completions.add(it) }
            }
            
            2 -> {
                when (args[0].lowercase()) {
                    "join", "setflag", "setspawn", "setteam" -> {
                        listOf("red", "blue").filter { it.lowercase().startsWith(args[1].lowercase()) }
                            .forEach { completions.add(it) }
                    }
                }
            }
            
            3 -> {
                if (args[0].lowercase() == "setteam" && args.size == 3) {
                    listOf("red", "blue").filter { it.lowercase().startsWith(args[2].lowercase()) }
                        .forEach { completions.add(it) }
                }
            }
        }

        return completions
    }
}