package com.hacklab.ctf

import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.MatchMode
import com.hacklab.ctf.utils.Team
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Match(
    val name: String,
    private val plugin: Main,
    val mode: MatchMode,
    val target: Int,
    val intervalDuration: Int
) {
    // マッチ全体の管理
    private val games = mutableListOf<Game>()
    private var currentGameIndex = 0
    private val matchWins = ConcurrentHashMap<Team, Int>()
    private val playerGames = ConcurrentHashMap<UUID, Game>()
    
    // チーム共有通貨
    private val teamCurrency = ConcurrentHashMap<Team, Int>()
    
    // マッチ状態
    var isActive = false
        private set
    var isInInterval = false
        private set
    
    // UI要素
    private var matchScoreboard: Scoreboard? = null
    private var matchBossBar: BossBar? = null
    
    // 設定値
    var buildDuration: Int = 300
    var combatDuration: Int = 600
    var buildPhaseGameMode: String = "ADVENTURE"
    
    init {
        matchWins[Team.RED] = 0
        matchWins[Team.BLUE] = 0
        teamCurrency[Team.RED] = 0
        teamCurrency[Team.BLUE] = 0
    }
    
    fun startMatch(initialGame: Game) {
        if (isActive) return
        
        isActive = true
        games.clear()
        games.add(initialGame)
        currentGameIndex = 0
        
        // 初期通貨を付与
        teamCurrency[Team.RED] = plugin.config.getInt("currency.initial", 50)
        teamCurrency[Team.BLUE] = plugin.config.getInt("currency.initial", 50)
        
        setupMatchUI()
        startCurrentGame()
    }
    
    private fun startCurrentGame() {
        val currentGame = games[currentGameIndex]
        
        // ゲーム設定をマッチ設定で上書き
        currentGame.buildDuration = buildDuration
        currentGame.combatDuration = combatDuration
        currentGame.buildPhaseGameMode = buildPhaseGameMode
        
        // ゲーム終了時のコールバックを設定
        currentGame.setGameEndCallback { winner ->
            handleGameEnd(winner)
        }
        
        // マッチのスコアボードと通貨システムを設定
        currentGame.setMatchContext(this)
        
        // 現在のゲーム番号を通知
        currentGame.getAllPlayers().forEach { player ->
            player.sendMessage(Component.text("=== ${getMatchStatus()} ===").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            player.sendMessage(Component.text("現在のスコア: ").color(NamedTextColor.WHITE)
                .append(Component.text("赤 ${matchWins[Team.RED]} ").color(NamedTextColor.RED))
                .append(Component.text("- ").color(NamedTextColor.WHITE))
                .append(Component.text("青 ${matchWins[Team.BLUE]}").color(NamedTextColor.BLUE)))
            when (mode) {
                MatchMode.FIRST_TO_X -> player.sendMessage(Component.text("先に${target}勝したチームの勝利です！").color(NamedTextColor.YELLOW))
                MatchMode.FIXED_ROUNDS -> player.sendMessage(Component.text("全${target}ゲームを行い、勝利数の多いチームが勝ちです！").color(NamedTextColor.YELLOW))
            }
        }
        
        currentGame.start()
    }
    
    private fun handleGameEnd(winner: Team?) {
        // 勝利チームのカウントを増やす
        winner?.let {
            matchWins[it] = (matchWins[it] ?: 0) + 1
            updateMatchScore()
        }
        
        // 戦闘フェーズ終了ボーナス
        val phaseEndBonus = plugin.config.getInt("currency.phase-end-bonus", 50)
        teamCurrency[Team.RED] = (teamCurrency[Team.RED] ?: 0) + phaseEndBonus
        teamCurrency[Team.BLUE] = (teamCurrency[Team.BLUE] ?: 0) + phaseEndBonus
        
        // デバッグログ
        plugin.logger.info("Match $name: Game ended. Winner: $winner")
        plugin.logger.info("Match $name: Current wins - RED: ${matchWins[Team.RED]}, BLUE: ${matchWins[Team.BLUE]}")
        plugin.logger.info("Match $name: Target: $target, Mode: $mode")
        plugin.logger.info("Match $name: Is match complete? ${isMatchComplete()}")
        
        // マッチ終了判定
        if (isMatchComplete()) {
            // 現在のゲームを停止してからマッチを終了
            getCurrentGame()?.stop()
            endMatch()
        } else {
            // 次のゲームの準備（インターバル開始）
            startInterval()
        }
    }
    
    private fun isMatchComplete(): Boolean {
        return when (mode) {
            MatchMode.FIRST_TO_X -> {
                matchWins.values.any { it >= target }
            }
            MatchMode.FIXED_ROUNDS -> {
                games.size >= target
            }
        }
    }
    
    private fun startInterval() {
        isInInterval = true
        val currentGame = games[currentGameIndex]
        
        // インターバルBossBar
        val nextGameNum = getCurrentGameNumber() + 1
        val status = when (mode) {
            MatchMode.FIRST_TO_X -> "[先取$target] 第${nextGameNum}ゲーム準備中"
            MatchMode.FIXED_ROUNDS -> "[${nextGameNum}/$target] 次のゲーム準備中"
        }
        
        // 現在のマッチスコア
        val winsText = " | 勝利数: 赤${matchWins[Team.RED]}勝 - 青${matchWins[Team.BLUE]}勝"
        
        matchBossBar?.setTitle("$status$winsText | 残り${intervalDuration}秒")
        matchBossBar?.color = BarColor.YELLOW
        
        object : BukkitRunnable() {
            var timeLeft = intervalDuration
            
            override fun run() {
                if (timeLeft <= 0) {
                    cancel()
                    isInInterval = false
                    prepareNextGame()
                    return
                }
                
                matchBossBar?.setTitle("$status$winsText | 残り${timeLeft}秒")
                matchBossBar?.progress = timeLeft.toDouble() / intervalDuration
                
                // カウントダウン通知
                if (timeLeft <= 5) {
                    currentGame.getPlayers().forEach { player ->
                        val title = Title.title(
                            Component.text("${timeLeft}").color(NamedTextColor.YELLOW),
                            Component.text("次のゲームが始まります").color(NamedTextColor.GRAY),
                            Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(1), java.time.Duration.ofMillis(500))
                        )
                        player.showTitle(title)
                    }
                }
                
                timeLeft--
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }
    
    private fun prepareNextGame() {
        val previousGame = games[currentGameIndex]
        
        // 前のゲームを停止（リザルトフェーズまで終了しているはず）
        if (previousGame.state != GameState.WAITING) {
            previousGame.stop()
        }
        
        // 新しいゲームを作成（同じ設定をコピー）
        val newGame = Game(
            gameName = name,
            plugin = plugin,
            world = previousGame.world
        ).apply {
            // 位置設定をコピー
            setRedFlagLocation(previousGame.getRedFlagLocation()!!)
            previousGame.getRedSpawnLocation()?.let { setRedSpawnLocation(it) }
            setBlueFlagLocation(previousGame.getBlueFlagLocation()!!)
            previousGame.getBlueSpawnLocation()?.let { setBlueSpawnLocation(it) }
            
            // プレイヤーを移行
            previousGame.getPlayers().forEach { player ->
                val team = previousGame.getPlayerTeam(player)
                if (team != null) {
                    this.addPlayer(player, team)
                }
            }
        }
        
        games.add(newGame)
        currentGameIndex++
        
        // 通貨をリセット
        teamCurrency[Team.RED] = plugin.config.getInt("currency.initial", 50)
        teamCurrency[Team.BLUE] = plugin.config.getInt("currency.initial", 50)
        
        startCurrentGame()
    }
    
    private fun endMatch() {
        isActive = false
        
        // 勝者を決定
        val winner = when (mode) {
            MatchMode.FIRST_TO_X -> {
                matchWins.entries.maxByOrNull { it.value }?.key
            }
            MatchMode.FIXED_ROUNDS -> {
                val totalScores = games.groupBy { it.getWinner() }
                    .mapValues { it.value.size }
                totalScores.entries.maxByOrNull { it.value }?.key
            }
        }
        
        // 最終結果を表示
        val currentGame = games.lastOrNull() ?: return
        displayFinalMatchReport(currentGame, winner)
        
        // UIをクリーンアップ
        cleanupMatchUI()
        
        // 現在のゲームが実行中の場合は停止（endMatchはhandleGameEndから呼ばれる場合は既に停止済み）
        if (currentGame.state != GameState.WAITING) {
            currentGame.stop()
        }
    }
    
    fun stopMatch() {
        isActive = false
        games.lastOrNull()?.stop()
        cleanupMatchUI()
    }
    
    private fun setupMatchUI() {
        // マッチ用スコアボード作成は各ゲームで行う
        
        // マッチ用BossBar
        val status = when (mode) {
            MatchMode.FIRST_TO_X -> "[先取$target] "
            MatchMode.FIXED_ROUNDS -> "[1/$target] "
        }
        matchBossBar = Bukkit.createBossBar(
            "${status}赤 0 - 青 0",
            BarColor.PURPLE,
            BarStyle.SOLID
        )
    }
    
    private fun cleanupMatchUI() {
        matchBossBar?.removeAll()
        matchBossBar = null
    }
    
    fun updateMatchScore() {
        val status = when (mode) {
            MatchMode.FIRST_TO_X -> "[先取$target] 第${getCurrentGameNumber()}ゲーム"
            MatchMode.FIXED_ROUNDS -> "[${getCurrentGameNumber()}/${target}ゲーム]"
        }
        
        // 現在のゲーム情報
        val currentGame = getCurrentGame()
        val gameScore = currentGame?.let { 
            " | 現在: 赤${it.getRedScore()} - 青${it.getBlueScore()}"
        } ?: ""
        
        // マッチ全体の勝利数
        val winsText = " | 勝利数: 赤${matchWins[Team.RED]}勝 - 青${matchWins[Team.BLUE]}勝"
        
        // リード情報
        val leadInfo = when {
            matchWins[Team.RED]!! > matchWins[Team.BLUE]!! -> " [赤リード]"
            matchWins[Team.BLUE]!! > matchWins[Team.RED]!! -> " [青リード]"
            else -> " [互角]"
        }
        
        matchBossBar?.setTitle("$status$gameScore$winsText$leadInfo")
        
        // バーの色をリードチームに応じて変更
        matchBossBar?.color = when {
            matchWins[Team.RED]!! > matchWins[Team.BLUE]!! -> BarColor.RED
            matchWins[Team.BLUE]!! > matchWins[Team.RED]!! -> BarColor.BLUE
            else -> BarColor.PURPLE
        }
    }
    
    // 通貨システム
    fun getTeamCurrency(team: Team): Int = teamCurrency[team] ?: 0
    
    fun addTeamCurrency(team: Team, amount: Int, reason: String = "") {
        val current = teamCurrency[team] ?: 0
        teamCurrency[team] = current + amount
        
        // チームメンバーに通知
        getCurrentGame()?.getTeamPlayers(team)?.forEach { player ->
            if (reason.isNotEmpty()) {
                player.sendMessage(Component.text("[チーム] ").color(NamedTextColor.GREEN)
                    .append(Component.text("$reason ").color(NamedTextColor.WHITE))
                    .append(Component.text("(+${amount}G)").color(NamedTextColor.GREEN)))
            }
            player.sendMessage(Component.text("[チーム] ").color(NamedTextColor.GREEN)
                .append(Component.text("残高: ${teamCurrency[team]}G").color(NamedTextColor.WHITE)))
        }
    }
    
    fun spendTeamCurrency(team: Team, amount: Int, player: Player, itemName: String): Boolean {
        val current = teamCurrency[team] ?: 0
        if (current < amount) return false
        
        teamCurrency[team] = current - amount
        
        // チームメンバーに通知
        getCurrentGame()?.getTeamPlayers(team)?.forEach { p ->
            p.sendMessage(Component.text("[チーム] ").color(NamedTextColor.YELLOW)
                .append(Component.text("${player.name} が $itemName を購入しました ").color(NamedTextColor.WHITE))
                .append(Component.text("(-${amount}G)").color(NamedTextColor.RED)))
            p.sendMessage(Component.text("[チーム] ").color(NamedTextColor.YELLOW)
                .append(Component.text("残高: ${teamCurrency[team]}G").color(NamedTextColor.WHITE)))
        }
        
        return true
    }
    
    fun getCurrentGame(): Game? = if (currentGameIndex < games.size) games[currentGameIndex] else null
    
    fun getMatchWins(): Map<Team, Int> = matchWins.toMap()
    
    fun getCurrentGameNumber(): Int = currentGameIndex + 1
    
    fun getTotalGamesNeeded(): Int {
        return when (mode) {
            MatchMode.FIRST_TO_X -> {
                // 先取モード: 最大で (target * 2 - 1) 試合
                val currentTotal = matchWins.values.sum()
                val remaining = target - matchWins.values.maxOrNull()!!
                currentTotal + remaining
            }
            MatchMode.FIXED_ROUNDS -> target
        }
    }
    
    fun getMatchStatus(): String {
        return when (mode) {
            MatchMode.FIRST_TO_X -> "第${getCurrentGameNumber()}ゲーム (先取$target)"
            MatchMode.FIXED_ROUNDS -> "第${getCurrentGameNumber()}ゲーム / 全${target}ゲーム"
        }
    }
    
    fun addPlayerToMatch(player: Player, game: Game) {
        playerGames[player.uniqueId] = game
        matchBossBar?.addPlayer(player)
    }
    
    fun removePlayerFromMatch(player: Player) {
        playerGames.remove(player.uniqueId)
        matchBossBar?.removePlayer(player)
    }
    
    private fun displayFinalMatchReport(currentGame: Game, winner: Team?) {
        currentGame.getPlayers().forEach { player ->
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(Component.text("========== マッチ最終結果 ==========").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            
            // マッチ情報
            player.sendMessage(Component.text("マッチ情報:").color(NamedTextColor.YELLOW))
            player.sendMessage(Component.text("モード: ${mode.displayName}").color(NamedTextColor.WHITE))
            when (mode) {
                MatchMode.FIRST_TO_X -> player.sendMessage(Component.text("勝利条件: 先に${target}勝").color(NamedTextColor.WHITE))
                MatchMode.FIXED_ROUNDS -> player.sendMessage(Component.text("勝利条件: 全${target}ゲーム中最多勝利").color(NamedTextColor.WHITE))
            }
            player.sendMessage(Component.text("実施ゲーム数: ${games.size}ゲーム").color(NamedTextColor.WHITE))
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            
            // 各ゲームの結果
            player.sendMessage(Component.text("ゲーム履歴:").color(NamedTextColor.YELLOW))
            games.forEachIndexed { index, game ->
                val gameWinner = game.getWinner()
                val scoreText = Component.text("  第${index + 1}ゲーム: ")
                    .append(Component.text("${game.getRedScore()} ").color(NamedTextColor.RED))
                    .append(Component.text("- ").color(NamedTextColor.WHITE))
                    .append(Component.text("${game.getBlueScore()} ").color(NamedTextColor.BLUE))
                
                val winnerComponent = if (gameWinner != null) {
                    Component.text("(勝者: ").color(NamedTextColor.WHITE)
                        .append(Component.text(gameWinner.displayName).color(gameWinner.color))
                        .append(Component.text(")").color(NamedTextColor.WHITE))
                } else {
                    Component.text("(引き分け)").color(NamedTextColor.YELLOW)
                }
                
                player.sendMessage(scoreText.append(winnerComponent))
            }
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            
            // 最終スコア
            player.sendMessage(Component.text("最終スコア:").color(NamedTextColor.YELLOW))
            player.sendMessage(Component.text("赤チーム: ${matchWins[Team.RED] ?: 0}勝").color(NamedTextColor.RED))
            player.sendMessage(Component.text("青チーム: ${matchWins[Team.BLUE] ?: 0}勝").color(NamedTextColor.BLUE))
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            
            // チームメンバー
            player.sendMessage(Component.text("チームメンバー:").color(NamedTextColor.YELLOW))
            
            // 赤チーム
            player.sendMessage(Component.text("赤チーム:").color(NamedTextColor.RED))
            currentGame.getTeamPlayers(Team.RED).forEach { p ->
                player.sendMessage(Component.text("  - ${p.name}", NamedTextColor.WHITE))
            }
            
            // 青チーム
            player.sendMessage(Component.text("青チーム:").color(NamedTextColor.BLUE))
            currentGame.getTeamPlayers(Team.BLUE).forEach { p ->
                player.sendMessage(Component.text("  - ${p.name}", NamedTextColor.WHITE))
            }
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            
            // 勝者発表
            if (winner != null) {
                player.sendMessage(Component.text("マッチ勝者: ").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                    .append(Component.text("${winner.displayName}チーム！").color(winner.color).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)))
            } else {
                player.sendMessage(Component.text("マッチ結果: 引き分け！").color(NamedTextColor.YELLOW).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            }
            
            player.sendMessage(Component.text("====================================").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            
            // タイトル表示
            if (winner != null) {
                player.showTitle(Title.title(
                    Component.text("${winner.displayName}の勝利！").color(winner.color).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                    Component.text("マッチ終了").color(NamedTextColor.GOLD),
                    Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(500))
                ))
            } else {
                player.showTitle(Title.title(
                    Component.text("引き分け！").color(NamedTextColor.YELLOW).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                    Component.text("マッチ終了").color(NamedTextColor.GOLD),
                    Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(500))
                ))
            }
        }
    }
}