package com.hacklab.ctf

import com.hacklab.ctf.config.GameConfig
import com.hacklab.ctf.utils.MatchMode
import com.hacklab.ctf.utils.Team
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * マッチシステムのシンプルなラッパー
 * Game内の複雑な処理を避け、マッチの状態管理に特化
 */
class MatchWrapper(
    val config: GameConfig,
    private val plugin: Main
) {
    // マッチ状態
    val matchWins = ConcurrentHashMap<Team, Int>().apply {
        put(Team.RED, 0)
        put(Team.BLUE, 0)
    }
    
    // チーム共有通貨
    val teamCurrency = ConcurrentHashMap<Team, Int>().apply {
        put(Team.RED, plugin.config.getInt("currency.initial", 50))
        put(Team.BLUE, plugin.config.getInt("currency.initial", 50))
    }
    
    // マッチ参加プレイヤー
    val players = ConcurrentHashMap<UUID, Player>()
    
    var currentGameNumber = 1
    var isActive = false
    
    /**
     * ゲーム終了時の処理
     */
    fun onGameEnd(winner: Team?) {
        winner?.let {
            matchWins[it] = (matchWins[it] ?: 0) + 1
        }
        
        // フェーズ終了ボーナス
        val bonus = plugin.config.getInt("currency.phase-end-bonus", 50)
        Team.values().forEach { team ->
            teamCurrency[team] = (teamCurrency[team] ?: 0) + bonus
        }
    }
    
    /**
     * マッチ完了判定
     */
    fun isMatchComplete(): Boolean {
        return currentGameNumber > config.matchTarget
    }
    
    /**
     * マッチ勝者取得
     */
    fun getMatchWinner(): Team? {
        val redWins = matchWins[Team.RED] ?: 0
        val blueWins = matchWins[Team.BLUE] ?: 0
        
        return when {
            redWins > blueWins -> Team.RED
            blueWins > redWins -> Team.BLUE
            else -> null
        }
    }
    
    /**
     * 次のゲームへ
     */
    fun nextGame() {
        currentGameNumber++
    }
    
    /**
     * マッチステータス文字列
     */
    fun getMatchStatus(): String {
        return "ゲーム $currentGameNumber / ${config.matchTarget}"
    }
    
    /**
     * チーム通貨の取得
     */
    fun getTeamCurrency(team: Team): Int = teamCurrency[team] ?: 0
    
    /**
     * チーム通貨の追加
     */
    fun addTeamCurrency(team: Team, amount: Int) {
        teamCurrency[team] = getTeamCurrency(team) + amount
    }
    
    /**
     * チーム通貨の使用
     */
    fun useTeamCurrency(team: Team, amount: Int): Boolean {
        val current = getTeamCurrency(team)
        return if (current >= amount) {
            teamCurrency[team] = current - amount
            true
        } else {
            false
        }
    }
}