package com.hacklab.ctf.managers

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.time.Duration
import java.util.UUID

/**
 * ゲームUI管理（スコアボード、BossBar、ActionBar）。
 * Game への参照を通じてゲーム状態を読み取り、UI を更新する。
 */
class GameScoreboardManager(
    private val game: Game,
    private val plugin: Main
) {
    // BossBar
    var bossBar: BossBar? = null
        private set

    // スコアボード更新レート制限
    private var lastScoreboardUpdate: Long = 0

    // ActionBar メッセージ管理
    private val actionBarCooldown = mutableMapOf<UUID, Long>()
    private val actionBarErrorDisplay = mutableMapOf<UUID, Pair<String, Long>>()
    private val actionBarPriority = mutableMapOf<UUID, Int>()

    // --- BossBar ---

    fun createBossBar() {
        bossBar = Bukkit.createBossBar(
            "",
            BarColor.YELLOW,
            BarStyle.SOLID
        )
    }

    fun addPlayerToBossBar(player: Player) {
        bossBar?.addPlayer(player)
    }

    fun removePlayerFromBossBar(player: Player) {
        bossBar?.removePlayer(player)
    }

    fun destroyBossBar() {
        bossBar?.removeAll()
        bossBar = null
    }

    fun updateBossBar() {
        val bar = bossBar ?: return

        val timeText = formatTime(game.currentPhaseTime)

        val barTitle = when (game.phase) {
            GamePhase.BUILD -> {
                plugin.languageManager.getMessage("phase-extended.build-time-format", "time" to timeText)
            }
            GamePhase.COMBAT -> {
                val redScore = game.score[Team.RED] ?: 0
                val blueScore = game.score[Team.BLUE] ?: 0
                if (game.matchWrapper != null) {
                    plugin.languageManager.getMessage("phase-extended.combat-score-format-match",
                        "current" to game.matchWrapper!!.currentGameNumber.toString(),
                        "total" to game.matchWrapper!!.config.matchTarget.toString(),
                        "red" to redScore.toString(),
                        "blue" to blueScore.toString(),
                        "time" to timeText)
                } else {
                    plugin.languageManager.getMessage("phase-extended.combat-score-format",
                        "red" to redScore.toString(),
                        "blue" to blueScore.toString(),
                        "time" to timeText)
                }
            }
        }

        bar.setTitle(barTitle)

        bar.color = when (game.phase) {
            GamePhase.BUILD -> BarColor.GREEN
            GamePhase.COMBAT -> BarColor.RED
        }

        val totalTime = when (game.phase) {
            GamePhase.BUILD -> game.buildDuration
            GamePhase.COMBAT -> game.combatDuration
        }

        val progress = game.currentPhaseTime.toDouble() / totalTime.toDouble()
        bar.progress = progress.coerceIn(0.0, 1.0)
    }

    // --- スコアボード ---

    fun setupScoreboard(player: Player) {
        try {
            val playerScoreboard = Bukkit.getScoreboardManager().newScoreboard
            val playerObjective = playerScoreboard.registerNewObjective("ctf_game", "dummy",
                plugin.languageManager.getMessageAsComponent("scoreboard.title-ctf", "game" to game.gameName).color(NamedTextColor.GOLD))
            playerObjective.displaySlot = DisplaySlot.SIDEBAR

            setupScoreboardTeams(playerScoreboard)
            player.scoreboard = playerScoreboard
            updatePlayerTeamColor(player, playerScoreboard)
            updatePlayerScoreboard(player)

        } catch (e: Exception) {
            plugin.logger.warning("Failed to setup scoreboard for player ${player.name}: ${e.message}")
        }
    }

    fun setupScoreboardTeams(scoreboard: Scoreboard) {
        if (scoreboard.getTeam("ctf_red") == null) {
            val redTeam = scoreboard.registerNewTeam("ctf_red")
            redTeam.color(NamedTextColor.RED)
            redTeam.prefix(Component.text("[${plugin.languageManager.getMessage("teams.red")}] ", NamedTextColor.RED))
        }

        if (scoreboard.getTeam("ctf_blue") == null) {
            val blueTeam = scoreboard.registerNewTeam("ctf_blue")
            blueTeam.color(NamedTextColor.BLUE)
            blueTeam.prefix(Component.text("[${plugin.languageManager.getMessage("teams.blue")}] ", NamedTextColor.BLUE))
        }

        if (scoreboard.getTeam("ctf_spectator") == null) {
            val spectatorTeam = scoreboard.registerNewTeam("ctf_spectator")
            spectatorTeam.color(NamedTextColor.GRAY)
            spectatorTeam.prefix(Component.text("[${plugin.languageManager.getMessage("teams.spectator")}] ", NamedTextColor.GRAY))
        }
    }

    fun updatePlayerTeamColor(player: Player, scoreboard: Scoreboard) {
        val playerTeam = game.getPlayerTeam(player.uniqueId)

        scoreboard.teams.forEach { team ->
            if (team.hasEntry(player.name)) {
                team.removeEntry(player.name)
            }
        }

        when (playerTeam) {
            Team.RED -> scoreboard.getTeam("ctf_red")?.addEntry(player.name)
            Team.BLUE -> scoreboard.getTeam("ctf_blue")?.addEntry(player.name)
            Team.SPECTATOR -> scoreboard.getTeam("ctf_spectator")?.addEntry(player.name)
            else -> {}
        }

        game.getAllPlayers().forEach { otherPlayer ->
            if (otherPlayer != player) {
                val otherScoreboard = otherPlayer.scoreboard
                if (otherScoreboard != Bukkit.getScoreboardManager().mainScoreboard) {
                    setupScoreboardTeams(otherScoreboard)

                    otherScoreboard.teams.forEach { team ->
                        if (team.hasEntry(player.name)) {
                            team.removeEntry(player.name)
                        }
                    }

                    when (playerTeam) {
                        Team.RED -> otherScoreboard.getTeam("ctf_red")?.addEntry(player.name)
                        Team.BLUE -> otherScoreboard.getTeam("ctf_blue")?.addEntry(player.name)
                        Team.SPECTATOR -> otherScoreboard.getTeam("ctf_spectator")?.addEntry(player.name)
                        else -> {}
                    }

                    val otherTeam = game.getPlayerTeam(otherPlayer.uniqueId)
                    when (otherTeam) {
                        Team.RED -> scoreboard.getTeam("ctf_red")?.addEntry(otherPlayer.name)
                        Team.BLUE -> scoreboard.getTeam("ctf_blue")?.addEntry(otherPlayer.name)
                        Team.SPECTATOR -> scoreboard.getTeam("ctf_spectator")?.addEntry(otherPlayer.name)
                        else -> {}
                    }
                }
            }
        }
    }

    fun updateScoreboard() {
        if (System.currentTimeMillis() - lastScoreboardUpdate < 1000) {
            return
        }
        lastScoreboardUpdate = System.currentTimeMillis()

        game.getAllPlayers().forEach { player ->
            updatePlayerScoreboard(player)
        }
    }

    fun forceUpdateScoreboard() {
        lastScoreboardUpdate = 0
        updateScoreboard()
    }

    fun updatePlayerTabColor(player: Player) {
        val scoreboard = player.scoreboard
        if (scoreboard != Bukkit.getScoreboardManager().mainScoreboard) {
            updatePlayerTeamColor(player, scoreboard)
        }
    }

    fun resetScoreboard(player: Player) {
        player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
    }

    private fun updatePlayerScoreboard(player: Player) {
        val playerScoreboard = player.scoreboard
        val obj = playerScoreboard.getObjective("ctf_game") ?: return

        playerScoreboard.entries.forEach { entry ->
            playerScoreboard.resetScores(entry)
        }

        var line = 15

        if (game.state == GameState.WAITING || game.state == GameState.STARTING) {
            val redCount = game.redTeam.size
            val blueCount = game.blueTeam.size
            val spectatorCount = game.spectators.size

            if (spectatorCount > 0) {
                obj.getScore(plugin.languageManager.getMessage("scoreboard.team-players-spectator",
                    "red" to redCount.toString(), "blue" to blueCount.toString(), "spectator" to spectatorCount.toString())).score = line--
            } else {
                obj.getScore(plugin.languageManager.getMessage("scoreboard.team-players",
                    "red" to redCount.toString(), "blue" to blueCount.toString())).score = line--
            }

            val match = game.matchWrapper
            if (match != null) {
                obj.getScore(plugin.languageManager.getMessage("scoreboard.match-game-number",
                    "current" to match.currentGameNumber.toString(),
                    "total" to match.config.matchTarget.toString())).score = line--

                val redWins = match.matchWins[Team.RED] ?: 0
                val blueWins = match.matchWins[Team.BLUE] ?: 0
                if (redWins > 0 || blueWins > 0) {
                    obj.getScore(plugin.languageManager.getMessage("scoreboard.match-wins",
                        "red" to redWins.toString(),
                        "blue" to blueWins.toString())).score = line--
                }
            }

            obj.getScore(plugin.languageManager.getMessage("scoreboard.build-combat-time",
                "build" to (game.buildDuration / 60).toString(), "combat" to (game.combatDuration / 60).toString())).score = line--
            obj.getScore(plugin.languageManager.getMessage("scoreboard.min-players", "min" to game.minPlayers.toString())).score = line--

            if (game.state == GameState.STARTING && game.autoStartCountdown > 0) {
                obj.getScore(plugin.languageManager.getMessage("scoreboard.start-countdown", "seconds" to game.autoStartCountdown.toString())).score = line--
            }

            return
        }

        val playerTeam = game.getPlayerTeam(player.uniqueId)
        val isSpectator = playerTeam == Team.SPECTATOR

        if (game.matchWrapper != null) {
            val currentGame = game.matchWrapper!!.currentGameNumber
            val totalGames = game.matchWrapper!!.config.matchTarget
            obj.getScore(plugin.languageManager.getMessage("scoreboard.match-game-number",
                "current" to currentGame.toString(),
                "total" to totalGames.toString())).score = line--

            val redWins = game.matchWrapper!!.matchWins[Team.RED] ?: 0
            val blueWins = game.matchWrapper!!.matchWins[Team.BLUE] ?: 0
            obj.getScore(plugin.languageManager.getMessage("scoreboard.match-wins-compact",
                "red" to redWins.toString(),
                "blue" to blueWins.toString())).score = line--

        } else {
            val redScore = game.score[Team.RED] ?: 0
            val blueScore = game.score[Team.BLUE] ?: 0
            obj.getScore(plugin.languageManager.getMessage("scoreboard.game-score",
                "red" to redScore.toString(),
                "blue" to blueScore.toString())).score = line--
        }

        val redCount = game.redTeam.size
        val blueCount = game.blueTeam.size
        val spectatorCount = game.spectators.size
        if (spectatorCount > 0) {
            obj.getScore(plugin.languageManager.getMessage("scoreboard.players-count-spectator",
                "red" to redCount.toString(),
                "blue" to blueCount.toString(),
                "spectator" to spectatorCount.toString())).score = line--
        } else {
            obj.getScore(plugin.languageManager.getMessage("scoreboard.players-count",
                "red" to redCount.toString(),
                "blue" to blueCount.toString())).score = line--
        }

        obj.getScore("  ").score = line--

        if (game.phase == GamePhase.COMBAT || game.phase == GamePhase.BUILD) {
            if (isSpectator) {
                val redCurrency = if (game.matchWrapper != null) {
                    game.matchWrapper!!.getTeamCurrency(Team.RED)
                } else {
                    game.getTeamCurrency(Team.RED)
                }
                val blueCurrency = if (game.matchWrapper != null) {
                    game.matchWrapper!!.getTeamCurrency(Team.BLUE)
                } else {
                    game.getTeamCurrency(Team.BLUE)
                }
                obj.getScore(plugin.languageManager.getMessage("scoreboard.both-currency",
                    "red" to redCurrency.toString(),
                    "blue" to blueCurrency.toString())).score = line--
            } else if (playerTeam != null) {
                val currency = if (game.matchWrapper != null) {
                    game.matchWrapper!!.getTeamCurrency(playerTeam)
                } else {
                    game.getTeamCurrency(playerTeam)
                }
                val teamColor = if (playerTeam == Team.RED) "§c" else "§9"
                obj.getScore(plugin.languageManager.getMessage("scoreboard.team-currency",
                    "color" to teamColor,
                    "amount" to currency.toString())).score = line--
            }
        }
    }

    // --- イベント通知 ---

    fun sendEventNotification(
        title: Component,
        subtitle: Component? = null,
        targetPlayers: List<Player> = game.getAllPlayers().toList(),
        sound: Sound? = null,
        soundPitch: Float = 1.0f
    ) {
        targetPlayers.forEach { player ->
            player.showTitle(Title.title(
                title,
                subtitle ?: Component.empty(),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofMillis(2000),
                    Duration.ofMillis(500)
                )
            ))

            sound?.let {
                player.playSound(player.location, it, 1.0f, soundPitch)
            }
        }
    }

    // --- ActionBar ---

    fun showActionBarError(player: Player, message: String, duration: Long = 3000L) {
        val now = System.currentTimeMillis()
        actionBarErrorDisplay[player.uniqueId] = Pair(message, now + duration)
        actionBarPriority[player.uniqueId] = 100
        player.sendActionBar(Component.text(message, NamedTextColor.RED))
    }

    fun updateActionBarGuides() {
        game.getAllPlayers().forEach { player ->
            val team = game.getPlayerTeam(player.uniqueId) ?: return@forEach

            val now = System.currentTimeMillis()
            val errorData = actionBarErrorDisplay[player.uniqueId]
            if (errorData != null && now < errorData.second) {
                player.sendActionBar(Component.text(errorData.first, NamedTextColor.RED))
                return@forEach
            } else if (errorData != null) {
                actionBarErrorDisplay.remove(player.uniqueId)
                actionBarPriority.remove(player.uniqueId)
            }

            val message = when (game.phase) {
                GamePhase.BUILD -> {
                    plugin.languageManager.getMessageAsComponent("action-bar.build-phase-guide")
                }
                GamePhase.COMBAT -> {
                    val enemyTeam = if (team == Team.RED) Team.BLUE else Team.RED

                    when {
                        // 自分が旗を持っている
                        player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier -> {
                            plugin.languageManager.getMessageAsComponent("action-bar.return-to-base")
                        }

                        // 自チームの旗が敵に取られている
                        (team == Team.RED && game.redFlagCarrier != null) ||
                        (team == Team.BLUE && game.blueFlagCarrier != null) -> {
                            val carrierName = when (team) {
                                Team.RED -> game.redFlagCarrier?.let { Bukkit.getPlayer(it)?.name }
                                    ?: plugin.languageManager.getMessage("flag.flag-carrier-unknown")
                                Team.BLUE -> game.blueFlagCarrier?.let { Bukkit.getPlayer(it)?.name }
                                    ?: plugin.languageManager.getMessage("flag.flag-carrier-unknown")
                                Team.SPECTATOR -> plugin.languageManager.getMessage("flag.flag-carrier-unknown")
                                else -> game.flagCarriers[team]?.let { Bukkit.getPlayer(it)?.name }
                                    ?: plugin.languageManager.getMessage("flag.flag-carrier-unknown")
                            }
                            plugin.languageManager.getMessageAsComponent("flag.enemy-has-flag", "carrier" to carrierName)
                        }

                        // 自チームの旗がドロップしている
                        (team == Team.RED && game.droppedFlags.any { it.value.first == Team.RED }) ||
                        (team == Team.BLUE && game.droppedFlags.any { it.value.first == Team.BLUE }) -> {
                            plugin.languageManager.getMessageAsComponent("flag.team-flag-dropped",
                                "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}"))
                        }

                        // 通常状態（敵の旗を取りに行く）
                        else -> {
                            plugin.languageManager.getMessageAsComponent("flag.retrieve-enemy-flag",
                                "color" to enemyTeam.getChatColor(),
                                "team" to plugin.languageManager.getMessage("teams.${enemyTeam.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            player.sendActionBar(message)
        }
    }

    // --- クリーンアップ ---

    fun clearState() {
        actionBarCooldown.clear()
        actionBarErrorDisplay.clear()
        actionBarPriority.clear()
        lastScoreboardUpdate = 0
    }

    // --- ユーティリティ ---

    fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return String.format("%d:%02d", min, sec)
    }
}
