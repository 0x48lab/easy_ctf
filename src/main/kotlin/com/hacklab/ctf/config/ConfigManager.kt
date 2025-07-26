package com.hacklab.ctf.config

import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.MatchMode
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * ゲーム設定の保存・読み込みを管理
 */
class ConfigManager(private val plugin: Main) {
    
    private val gamesDir = File(plugin.dataFolder, "games")
    
    init {
        if (!gamesDir.exists()) {
            gamesDir.mkdirs()
        }
    }
    
    /**
     * ゲーム設定を保存
     */
    fun saveConfig(config: GameConfig) {
        val file = File(gamesDir, "${config.name.lowercase()}.yml")
        val yaml = YamlConfiguration()
        
        // 基本情報
        yaml.set("name", config.name)
        yaml.set("created", config.createdAt)
        yaml.set("world", config.world.name)
        
        // 位置情報
        config.redFlagLocation?.let {
            yaml.set("teams.red.flag-location.x", it.x)
            yaml.set("teams.red.flag-location.y", it.y)
            yaml.set("teams.red.flag-location.z", it.z)
        }
        config.redSpawnLocation?.let {
            yaml.set("teams.red.spawn-location.x", it.x)
            yaml.set("teams.red.spawn-location.y", it.y)
            yaml.set("teams.red.spawn-location.z", it.z)
        }
        config.blueFlagLocation?.let {
            yaml.set("teams.blue.flag-location.x", it.x)
            yaml.set("teams.blue.flag-location.y", it.y)
            yaml.set("teams.blue.flag-location.z", it.z)
        }
        config.blueSpawnLocation?.let {
            yaml.set("teams.blue.spawn-location.x", it.x)
            yaml.set("teams.blue.spawn-location.y", it.y)
            yaml.set("teams.blue.spawn-location.z", it.z)
        }
        
        // ゲーム設定
        yaml.set("settings.min-players", config.minPlayers)
        yaml.set("settings.max-players-per-team", config.maxPlayersPerTeam)
        yaml.set("settings.respawn-delay", config.respawnDelay)
        yaml.set("settings.phases.build-duration", config.buildDuration)
        yaml.set("settings.phases.build-gamemode", config.buildPhaseGameMode)
        yaml.set("settings.phases.combat-duration", config.combatDuration)
        yaml.set("settings.phases.result-duration", config.resultDuration)
        
        // マッチ設定
        yaml.set("settings.match.mode", config.matchMode.name)
        yaml.set("settings.match.target", config.matchTarget)
        yaml.set("settings.match.interval-duration", config.matchIntervalDuration)
        
        yaml.save(file)
    }
    
    /**
     * ゲーム設定を読み込み
     */
    fun loadConfig(name: String): GameConfig? {
        val file = File(gamesDir, "${name.lowercase()}.yml")
        if (!file.exists()) return null
        
        val yaml = YamlConfiguration.loadConfiguration(file)
        val worldName = yaml.getString("world") ?: return null
        val world = plugin.server.getWorld(worldName) ?: return null
        
        val config = GameConfig(
            name = yaml.getString("name") ?: name,
            world = world,
            createdAt = yaml.getLong("created", System.currentTimeMillis())
        )
        
        // 位置情報の読み込み
        if (yaml.contains("teams.red.flag-location")) {
            config.redFlagLocation = Location(
                world,
                yaml.getDouble("teams.red.flag-location.x"),
                yaml.getDouble("teams.red.flag-location.y"),
                yaml.getDouble("teams.red.flag-location.z")
            )
        }
        if (yaml.contains("teams.red.spawn-location")) {
            config.redSpawnLocation = Location(
                world,
                yaml.getDouble("teams.red.spawn-location.x"),
                yaml.getDouble("teams.red.spawn-location.y"),
                yaml.getDouble("teams.red.spawn-location.z")
            )
        }
        if (yaml.contains("teams.blue.flag-location")) {
            config.blueFlagLocation = Location(
                world,
                yaml.getDouble("teams.blue.flag-location.x"),
                yaml.getDouble("teams.blue.flag-location.y"),
                yaml.getDouble("teams.blue.flag-location.z")
            )
        }
        if (yaml.contains("teams.blue.spawn-location")) {
            config.blueSpawnLocation = Location(
                world,
                yaml.getDouble("teams.blue.spawn-location.x"),
                yaml.getDouble("teams.blue.spawn-location.y"),
                yaml.getDouble("teams.blue.spawn-location.z")
            )
        }
        
        // ゲーム設定の読み込み
        config.minPlayers = yaml.getInt("settings.min-players", config.minPlayers)
        config.maxPlayersPerTeam = yaml.getInt("settings.max-players-per-team", config.maxPlayersPerTeam)
        config.respawnDelay = yaml.getInt("settings.respawn-delay", config.respawnDelay)
        config.buildDuration = yaml.getInt("settings.phases.build-duration", config.buildDuration)
        config.buildPhaseGameMode = yaml.getString("settings.phases.build-gamemode", config.buildPhaseGameMode)!!
        config.combatDuration = yaml.getInt("settings.phases.combat-duration", config.combatDuration)
        config.resultDuration = yaml.getInt("settings.phases.result-duration", config.resultDuration)
        
        // マッチ設定の読み込み
        config.matchMode = MatchMode.FIXED_ROUNDS
        config.matchTarget = yaml.getInt("settings.match.target", config.matchTarget)
        config.matchIntervalDuration = yaml.getInt("settings.match.interval-duration", config.matchIntervalDuration)
        
        return config
    }
    
    /**
     * 全ゲーム設定を読み込み
     */
    fun loadAllConfigs(): Map<String, GameConfig> {
        val configs = mutableMapOf<String, GameConfig>()
        
        gamesDir.listFiles { file -> file.extension == "yml" }?.forEach { file ->
            try {
                val name = file.nameWithoutExtension
                loadConfig(name)?.let {
                    configs[name.lowercase()] = it
                }
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load config from ${file.name}: ${e.message}")
            }
        }
        
        return configs
    }
    
    /**
     * ゲーム設定を削除
     */
    fun deleteConfig(name: String): Boolean {
        val file = File(gamesDir, "${name.lowercase()}.yml")
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
    
    /**
     * デフォルト設定を作成
     */
    fun createDefaultConfig(name: String, world: org.bukkit.World): GameConfig {
        return GameConfig(
            name = name,
            world = world,
            minPlayers = plugin.config.getInt("default-game.min-players", 2),
            maxPlayersPerTeam = plugin.config.getInt("default-game.max-players-per-team", 10),
            respawnDelay = plugin.config.getInt("default-game.respawn-delay", 5),
            buildDuration = plugin.config.getInt("default-phases.build-duration", 300),
            buildPhaseGameMode = plugin.config.getString("default-phases.build-phase-gamemode", "ADVENTURE")!!,
            combatDuration = plugin.config.getInt("default-phases.combat-duration", 600),
            resultDuration = plugin.config.getInt("default-phases.result-duration", 60),
            matchMode = MatchMode.FIXED_ROUNDS,
            matchTarget = plugin.config.getInt("match.default-target", 3),
            matchIntervalDuration = plugin.config.getInt("match.interval-duration", 30)
        )
    }
}