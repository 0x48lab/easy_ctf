package com.hacklab.ctf.managers

import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.Team
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.*
import java.util.*

class GameManager(private val plugin: Main) {
    
    private val languageManager = plugin.languageManager
    private val equipmentManager = plugin.equipmentManager
    
    private var gameState = GameState.WAITING
    private var currentPhase = GamePhase.BUILD
    private val playerTeams = mutableMapOf<UUID, Team>()
    private val scores = mutableMapOf<Team, Int>().apply {
        put(Team.RED, 0)
        put(Team.BLUE, 0)
    }
    
    val teamSpawns = mutableMapOf<Team, Location>()
    private val flagLocations = mutableMapOf<Team, Location>()
    private val flagBases = mutableMapOf<Team, Location>()
    private val spawnProtectionAreas = mutableMapOf<Team, Location>()
    private val spawnBeacons = mutableMapOf<Team, Location>()
    private var redFlagCarrier: UUID? = null
    private var blueFlagCarrier: UUID? = null
    
    // プレイヤーが配置したブロックを追跡（ワールド座標 -> 配置したプレイヤーのUUID）
    private val placedBlocks = mutableMapOf<Location, UUID>()
    
    // 統計情報の追跡
    private val playerKills = mutableMapOf<UUID, Int>()
    private val playerDeaths = mutableMapOf<UUID, Int>()
    private val playerFlagCaptures = mutableMapOf<UUID, Int>()
    
    private lateinit var gameBar: BossBar
    private lateinit var scoreboard: Scoreboard
    private lateinit var objective: Objective
    private var timeRemaining = 0
    private var gameTimer: BukkitRunnable? = null

    init {
        setupScoreboard()
    }

    private fun setupScoreboard() {
        val manager = Bukkit.getScoreboardManager()!!
        scoreboard = manager.newScoreboard
        objective = scoreboard.registerNewObjective("ctf", "dummy", languageManager.getUIMessage("scoreboard-title"))
        objective.displaySlot = DisplaySlot.SIDEBAR
        
        gameBar = Bukkit.createBossBar(
            languageManager.getUIMessage("bossbar-waiting"),
            BarColor.YELLOW,
            BarStyle.SOLID
        ).apply {
            setProgress(1.0)
        }
    }

    fun startGame() {
        if (gameState != GameState.WAITING) return
        
        gameState = GameState.RUNNING
        currentPhase = GamePhase.BUILD
        timeRemaining = plugin.config.getInt("phases.build-duration", 300)
        
        // 統計情報をリセット
        clearStatistics()
        
        Bukkit.getOnlinePlayers().forEach { player ->
            if (playerTeams.containsKey(player.uniqueId)) {
                prepareBuildPhasePlayer(player)
                player.scoreboard = scoreboard
                gameBar.addPlayer(player)
                updatePlayerTabListName(player)
                updatePlayerTabListHeaderFooter(player)
            }
        }
        
        // スポーンエリアのビーコンとカラーブロックを設置
        setupSpawnAreas()
        
        startPhaseTimer()
        updateScoreboard()
        updateBossBar()
        
        Bukkit.broadcastMessage(languageManager.getPhaseMessage("build", "start"))
        Bukkit.broadcastMessage(languageManager.getPhaseMessage("build", "info", "time" to "${timeRemaining / 60}"))
    }

    private fun preparePlayer(player: Player) {
        val team = playerTeams[player.uniqueId] ?: return
        
        with(player) {
            inventory.clear()
            health = 20.0
            foodLevel = 20
            saturation = 20.0f
        }
        
        val armorColor = if (team == Team.RED) Color.RED else Color.BLUE
        
        val armorPieces = listOf(
            Material.LEATHER_HELMET,
            Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS,
            Material.LEATHER_BOOTS
        ).map { material ->
            ItemStack(material).apply {
                val meta = itemMeta as LeatherArmorMeta
                meta.setColor(armorColor)
                meta.isUnbreakable = true
                itemMeta = meta
            }
        }
        
        with(player.equipment!!) {
            helmet = armorPieces[0]
            chestplate = armorPieces[1]
            leggings = armorPieces[2]
            boots = armorPieces[3]
        }
        
        teamSpawns[team]?.let { spawn ->
            player.teleport(spawn)
        }
        
        val teamColor = if (team == Team.RED) "${ChatColor.RED}RED" else "${ChatColor.BLUE}BLUE"
        player.sendMessage("${ChatColor.GREEN}Game started! You are on $teamColor${ChatColor.GREEN} team!")
    }

    private fun prepareBuildPhasePlayer(player: Player) {
        val team = playerTeams[player.uniqueId] ?: return
        
        // config.ymlから建築フェーズのゲームモードを取得
        val buildGameMode = getBuildPhaseGameMode()
        
        with(player) {
            health = 20.0
            foodLevel = 20
            saturation = 20.0f
            gameMode = buildGameMode
        }
        
        equipmentManager.giveBuildPhaseEquipment(player)
        
        // プレイヤーを自陣のリスポン地点に転送
        val spawn = teamSpawns[team]
        if (spawn != null) {
            player.teleport(spawn)
            plugin.logger.info("Player ${player.name} teleported to ${team} team spawn")
        } else {
            plugin.logger.warning("No spawn location set for ${team} team! Player ${player.name} was not teleported.")
            player.sendMessage(languageManager.getGeneralMessage("spawn-not-set"))
        }
        
        val teamColor = if (team == Team.RED) ChatColor.RED else ChatColor.BLUE
        val teamName = if (team == Team.RED) "RED" else "BLUE"
        player.sendMessage(languageManager.getGameMessage("build-phase-player", "color" to teamColor.toString(), "team" to teamName))
        player.sendMessage(languageManager.getGameMessage("build-phase-info"))
    }

    private fun prepareCombatPhasePlayer(player: Player) {
        val team = playerTeams[player.uniqueId] ?: return
        
        with(player) {
            health = 20.0
            foodLevel = 20
            saturation = 20.0f
            gameMode = GameMode.SURVIVAL
        }
        
        equipmentManager.giveCombatPhaseEquipment(player, team)
        
        // プレイヤーを自陣のリスポン地点に転送
        val spawn = teamSpawns[team]
        if (spawn != null) {
            player.teleport(spawn)
            plugin.logger.info("Player ${player.name} teleported to ${team} team spawn for combat phase")
        } else {
            plugin.logger.warning("No spawn location set for ${team} team! Player ${player.name} was not teleported.")
            player.sendMessage(languageManager.getGeneralMessage("spawn-not-set"))
        }
        
        val teamColor = if (team == Team.RED) ChatColor.RED else ChatColor.BLUE
        val teamName = if (team == Team.RED) "RED" else "BLUE"
        player.sendMessage(languageManager.getGameMessage("combat-phase-player", "color" to teamColor.toString(), "team" to teamName))
        player.sendMessage(languageManager.getGameMessage("combat-phase-info"))
    }

    private fun spawnFlags() {
        Team.values().forEach { team ->
            flagBases[team]?.let { flagBase ->
                flagLocations[team] = flagBase.clone()
                createFlagBeacon(flagBase, team)
            }
        }
    }

    private fun createFlagBeacon(location: Location, team: Team) {
        val world = location.world ?: return
        
        location.block.type = Material.BEACON
        
        // Create beacon base
        (-1..1).forEach { x ->
            (-1..1).forEach { z ->
                val blockLoc = location.clone().add(x.toDouble(), -1.0, z.toDouble())
                blockLoc.block.type = Material.IRON_BLOCK
            }
        }
        
        // Particle effect task
        object : BukkitRunnable() {
            override fun run() {
                if (gameState != GameState.RUNNING) {
                    cancel()
                    return
                }
                
                (0 until 50).forEach { i ->
                    val angle = 2 * Math.PI * i / 50
                    val x = Math.cos(angle) * 0.5
                    val z = Math.sin(angle) * 0.5
                    
                    val particleLoc = location.clone().add(x, i * 0.5, z)
                    
                    val dustColor = if (team == Team.RED) Color.RED else Color.BLUE
                    world.spawnParticle(
                        Particle.DUST,
                        particleLoc,
                        1,
                        Particle.DustOptions(dustColor, 1.0f)
                    )
                }
            }
        }.runTaskTimer(plugin, 0L, 10L)
    }

    private fun startPhaseTimer() {
        gameTimer = object : BukkitRunnable() {
            override fun run() {
                if (timeRemaining <= 0) {
                    nextPhase()
                    cancel()
                    return
                }
                
                timeRemaining--
                updateScoreboard()
                updateBossBar()
                
                when (timeRemaining) {
                    60, 30, 10 -> {
                        val phaseDisplayName = when(currentPhase) {
                            GamePhase.BUILD -> "Build Phase"
                            GamePhase.COMBAT -> "Combat Phase"
                            GamePhase.RESULT -> "Result Phase"
                        }
                        Bukkit.broadcastMessage("${ChatColor.YELLOW}⚠ $phaseDisplayName ends in $timeRemaining seconds!")
                    }
                }
            }
        }
        gameTimer!!.runTaskTimer(plugin, 0L, 20L)
    }

    private fun nextPhase() {
        when (currentPhase) {
            GamePhase.BUILD -> {
                currentPhase = GamePhase.COMBAT
                timeRemaining = plugin.config.getInt("phases.combat-duration", 600)
                startCombatPhase()
                startPhaseTimer()
            }
            GamePhase.COMBAT -> {
                currentPhase = GamePhase.RESULT
                timeRemaining = plugin.config.getInt("phases.result-duration", 60)
                startResultPhase()
                startPhaseTimer()
            }
            GamePhase.RESULT -> {
                endGame()
            }
        }
    }

    private fun startCombatPhase() {
        clearBuildPhaseBlocks()
        spawnFlags()
        
        Bukkit.getOnlinePlayers().forEach { player ->
            if (playerTeams.containsKey(player.uniqueId)) {
                prepareCombatPhasePlayer(player)
                updatePlayerTabListName(player)
                updatePlayerTabListHeaderFooter(player)
            }
        }
        
        updateScoreboard()
        updateBossBar()
        
        Bukkit.broadcastMessage(languageManager.getPhaseMessage("combat", "start"))
        Bukkit.broadcastMessage(languageManager.getPhaseMessage("combat", "info", "time" to "${timeRemaining / 60}"))
    }

    private fun startResultPhase() {
        removeFlags()
        
        Bukkit.getOnlinePlayers().forEach { player ->
            if (playerTeams.containsKey(player.uniqueId)) {
                player.inventory.clear()
                val team = playerTeams[player.uniqueId]!!
                val spawn = teamSpawns[team]
                if (spawn != null) {
                    player.teleport(spawn)
                    plugin.logger.info("Player ${player.name} teleported to ${team} team spawn for result phase")
                } else {
                    plugin.logger.warning("No spawn location set for ${team} team! Player ${player.name} was not teleported.")
                    player.sendMessage(languageManager.getGeneralMessage("spawn-not-set"))
                }
                updatePlayerTabListName(player)
                updatePlayerTabListHeaderFooter(player)
            }
        }
        
        updateScoreboard()
        updateBossBar()
        
        val winner = getWinningTeam()
        val winnerColor = if (winner == Team.RED) ChatColor.RED else ChatColor.BLUE
        val winnerName = if (winner == Team.RED) "RED" else "BLUE"
        
        Bukkit.broadcastMessage(languageManager.getPhaseMessage("result", "start"))
        Bukkit.broadcastMessage(languageManager.getPhaseMessage("result", "winner", "color" to winnerColor.toString(), "team" to winnerName))
        Bukkit.broadcastMessage(languageManager.getPhaseMessage("result", "score", "red" to "${scores[Team.RED]}", "blue" to "${scores[Team.BLUE]}"))
        
        // MVP と ランキング表示
        displayMVPAndRankings()
    }

    private fun removeFlags() {
        redFlagCarrier = null
        blueFlagCarrier = null
        // フラグエンティティの削除処理をここに追加（必要に応じて）
    }

    private fun getWinningTeam(): Team {
        val redScore = scores[Team.RED] ?: 0
        val blueScore = scores[Team.BLUE] ?: 0
        return if (redScore >= blueScore) Team.RED else Team.BLUE
    }

    private fun updateScoreboard() {
        // Clear existing scores
        objective.scoreboard!!.entries.forEach { entry ->
            objective.scoreboard!!.resetScores(entry)
        }
        
        objective.getScore("${ChatColor.GRAY}════════════════").setScore(11)
        
        val phaseColor = when(currentPhase) {
            GamePhase.BUILD -> ChatColor.YELLOW
            GamePhase.COMBAT -> ChatColor.RED
            GamePhase.RESULT -> ChatColor.GOLD
        }
        val phaseDisplayName = when(currentPhase) {
            GamePhase.BUILD -> languageManager.getUIMessage("phase-build")
            GamePhase.COMBAT -> languageManager.getUIMessage("phase-combat")
            GamePhase.RESULT -> languageManager.getUIMessage("phase-result")
        }
        
        objective.getScore("${ChatColor.WHITE}Phase: $phaseColor$phaseDisplayName").setScore(10)
        objective.getScore("${ChatColor.WHITE}Time: ${ChatColor.GREEN}${formatTime(timeRemaining)}").setScore(9)
        objective.getScore(" ").setScore(8)
        objective.getScore("${ChatColor.RED}Red Team: ${ChatColor.WHITE}${scores[Team.RED]}").setScore(7)
        objective.getScore("${ChatColor.BLUE}Blue Team: ${ChatColor.WHITE}${scores[Team.BLUE]}").setScore(6)
        objective.getScore("  ").setScore(5)
        
        redFlagCarrier?.let { carrierId ->
            Bukkit.getPlayer(carrierId)?.let { carrier ->
                objective.getScore("${ChatColor.RED}⚑ ${carrier.name}").setScore(4)
            }
        }
        
        blueFlagCarrier?.let { carrierId ->
            Bukkit.getPlayer(carrierId)?.let { carrier ->
                objective.getScore("${ChatColor.BLUE}⚑ ${carrier.name}").setScore(3)
            }
        }
        
        objective.getScore("   ").setScore(2)
        objective.getScore("${ChatColor.GRAY}════════════════ ").setScore(1)
    }

    private fun updateBossBar() {
        val totalPhaseTime = when(currentPhase) {
            GamePhase.BUILD -> plugin.config.getInt("phases.build-duration", 300)
            GamePhase.COMBAT -> plugin.config.getInt("phases.combat-duration", 600)
            GamePhase.RESULT -> plugin.config.getInt("phases.result-duration", 60)
        }
        
        val progress = timeRemaining.toDouble() / totalPhaseTime
        gameBar.setProgress(maxOf(0.0, minOf(1.0, progress)))
        
        val phaseColor = when(currentPhase) {
            GamePhase.BUILD -> BarColor.YELLOW
            GamePhase.COMBAT -> BarColor.RED
            GamePhase.RESULT -> BarColor.PURPLE
        }
        gameBar.setColor(phaseColor)
        
        val phaseDisplayName = when(currentPhase) {
            GamePhase.BUILD -> languageManager.getUIMessage("bossbar-build")
            GamePhase.COMBAT -> languageManager.getUIMessage("bossbar-combat")
            GamePhase.RESULT -> languageManager.getUIMessage("bossbar-result")
        }
        
        gameBar.setTitle(
            "${ChatColor.GOLD}$phaseDisplayName - ${ChatColor.WHITE}${formatTime(timeRemaining)}" +
            "${ChatColor.GRAY} | " +
            "${ChatColor.RED}Red: ${scores[Team.RED]}" +
            "${ChatColor.GRAY} - " +
            "${ChatColor.BLUE}Blue: ${scores[Team.BLUE]}"
        )
    }

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", minutes, secs)
    }

    fun captureFlag(player: Player, capturedTeam: Team) {
        val playerTeam = playerTeams[player.uniqueId]
        if (playerTeam == null || playerTeam == capturedTeam) return
        
        scores[playerTeam] = scores[playerTeam]!! + 1
        
        // 旗奪取数を記録
        playerFlagCaptures[player.uniqueId] = (playerFlagCaptures[player.uniqueId] ?: 0) + 1
        
        val teamColor = if (capturedTeam == Team.RED) "${ChatColor.RED}RED" else "${ChatColor.BLUE}BLUE"
        Bukkit.broadcastMessage("${ChatColor.GOLD}⚑ ${player.name} captured the $teamColor${ChatColor.GOLD} flag!")
        
        player.world.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f)
        
        repeat(50) {
            player.world.spawnParticle(
                Particle.FIREWORK,
                player.location.add(0.0, 2.0, 0.0),
                10, 0.5, 0.5, 0.5, 0.1
            )
        }
        
        when (capturedTeam) {
            Team.RED -> redFlagCarrier = null
            Team.BLUE -> blueFlagCarrier = null
        }
        
        respawnFlag(capturedTeam)
        updateScoreboard()
        
        // 統計更新後にタブリストを更新
        updateAllPlayersTabList()
    }

    private fun respawnFlag(team: Team) {
        flagBases[team]?.let { flagBase ->
            flagLocations[team] = flagBase.clone()
            createFlagBeacon(flagBase, team)
        }
    }

    fun pickupFlag(player: Player, flagTeam: Team) {
        val playerTeam = playerTeams[player.uniqueId]
        if (playerTeam == null || playerTeam == flagTeam) return
        
        when (flagTeam) {
            Team.RED -> redFlagCarrier = player.uniqueId
            Team.BLUE -> blueFlagCarrier = player.uniqueId
        }
        
        player.setGlowing(true)
        
        val teamColor = if (flagTeam == Team.RED) "${ChatColor.RED}RED" else "${ChatColor.BLUE}BLUE"
        Bukkit.broadcastMessage("${ChatColor.YELLOW}⚑ ${player.name} picked up the $teamColor${ChatColor.YELLOW} flag!")
        
        object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || gameState != GameState.RUNNING) {
                    cancel()
                    return
                }
                
                val carrierId = if (flagTeam == Team.RED) redFlagCarrier else blueFlagCarrier
                if (carrierId == null || carrierId != player.uniqueId) {
                    player.setGlowing(false)
                    cancel()
                    return
                }
                
                val dustColor = if (flagTeam == Team.RED) Color.RED else Color.BLUE
                player.world.spawnParticle(
                    Particle.DUST,
                    player.location.add(0.0, 2.0, 0.0),
                    5, 0.2, 0.2, 0.2,
                    Particle.DustOptions(dustColor, 2.0f)
                )
            }
        }.runTaskTimer(plugin, 0L, 5L)
    }

    fun dropFlag(player: Player) {
        val droppedFlag = when {
            redFlagCarrier == player.uniqueId -> {
                redFlagCarrier = null
                Team.RED
            }
            blueFlagCarrier == player.uniqueId -> {
                blueFlagCarrier = null
                Team.BLUE
            }
            else -> null
        }
        
        droppedFlag?.let { flag ->
            player.setGlowing(false)
            flagLocations[flag] = player.location
            createFlagBeacon(player.location, flag)
            
            val teamColor = if (flag == Team.RED) "${ChatColor.RED}RED" else "${ChatColor.BLUE}BLUE"
            Bukkit.broadcastMessage("${ChatColor.YELLOW}⚑ The $teamColor${ChatColor.YELLOW} flag has been dropped!")
        }
    }

    fun endGame() {
        gameState = GameState.ENDING
        
        gameTimer?.cancel()
        
        val winner = when {
            scores[Team.RED]!! > scores[Team.BLUE]!! -> Team.RED
            scores[Team.BLUE]!! > scores[Team.RED]!! -> Team.BLUE
            else -> null
        }
        
        if (winner != null) {
            val winnerColor = if (winner == Team.RED) "${ChatColor.RED}RED TEAM" else "${ChatColor.BLUE}BLUE TEAM"
            Bukkit.broadcastMessage("${ChatColor.GOLD}════════════════════════════════")
            Bukkit.broadcastMessage("${ChatColor.YELLOW}        GAME OVER!")
            Bukkit.broadcastMessage("${ChatColor.WHITE}  Winner: $winnerColor")
            Bukkit.broadcastMessage("${ChatColor.WHITE}  Score: ${ChatColor.RED}${scores[Team.RED]}${ChatColor.WHITE} - ${ChatColor.BLUE}${scores[Team.BLUE]}")
            Bukkit.broadcastMessage("${ChatColor.GOLD}════════════════════════════════")
        } else {
            Bukkit.broadcastMessage("${ChatColor.YELLOW}Game ended in a draw!")
        }
        
        object : BukkitRunnable() {
            override fun run() {
                stopGame()
            }
        }.runTaskLater(plugin, 100L)
    }

    fun stopGame() {
        gameState = GameState.WAITING
        
        // タイマーをキャンセル
        gameTimer?.cancel()
        gameTimer = null
        
        Bukkit.getOnlinePlayers().forEach { player ->
            player.scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
            player.setGlowing(false)
            gameBar.removePlayer(player)
            // タブリストの名前をリセット
            resetPlayerTabListName(player)
            // ヘッダー・フッターもクリア
            player.setPlayerListHeader("")
            player.setPlayerListFooter("")
        }
        
        playerTeams.clear()
        scores[Team.RED] = 0
        scores[Team.BLUE] = 0
        redFlagCarrier = null
        blueFlagCarrier = null
        clearStatistics()
        
        // スポーンエリアの設置物をクリア
        clearSpawnAreas()
        
        updateScoreboard()
    }

    fun joinTeam(player: Player, team: Team) {
        playerTeams[player.uniqueId] = team
        val teamColor = if (team == Team.RED) ChatColor.RED else ChatColor.BLUE
        val teamName = if (team == Team.RED) "RED" else "BLUE"
        
        // タブリストの名前を更新
        updatePlayerTabListName(player)
        updatePlayerTabListHeaderFooter(player)
        
        // チーム人数を取得
        val (redSize, blueSize) = getTeamSizes()
        val maxPlayersPerTeam = plugin.config.getInt("game.max-players-per-team", 10)
        
        player.sendMessage(languageManager.getTeamMessage("join", "color" to teamColor.toString(), "team" to teamName))
        player.sendMessage(languageManager.getTeamsMessage("team-size-format", 
            "red" to redSize.toString(), 
            "blue" to blueSize.toString(), 
            "max" to maxPlayersPerTeam.toString()
        ))
        
        // 他のプレイヤーにも人数を通知
        Bukkit.getOnlinePlayers().forEach { otherPlayer ->
            if (otherPlayer != player && playerTeams.containsKey(otherPlayer.uniqueId)) {
                otherPlayer.sendMessage(languageManager.getTeamsMessage("player-joined", 
                    "player" to player.name, 
                    "color" to teamColor.toString(), 
                    "team" to teamName
                ))
                otherPlayer.sendMessage(languageManager.getTeamsMessage("team-size-format", 
                    "red" to redSize.toString(), 
                    "blue" to blueSize.toString(), 
                    "max" to maxPlayersPerTeam.toString()
                ))
            }
        }
    }

    fun setPlayerTeam(player: Player, team: Team): Boolean {
        val maxPlayersPerTeam = plugin.config.getInt("game.max-players-per-team", 10)
        val currentTeamSize = playerTeams.values.count { it == team }
        
        if (currentTeamSize >= maxPlayersPerTeam) {
            return false
        }
        
        if (player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier) {
            dropFlag(player)
        }
        
        playerTeams[player.uniqueId] = team
        
        // タブリストの名前を更新
        updatePlayerTabListName(player)
        updatePlayerTabListHeaderFooter(player)
        
        return true
    }

    fun leaveTeam(player: Player) {
        playerTeams.remove(player.uniqueId)
        if (player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier) {
            dropFlag(player)
        }
        
        // タブリストの名前をリセット
        resetPlayerTabListName(player)
        updatePlayerTabListHeaderFooter(player)
        
        // チーム離脱後の人数を表示
        val (redSize, blueSize) = getTeamSizes()
        val maxPlayersPerTeam = plugin.config.getInt("game.max-players-per-team", 10)
        
        player.sendMessage(languageManager.getTeamMessage("leave"))
        player.sendMessage(languageManager.getTeamsMessage("team-size-format", 
            "red" to redSize.toString(), 
            "blue" to blueSize.toString(), 
            "max" to maxPlayersPerTeam.toString()
        ))
        
        // 他のプレイヤーにも通知
        Bukkit.getOnlinePlayers().forEach { otherPlayer ->
            if (otherPlayer != player && playerTeams.containsKey(otherPlayer.uniqueId)) {
                otherPlayer.sendMessage(languageManager.getTeamsMessage("player-left", "player" to player.name))
                otherPlayer.sendMessage(languageManager.getTeamsMessage("team-size-format", 
                    "red" to redSize.toString(), 
                    "blue" to blueSize.toString(), 
                    "max" to maxPlayersPerTeam.toString()
                ))
            }
        }
    }

    fun setTeamSpawn(team: Team, location: Location): Boolean {
        // 旗との距離をチェック
        if (!validateFlagSpawnDistance(location, team, false)) {
            return false
        }
        
        teamSpawns[team] = location
        return true
    }

    fun setFlagBase(team: Team, location: Location): Boolean {
        // スポーンとの距離をチェック
        if (!validateFlagSpawnDistance(location, team, true)) {
            return false
        }
        
        flagBases[team] = location
        return true
    }

    fun getGameState() = gameState
    fun getCurrentPhase() = currentPhase
    fun getPlayerTeam(player: Player) = playerTeams[player.uniqueId]
    fun getFlagCarrier(flagTeam: Team) = if (flagTeam == Team.RED) redFlagCarrier else blueFlagCarrier
    fun getFlagLocation(team: Team) = flagLocations[team]

    // ブロック管理メソッド
    fun addPlacedBlock(location: Location, player: Player) {
        placedBlocks[location] = player.uniqueId
    }

    fun removePlacedBlock(location: Location) {
        placedBlocks.remove(location)
    }

    fun isPlayerPlacedBlock(location: Location): Boolean {
        return placedBlocks.containsKey(location)
    }

    fun getBlockPlacer(location: Location): UUID? {
        return placedBlocks[location]
    }

    fun clearPlacedBlocks() {
        placedBlocks.clear()
    }

    // フェーズ遷移時にプレイヤー配置ブロックをクリア
    private fun clearBuildPhaseBlocks() {
        placedBlocks.clear()
    }
    
    // 統計情報管理メソッド
    fun recordKill(killer: Player, victim: Player) {
        // 同じチームのプレイヤーを殺した場合は記録しない
        val killerTeam = playerTeams[killer.uniqueId]
        val victimTeam = playerTeams[victim.uniqueId]
        if (killerTeam == null || victimTeam == null || killerTeam == victimTeam) return
        
        playerKills[killer.uniqueId] = (playerKills[killer.uniqueId] ?: 0) + 1
        playerDeaths[victim.uniqueId] = (playerDeaths[victim.uniqueId] ?: 0) + 1
        
        // キル・デス統計更新後にタブリストを更新
        updatePlayerTabListName(killer)
        updatePlayerTabListName(victim)
    }
    
    fun recordDeath(player: Player) {
        // プレイヤーが参加していない場合は記録しない
        if (!playerTeams.containsKey(player.uniqueId)) return
        
        playerDeaths[player.uniqueId] = (playerDeaths[player.uniqueId] ?: 0) + 1
        
        // デス統計更新後にタブリストを更新
        updatePlayerTabListName(player)
    }
    
    fun getPlayerKills(player: Player): Int {
        return playerKills[player.uniqueId] ?: 0
    }
    
    fun getPlayerDeaths(player: Player): Int {
        return playerDeaths[player.uniqueId] ?: 0
    }
    
    fun getPlayerKDRatio(player: Player): Double {
        val kills = getPlayerKills(player)
        val deaths = getPlayerDeaths(player)
        return if (deaths > 0) kills.toDouble() / deaths else kills.toDouble()
    }
    
    fun getPlayerFlagCaptures(player: Player): Int {
        return playerFlagCaptures[player.uniqueId] ?: 0
    }
    
    fun clearStatistics() {
        playerKills.clear()
        playerDeaths.clear()
        playerFlagCaptures.clear()
    }
    
    fun getTeamSize(team: Team): Int {
        return playerTeams.values.count { it == team }
    }
    
    fun getTeamSizes(): Pair<Int, Int> {
        val redSize = getTeamSize(Team.RED)
        val blueSize = getTeamSize(Team.BLUE)
        return Pair(redSize, blueSize)
    }
    
    private fun displayMVPAndRankings() {
        val onlinePlayers = Bukkit.getOnlinePlayers().filter { playerTeams.containsKey(it.uniqueId) }
        
        if (onlinePlayers.isEmpty()) return
        
        // キル数でのMVPとランキング（同じキル数の場合はK/D比で判定）
        val killsMvp = onlinePlayers.maxWithOrNull(compareBy<Player> { playerKills[it.uniqueId] ?: 0 }
            .thenBy { getPlayerKDRatio(it) })
        val killsRanking = onlinePlayers.sortedWith(compareByDescending<Player> { playerKills[it.uniqueId] ?: 0 }
            .thenByDescending { getPlayerKDRatio(it) }).take(5)
        
        // 旗奪取数でのMVPとランキング
        val capturesMvp = onlinePlayers.maxByOrNull { playerFlagCaptures[it.uniqueId] ?: 0 }
        val capturesRanking = onlinePlayers.sortedByDescending { playerFlagCaptures[it.uniqueId] ?: 0 }.take(5)
        
        Bukkit.broadcastMessage("")
        Bukkit.broadcastMessage(languageManager.getResultMessage("mvp-title"))
        
        // キルMVP
        killsMvp?.let { mvp ->
            val kills = playerKills[mvp.uniqueId] ?: 0
            if (kills > 0) {
                val deaths = playerDeaths[mvp.uniqueId] ?: 0
                val kdRatio = getPlayerKDRatio(mvp)
                val teamColor = if (playerTeams[mvp.uniqueId] == Team.RED) ChatColor.RED else ChatColor.BLUE
                Bukkit.broadcastMessage(languageManager.getResultMessage("mvp-kills", 
                    "player" to mvp.name, 
                    "color" to teamColor.toString(),
                    "kills" to kills.toString(),
                    "deaths" to deaths.toString(),
                    "kd" to String.format("%.2f", kdRatio)
                ))
            }
        }
        
        // 旗奪取MVP
        capturesMvp?.let { mvp ->
            val captures = playerFlagCaptures[mvp.uniqueId] ?: 0
            if (captures > 0) {
                val teamColor = if (playerTeams[mvp.uniqueId] == Team.RED) ChatColor.RED else ChatColor.BLUE
                Bukkit.broadcastMessage(languageManager.getResultMessage("mvp-captures",
                    "player" to mvp.name,
                    "color" to teamColor.toString(),
                    "captures" to captures.toString()
                ))
            }
        }
        
        Bukkit.broadcastMessage("")
        Bukkit.broadcastMessage(languageManager.getResultMessage("ranking-title"))
        
        // キルランキング
        Bukkit.broadcastMessage(languageManager.getResultMessage("ranking-kills-header"))
        killsRanking.forEachIndexed { index, player ->
            val kills = playerKills[player.uniqueId] ?: 0
            val deaths = playerDeaths[player.uniqueId] ?: 0
            val kdRatio = getPlayerKDRatio(player)
            val teamColor = if (playerTeams[player.uniqueId] == Team.RED) ChatColor.RED else ChatColor.BLUE
            val rank = index + 1
            Bukkit.broadcastMessage(languageManager.getResultMessage("ranking-kills-entry",
                "rank" to rank.toString(),
                "player" to player.name,
                "color" to teamColor.toString(),
                "kills" to kills.toString(),
                "deaths" to deaths.toString(),
                "kd" to String.format("%.2f", kdRatio)
            ))
        }
        
        Bukkit.broadcastMessage("")
        
        // 旗奪取ランキング
        Bukkit.broadcastMessage(languageManager.getResultMessage("ranking-captures-header"))
        capturesRanking.forEachIndexed { index, player ->
            val captures = playerFlagCaptures[player.uniqueId] ?: 0
            val teamColor = if (playerTeams[player.uniqueId] == Team.RED) ChatColor.RED else ChatColor.BLUE
            val rank = index + 1
            Bukkit.broadcastMessage(languageManager.getResultMessage("ranking-captures-entry",
                "rank" to rank.toString(),
                "player" to player.name,
                "color" to teamColor.toString(),
                "captures" to captures.toString()
            ))
        }
        
        Bukkit.broadcastMessage("")
    }
    
    // タブリストの名前を更新
    fun updatePlayerTabListName(player: Player) {
        val team = playerTeams[player.uniqueId]
        if (team != null) {
            val teamColor = if (team == Team.RED) ChatColor.RED else ChatColor.BLUE
            val teamPrefix = if (team == Team.RED) "[R] " else "[B] "
            
            // 統計情報を取得
            val kills = getPlayerKills(player)
            val deaths = getPlayerDeaths(player)
            val captures = getPlayerFlagCaptures(player)
            
            // 戦闘フェーズ中は詳細統計を表示
            val statsDisplay = if (currentPhase == GamePhase.COMBAT || currentPhase == GamePhase.RESULT) {
                " ${ChatColor.GRAY}(${ChatColor.GREEN}${kills}K${ChatColor.GRAY}/${ChatColor.RED}${deaths}D${ChatColor.GRAY})"
            } else {
                ""
            }
            
            player.setPlayerListName("${teamColor}${teamPrefix}${player.name}${statsDisplay}")
        } else {
            player.setPlayerListName("${ChatColor.WHITE}${player.name}")
        }
    }
    
    // タブリストの名前をリセット
    fun resetPlayerTabListName(player: Player) {
        player.setPlayerListName(player.name)
    }
    
    // 全プレイヤーのタブリストを更新
    fun updateAllPlayersTabList() {
        Bukkit.getOnlinePlayers().forEach { player ->
            updatePlayerTabListName(player)
            updatePlayerTabListHeaderFooter(player)
        }
    }
    
    // タブリストのヘッダーとフッターを更新
    fun updatePlayerTabListHeaderFooter(player: Player) {
        // ヘッダー: ゲーム状況
        val phaseDisplayName = when(currentPhase) {
            GamePhase.BUILD -> languageManager.getUIMessage("phase-build")
            GamePhase.COMBAT -> languageManager.getUIMessage("phase-combat")
            GamePhase.RESULT -> languageManager.getUIMessage("phase-result")
        }
        
        val gameStateDisplayName = when(gameState) {
            GameState.WAITING -> languageManager.getGameStateMessage("waiting")
            GameState.STARTING -> languageManager.getGameStateMessage("starting")
            GameState.RUNNING -> languageManager.getGameStateMessage("running")
            GameState.ENDING -> languageManager.getGameStateMessage("ending")
        }
        
        val header = when(gameState) {
            GameState.WAITING -> {
                "${ChatColor.GOLD}✦ Capture The Flag ✦\n" +
                "${ChatColor.WHITE}Status: ${ChatColor.YELLOW}${gameStateDisplayName}\n" +
                "${ChatColor.GRAY}Waiting for players..."
            }
            GameState.STARTING -> {
                "${ChatColor.GOLD}✦ Capture The Flag ✦\n" +
                "${ChatColor.WHITE}Status: ${ChatColor.GREEN}${gameStateDisplayName}\n" +
                "${ChatColor.GRAY}Game starting..."
            }
            GameState.RUNNING -> {
                "${ChatColor.GOLD}✦ Capture The Flag ✦\n" +
                "${ChatColor.WHITE}Phase: ${getPhaseColor()}${phaseDisplayName}\n" +
                "${ChatColor.WHITE}Time: ${ChatColor.GREEN}${formatTime(timeRemaining)}"
            }
            GameState.ENDING -> {
                "${ChatColor.GOLD}✦ Capture The Flag ✦\n" +
                "${ChatColor.WHITE}Status: ${ChatColor.RED}${gameStateDisplayName}\n" +
                "${ChatColor.GRAY}Game ending..."
            }
        }
        
        // フッター: チームスコア
        val footer = when(gameState) {
            GameState.RUNNING, GameState.ENDING -> {
                "${ChatColor.RED}Red Team: ${scores[Team.RED]} ${ChatColor.GRAY}| ${ChatColor.BLUE}Blue Team: ${scores[Team.BLUE]}\n" +
                "${ChatColor.GRAY}Your Team: ${getPlayerTeamDisplay(player)}"
            }
            GameState.WAITING, GameState.STARTING -> {
                val (redSize, blueSize) = getTeamSizes()
                val maxPlayersPerTeam = plugin.config.getInt("game.max-players-per-team", 10)
                "${ChatColor.RED}Red: ${redSize}/${maxPlayersPerTeam} ${ChatColor.GRAY}| ${ChatColor.BLUE}Blue: ${blueSize}/${maxPlayersPerTeam}\n" +
                "${ChatColor.YELLOW}Use '/ctf join <red|blue>' to join a team!"
            }
        }
        
        player.setPlayerListHeader(header)
        player.setPlayerListFooter(footer)
    }
    
    // フェーズに応じた色を取得
    private fun getPhaseColor(): ChatColor {
        return when(currentPhase) {
            GamePhase.BUILD -> ChatColor.YELLOW
            GamePhase.COMBAT -> ChatColor.RED
            GamePhase.RESULT -> ChatColor.GOLD
        }
    }
    
    // プレイヤーのチーム表示を取得
    private fun getPlayerTeamDisplay(player: Player): String {
        val team = playerTeams[player.uniqueId]
        return if (team != null) {
            val teamColor = if (team == Team.RED) ChatColor.RED else ChatColor.BLUE
            val teamName = if (team == Team.RED) "RED" else "BLUE"
            "${teamColor}${teamName}"
        } else {
            "${ChatColor.GRAY}None"
        }
    }
    
    // 建築フェーズのゲームモードを取得
    private fun getBuildPhaseGameMode(): GameMode {
        val gameModeString = plugin.config.getString("phases.build-phase-gamemode", "ADVENTURE")
        
        return try {
            when (gameModeString?.uppercase()) {
                "SURVIVAL" -> GameMode.SURVIVAL
                "CREATIVE" -> GameMode.CREATIVE
                "ADVENTURE" -> GameMode.ADVENTURE
                "SPECTATOR" -> GameMode.SPECTATOR
                else -> {
                    plugin.logger.warning("Invalid build-phase-gamemode '${gameModeString}' in config.yml. Using ADVENTURE as default.")
                    GameMode.ADVENTURE
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error parsing build-phase-gamemode from config.yml: ${e.message}. Using ADVENTURE as default.")
            GameMode.ADVENTURE
        }
    }
    
    // スポーンエリアのビーコンとカラーブロックを設置
    private fun setupSpawnAreas() {
        Team.values().forEach { team ->
            teamSpawns[team]?.let { spawnLocation ->
                setupTeamSpawnArea(team, spawnLocation)
            }
        }
    }
    
    // チームスポーンエリアの設置
    private fun setupTeamSpawnArea(team: Team, spawnLocation: Location) {
        val world = spawnLocation.world ?: return
        
        // チーム色のブロック材質を決定
        val teamBlock = if (team == Team.RED) Material.RED_WOOL else Material.BLUE_WOOL
        val teamConcrete = if (team == Team.RED) Material.RED_CONCRETE else Material.BLUE_CONCRETE
        
        // スポーン地点の足元にチーム色ブロックを設置（3x3エリア）
        for (x in -1..1) {
            for (z in -1..1) {
                val blockLocation = spawnLocation.clone().add(x.toDouble(), -1.0, z.toDouble())
                blockLocation.block.type = teamConcrete
            }
        }
        
        // ビーコンを設置（スポーン地点の1ブロック上）
        val beaconLocation = spawnLocation.clone().add(0.0, 1.0, 0.0)
        beaconLocation.block.type = Material.BEACON
        spawnBeacons[team] = beaconLocation
        
        // ビーコンベースを設置（チーム色ブロックで）
        for (x in -1..1) {
            for (z in -1..1) {
                val baseLocation = spawnLocation.clone().add(x.toDouble(), 0.0, z.toDouble())
                baseLocation.block.type = teamBlock
            }
        }
        
        // 保護エリアとして登録
        spawnProtectionAreas[team] = spawnLocation
        
        plugin.logger.info("Setup spawn area for ${team} team at ${spawnLocation.blockX}, ${spawnLocation.blockY}, ${spawnLocation.blockZ}")
    }
    
    // スポーンエリアの設置物をクリア
    private fun clearSpawnAreas() {
        spawnBeacons.values.forEach { beaconLocation ->
            // ビーコンを削除
            beaconLocation.block.type = Material.AIR
            
            // ベースブロックを削除（3x3）
            for (x in -1..1) {
                for (z in -1..1) {
                    val baseLocation = beaconLocation.clone().add(x.toDouble(), -1.0, z.toDouble())
                    if (baseLocation.block.type == Material.RED_WOOL || 
                        baseLocation.block.type == Material.BLUE_WOOL) {
                        baseLocation.block.type = Material.AIR
                    }
                }
            }
            
            // 足元のコンクリートブロックを削除（3x3）
            for (x in -1..1) {
                for (z in -1..1) {
                    val floorLocation = beaconLocation.clone().add(x.toDouble(), -2.0, z.toDouble())
                    if (floorLocation.block.type == Material.RED_CONCRETE || 
                        floorLocation.block.type == Material.BLUE_CONCRETE) {
                        floorLocation.block.type = Material.AIR
                    }
                }
            }
        }
        
        spawnBeacons.clear()
        spawnProtectionAreas.clear()
        
        plugin.logger.info("Cleared all spawn areas")
    }
    
    // スポーン保護エリア内かどうかをチェック
    fun isInSpawnProtection(location: Location): Boolean {
        if (!plugin.config.getBoolean("world.spawn-protection.enabled", true)) {
            return false
        }
        
        val radius = plugin.config.getInt("world.spawn-protection.radius", 5)
        val height = plugin.config.getInt("world.spawn-protection.height", 3)
        
        return spawnProtectionAreas.values.any { spawnLocation ->
            location.world == spawnLocation.world &&
            location.distance(spawnLocation) <= radius &&
            Math.abs(location.blockY - spawnLocation.blockY) <= height
        }
    }
    
    // どのチームのスポーン保護エリアかを取得
    fun getSpawnProtectionTeam(location: Location): Team? {
        if (!plugin.config.getBoolean("world.spawn-protection.enabled", true)) {
            return null
        }
        
        val radius = plugin.config.getInt("world.spawn-protection.radius", 5)
        val height = plugin.config.getInt("world.spawn-protection.height", 3)
        
        return spawnProtectionAreas.entries.find { (_, spawnLocation) ->
            location.world == spawnLocation.world &&
            location.distance(spawnLocation) <= radius &&
            Math.abs(location.blockY - spawnLocation.blockY) <= height
        }?.key
    }
    
    // 旗とスポーンの距離を検証
    private fun validateFlagSpawnDistance(newLocation: Location, team: Team, isFlag: Boolean): Boolean {
        val minDistance = plugin.config.getDouble("world.spawn-protection.min-flag-spawn-distance", 15.0)
        
        if (isFlag) {
            // 旗を設定する場合、スポーンとの距離をチェック
            val spawnLocation = teamSpawns[team]
            if (spawnLocation != null && spawnLocation.world == newLocation.world) {
                val distance = newLocation.distance(spawnLocation)
                if (distance < minDistance) {
                    plugin.logger.warning("Flag location too close to spawn (distance: ${String.format("%.1f", distance)}, minimum: ${minDistance})")
                    return false
                }
            }
            
            // 他のチームのスポーンとの距離もチェック
            teamSpawns.entries.forEach { (otherTeam, otherSpawn) ->
                if (otherTeam != team && otherSpawn.world == newLocation.world) {
                    val distance = newLocation.distance(otherSpawn)
                    if (distance < minDistance) {
                        plugin.logger.warning("Flag location too close to ${otherTeam} team spawn (distance: ${String.format("%.1f", distance)}, minimum: ${minDistance})")
                        return false
                    }
                }
            }
        } else {
            // スポーンを設定する場合、旗との距離をチェック
            val flagLocation = flagBases[team]
            if (flagLocation != null && flagLocation.world == newLocation.world) {
                val distance = newLocation.distance(flagLocation)
                if (distance < minDistance) {
                    plugin.logger.warning("Spawn location too close to flag (distance: ${String.format("%.1f", distance)}, minimum: ${minDistance})")
                    return false
                }
            }
            
            // 他のチームの旗との距離もチェック
            flagBases.entries.forEach { (otherTeam, otherFlag) ->
                if (otherTeam != team && otherFlag.world == newLocation.world) {
                    val distance = newLocation.distance(otherFlag)
                    if (distance < minDistance) {
                        plugin.logger.warning("Spawn location too close to ${otherTeam} team flag (distance: ${String.format("%.1f", distance)}, minimum: ${minDistance})")
                        return false
                    }
                }
            }
        }
        
        return true
    }
    
    // 距離検証用のパブリックメソッド（コマンドから使用）
    fun validateLocationDistance(location: Location, team: Team, isFlag: Boolean): Pair<Boolean, String?> {
        val minDistance = plugin.config.getDouble("world.spawn-protection.min-flag-spawn-distance", 15.0)
        
        if (isFlag) {
            // 旗設定の場合
            val spawnLocation = teamSpawns[team]
            if (spawnLocation != null && spawnLocation.world == location.world) {
                val distance = location.distance(spawnLocation)
                if (distance < minDistance) {
                    return Pair(false, "Flag location is too close to spawn point! Distance: ${String.format("%.1f", distance)}, minimum required: ${minDistance}")
                }
            }
            
            // 他チームスポーンとの距離チェック
            teamSpawns.entries.forEach { (otherTeam, otherSpawn) ->
                if (otherTeam != team && otherSpawn.world == location.world) {
                    val distance = location.distance(otherSpawn)
                    if (distance < minDistance) {
                        val teamName = if (otherTeam == Team.RED) "RED" else "BLUE"
                        return Pair(false, "Flag location is too close to ${teamName} team spawn! Distance: ${String.format("%.1f", distance)}, minimum required: ${minDistance}")
                    }
                }
            }
        } else {
            // スポーン設定の場合
            val flagLocation = flagBases[team]
            if (flagLocation != null && flagLocation.world == location.world) {
                val distance = location.distance(flagLocation)
                if (distance < minDistance) {
                    return Pair(false, "Spawn location is too close to flag! Distance: ${String.format("%.1f", distance)}, minimum required: ${minDistance}")
                }
            }
            
            // 他チーム旗との距離チェック
            flagBases.entries.forEach { (otherTeam, otherFlag) ->
                if (otherTeam != team && otherFlag.world == location.world) {
                    val distance = location.distance(otherFlag)
                    if (distance < minDistance) {
                        val teamName = if (otherTeam == Team.RED) "RED" else "BLUE"
                        return Pair(false, "Spawn location is too close to ${teamName} team flag! Distance: ${String.format("%.1f", distance)}, minimum required: ${minDistance}")
                    }
                }
            }
        }
        
        return Pair(true, null)
    }
}