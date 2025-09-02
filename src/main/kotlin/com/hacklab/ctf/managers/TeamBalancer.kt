package com.hacklab.ctf.managers

import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import org.bukkit.entity.Player
import java.util.UUID

class TeamBalancer(private val plugin: Main) {
    
    data class PlayerWithScore(
        val uuid: UUID,
        val player: Player,
        val skillScore: Double
    )
    
    data class BalancedTeams(
        val redTeam: List<Player>,
        val blueTeam: List<Player>,
        val redTeamTotalScore: Double,
        val blueTeamTotalScore: Double
    ) {
        fun getScoreDifference(): Double {
            return kotlin.math.abs(redTeamTotalScore - blueTeamTotalScore)
        }
        
        fun getBalanceRatio(): Double {
            val total = redTeamTotalScore + blueTeamTotalScore
            return if (total == 0.0) 1.0 else kotlin.math.min(redTeamTotalScore, blueTeamTotalScore) / kotlin.math.max(redTeamTotalScore, blueTeamTotalScore)
        }
    }
    
    /**
     * プレイヤーのリストをスキルベースでバランスの取れた2チームに分割
     * グリーディアルゴリズムを使用：スコアの高い順に、現在の合計が少ないチームに割り当て
     */
    fun balanceTeams(players: List<Player>): BalancedTeams {
        if (players.isEmpty()) {
            return BalancedTeams(emptyList(), emptyList(), 0.0, 0.0)
        }
        
        // プレイヤーをスキルスコア付きでソート
        val playersWithScores = players.map { player ->
            val stats = plugin.playerStatisticsManager.getPlayerStats(player.uniqueId)
            PlayerWithScore(player.uniqueId, player, stats.calculateSkillScore())
        }.sortedByDescending { it.skillScore }
        
        val redTeam = mutableListOf<Player>()
        val blueTeam = mutableListOf<Player>()
        var redTeamScore = 0.0
        var blueTeamScore = 0.0
        
        // グリーディアルゴリズムでチーム分け
        for (playerWithScore in playersWithScores) {
            if (redTeamScore <= blueTeamScore) {
                redTeam.add(playerWithScore.player)
                redTeamScore += playerWithScore.skillScore
            } else {
                blueTeam.add(playerWithScore.player)
                blueTeamScore += playerWithScore.skillScore
            }
        }
        
        return BalancedTeams(redTeam, blueTeam, redTeamScore, blueTeamScore)
    }
    
    /**
     * 新しいプレイヤーが参加する際、スキルバランスを考慮してチームを選択
     */
    fun selectTeamForPlayer(
        player: Player,
        currentRedTeam: List<Player>,
        currentBlueTeam: List<Player>
    ): Team {
        // チームサイズが大きく異なる場合は、人数の少ない方を優先
        val sizeDifference = kotlin.math.abs(currentRedTeam.size - currentBlueTeam.size)
        if (sizeDifference >= 2) {
            return if (currentRedTeam.size < currentBlueTeam.size) Team.RED else Team.BLUE
        }
        
        // プレイヤーのスキルスコアを取得
        val playerStats = plugin.playerStatisticsManager.getPlayerStats(player.uniqueId)
        val playerScore = playerStats.calculateSkillScore()
        
        // 現在の各チームの合計スコアを計算
        val redTeamScore = currentRedTeam.sumOf { 
            plugin.playerStatisticsManager.getPlayerStats(it.uniqueId).calculateSkillScore()
        }
        val blueTeamScore = currentBlueTeam.sumOf {
            plugin.playerStatisticsManager.getPlayerStats(it.uniqueId).calculateSkillScore()
        }
        
        // プレイヤーを追加した後のバランスを計算
        val redTeamNewScore = redTeamScore + playerScore
        val blueTeamNewScore = blueTeamScore + playerScore
        
        val redDifference = kotlin.math.abs(redTeamNewScore - blueTeamScore)
        val blueDifference = kotlin.math.abs(blueTeamNewScore - redTeamScore)
        
        // よりバランスの良い方を選択
        return when {
            redDifference < blueDifference -> Team.RED
            blueDifference < redDifference -> Team.BLUE
            currentRedTeam.size <= currentBlueTeam.size -> Team.RED
            else -> Team.BLUE
        }
    }
    
    /**
     * 現在のチーム構成のバランス情報を取得
     */
    fun getTeamBalanceInfo(redTeam: List<Player>, blueTeam: List<Player>): Map<String, Any> {
        val redTeamScore = redTeam.sumOf {
            plugin.playerStatisticsManager.getPlayerStats(it.uniqueId).calculateSkillScore()
        }
        val blueTeamScore = blueTeam.sumOf {
            plugin.playerStatisticsManager.getPlayerStats(it.uniqueId).calculateSkillScore()
        }
        
        val totalScore = redTeamScore + blueTeamScore
        val averageScore = if (redTeam.size + blueTeam.size > 0) {
            totalScore / (redTeam.size + blueTeam.size)
        } else 0.0
        
        return mapOf(
            "redTeamSize" to redTeam.size,
            "blueTeamSize" to blueTeam.size,
            "redTeamScore" to redTeamScore,
            "blueTeamScore" to blueTeamScore,
            "scoreDifference" to kotlin.math.abs(redTeamScore - blueTeamScore),
            "balanceRatio" to if (totalScore == 0.0) 1.0 else 
                kotlin.math.min(redTeamScore, blueTeamScore) / kotlin.math.max(redTeamScore, blueTeamScore),
            "averageScore" to averageScore,
            "redTeamAverageScore" to if (redTeam.isEmpty()) 0.0 else redTeamScore / redTeam.size,
            "blueTeamAverageScore" to if (blueTeam.isEmpty()) 0.0 else blueTeamScore / blueTeam.size
        )
    }
    
    /**
     * チーム再バランスが必要かどうか判定
     */
    fun isRebalanceNeeded(redTeam: List<Player>, blueTeam: List<Player>, threshold: Double = 0.3): Boolean {
        val info = getTeamBalanceInfo(redTeam, blueTeam)
        val balanceRatio = info["balanceRatio"] as Double
        
        // バランス比率が閾値を下回る場合、再バランスが必要
        return balanceRatio < (1.0 - threshold)
    }
    
    /**
     * プレイヤーの移動によるバランス改善のシミュレーション
     */
    fun simulatePlayerSwap(
        player: Player,
        fromTeam: List<Player>,
        toTeam: List<Player>,
        otherTeamPlayers: List<Player>
    ): Double {
        val playerScore = plugin.playerStatisticsManager.getPlayerStats(player.uniqueId).calculateSkillScore()
        
        val newFromTeamScore = fromTeam.filterNot { it == player }.sumOf {
            plugin.playerStatisticsManager.getPlayerStats(it.uniqueId).calculateSkillScore()
        }
        val newToTeamScore = toTeam.sumOf {
            plugin.playerStatisticsManager.getPlayerStats(it.uniqueId).calculateSkillScore()
        } + playerScore
        
        return kotlin.math.abs(newFromTeamScore - newToTeamScore)
    }
}