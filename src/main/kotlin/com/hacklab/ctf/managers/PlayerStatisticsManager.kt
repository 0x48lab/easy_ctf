package com.hacklab.ctf.managers

import com.hacklab.ctf.Main
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

class PlayerStatisticsManager(private val plugin: Main) {
    
    data class PlayerStats(
        val uuid: UUID,
        var totalKills: Int = 0,
        var totalDeaths: Int = 0,
        var totalCaptures: Int = 0,
        var gamesPlayed: Int = 0,
        var gamesWon: Int = 0,
        var lastPlayed: Long = System.currentTimeMillis()
    ) {
        fun calculateSkillScore(): Double {
            return (totalKills * 10.0) + (totalCaptures * 30.0) - (totalDeaths * 5.0)
        }
        
        fun getKDRatio(): Double {
            return if (totalDeaths == 0) totalKills.toDouble() else totalKills.toDouble() / totalDeaths
        }
        
        fun getWinRate(): Double {
            return if (gamesPlayed == 0) 0.0 else (gamesWon.toDouble() / gamesPlayed) * 100
        }
    }
    
    private val statsFile = File(plugin.dataFolder, "player_stats.yml")
    private val playerStats = mutableMapOf<UUID, PlayerStats>()
    
    init {
        loadStats()
    }
    
    fun loadStats() {
        if (!statsFile.exists()) {
            statsFile.createNewFile()
            return
        }
        
        val config = YamlConfiguration.loadConfiguration(statsFile)
        val statsSection = config.getConfigurationSection("players") ?: return
        
        for (key in statsSection.getKeys(false)) {
            try {
                val uuid = UUID.fromString(key)
                val stats = PlayerStats(
                    uuid = uuid,
                    totalKills = config.getInt("players.$key.kills", 0),
                    totalDeaths = config.getInt("players.$key.deaths", 0),
                    totalCaptures = config.getInt("players.$key.captures", 0),
                    gamesPlayed = config.getInt("players.$key.games_played", 0),
                    gamesWon = config.getInt("players.$key.games_won", 0),
                    lastPlayed = config.getLong("players.$key.last_played", System.currentTimeMillis())
                )
                playerStats[uuid] = stats
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load stats for $key: ${e.message}")
            }
        }
        
        plugin.logger.info("Loaded statistics for ${playerStats.size} players")
    }
    
    fun saveStats() {
        val config = YamlConfiguration()
        
        for ((uuid, stats) in playerStats) {
            val path = "players.$uuid"
            config.set("$path.kills", stats.totalKills)
            config.set("$path.deaths", stats.totalDeaths)
            config.set("$path.captures", stats.totalCaptures)
            config.set("$path.games_played", stats.gamesPlayed)
            config.set("$path.games_won", stats.gamesWon)
            config.set("$path.last_played", stats.lastPlayed)
            
            // プレイヤー名も保存（参照用）
            Bukkit.getOfflinePlayer(uuid).name?.let { name ->
                config.set("$path.last_known_name", name)
            }
        }
        
        config.save(statsFile)
    }
    
    fun getPlayerStats(uuid: UUID): PlayerStats {
        return playerStats.getOrPut(uuid) { PlayerStats(uuid) }
    }
    
    fun updatePlayerStats(
        uuid: UUID,
        kills: Int = 0,
        deaths: Int = 0,
        captures: Int = 0,
        gamePlayed: Boolean = false,
        gameWon: Boolean = false
    ) {
        val stats = getPlayerStats(uuid)
        stats.totalKills += kills
        stats.totalDeaths += deaths
        stats.totalCaptures += captures
        if (gamePlayed) {
            stats.gamesPlayed++
            if (gameWon) {
                stats.gamesWon++
            }
        }
        stats.lastPlayed = System.currentTimeMillis()
        
        // 自動保存（パフォーマンスを考慮して、必要に応じて非同期化可能）
        saveStats()
    }
    
    fun resetPlayerStats(uuid: UUID) {
        playerStats[uuid] = PlayerStats(uuid)
        saveStats()
    }
    
    fun getTopPlayersByScore(limit: Int = 10): List<Pair<UUID, Double>> {
        return playerStats.entries
            .map { it.key to it.value.calculateSkillScore() }
            .sortedByDescending { it.second }
            .take(limit)
    }
    
    fun getTopPlayersByKills(limit: Int = 10): List<Pair<UUID, Int>> {
        return playerStats.entries
            .map { it.key to it.value.totalKills }
            .sortedByDescending { it.second }
            .take(limit)
    }
    
    fun getTopPlayersByCaptures(limit: Int = 10): List<Pair<UUID, Int>> {
        return playerStats.entries
            .map { it.key to it.value.totalCaptures }
            .sortedByDescending { it.second }
            .take(limit)
    }
    
    fun getTopPlayersByWinRate(limit: Int = 10, minGames: Int = 5): List<Pair<UUID, Double>> {
        return playerStats.entries
            .filter { it.value.gamesPlayed >= minGames }
            .map { it.key to it.value.getWinRate() }
            .sortedByDescending { it.second }
            .take(limit)
    }
    
    fun getAllPlayerStats(): Map<UUID, PlayerStats> {
        return playerStats.toMap()
    }
    
    fun getPlayerRank(uuid: UUID): Int {
        val allScores = playerStats.entries
            .map { it.key to it.value.calculateSkillScore() }
            .sortedByDescending { it.second }
        
        return allScores.indexOfFirst { it.first == uuid } + 1
    }
}