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
        
        // 複数スポーン地点の保存
        if (config.redSpawnLocations.isNotEmpty()) {
            config.redSpawnLocations.forEachIndexed { index, location ->
                yaml.set("teams.red.spawn-locations.$index.x", location.x)
                yaml.set("teams.red.spawn-locations.$index.y", location.y)
                yaml.set("teams.red.spawn-locations.$index.z", location.z)
            }
        }
        if (config.blueSpawnLocations.isNotEmpty()) {
            config.blueSpawnLocations.forEachIndexed { index, location ->
                yaml.set("teams.blue.spawn-locations.$index.x", location.x)
                yaml.set("teams.blue.spawn-locations.$index.y", location.y)
                yaml.set("teams.blue.spawn-locations.$index.z", location.z)
            }
        }
        
        // ゲーム設定
        yaml.set("settings.auto-start-enabled", config.autoStartEnabled)
        yaml.set("settings.min-players", config.minPlayers)
        yaml.set("settings.max-players-per-team", config.maxPlayersPerTeam)
        
        // リスポーン設定
        yaml.set("settings.respawn-delay-base", config.respawnDelayBase)
        yaml.set("settings.respawn-delay-per-death", config.respawnDelayPerDeath)
        yaml.set("settings.respawn-delay-max", config.respawnDelayMax)
        yaml.set("settings.phases.build-duration", config.buildDuration)
        yaml.set("settings.phases.build-phase-gamemode", config.buildPhaseGameMode)
        yaml.set("settings.phases.combat-duration", config.combatDuration)
        yaml.set("settings.phases.result-duration", config.resultDuration)
        yaml.set("settings.phases.intermediate-duration", config.intermediateDuration)
        yaml.set("settings.phases.build-phase-blocks", config.buildPhaseBlocks)
        yaml.set("settings.phases.combat-phase-blocks", config.combatPhaseBlocks)
        
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
        
        // 複数スポーン地点の読み込み
        if (yaml.contains("teams.red.spawn-locations")) {
            val redSpawnSection = yaml.getConfigurationSection("teams.red.spawn-locations")
            redSpawnSection?.getKeys(false)?.forEach { key ->
                val location = Location(
                    world,
                    yaml.getDouble("teams.red.spawn-locations.$key.x"),
                    yaml.getDouble("teams.red.spawn-locations.$key.y"),
                    yaml.getDouble("teams.red.spawn-locations.$key.z")
                )
                config.redSpawnLocations.add(location)
            }
        }
        if (yaml.contains("teams.blue.spawn-locations")) {
            val blueSpawnSection = yaml.getConfigurationSection("teams.blue.spawn-locations")
            blueSpawnSection?.getKeys(false)?.forEach { key ->
                val location = Location(
                    world,
                    yaml.getDouble("teams.blue.spawn-locations.$key.x"),
                    yaml.getDouble("teams.blue.spawn-locations.$key.y"),
                    yaml.getDouble("teams.blue.spawn-locations.$key.z")
                )
                config.blueSpawnLocations.add(location)
            }
        }
        
        // ゲーム設定の読み込み
        config.autoStartEnabled = yaml.getBoolean("settings.auto-start-enabled", config.autoStartEnabled)
        config.minPlayers = yaml.getInt("settings.min-players", config.minPlayers)
        config.maxPlayersPerTeam = yaml.getInt("settings.max-players-per-team", config.maxPlayersPerTeam)
        
        // リスポーン設定の読み込み
        config.respawnDelayBase = yaml.getInt("settings.respawn-delay-base", config.respawnDelayBase)
        config.respawnDelayPerDeath = yaml.getInt("settings.respawn-delay-per-death", config.respawnDelayPerDeath)
        config.respawnDelayMax = yaml.getInt("settings.respawn-delay-max", config.respawnDelayMax)
        config.buildDuration = yaml.getInt("settings.phases.build-duration", config.buildDuration)
        val gameModeFromFile = yaml.getString("settings.phases.build-phase-gamemode")
        plugin.logger.info("[ConfigManager] Loading game ${name}: build-phase-gamemode from file = $gameModeFromFile, default = ${config.buildPhaseGameMode}")
        config.buildPhaseGameMode = gameModeFromFile ?: config.buildPhaseGameMode
        config.combatDuration = yaml.getInt("settings.phases.combat-duration", config.combatDuration)
        config.resultDuration = yaml.getInt("settings.phases.result-duration", config.resultDuration)
        config.intermediateDuration = yaml.getInt("settings.phases.intermediate-duration", config.intermediateDuration)
        config.buildPhaseBlocks = yaml.getInt("settings.phases.build-phase-blocks", config.buildPhaseBlocks)
        config.combatPhaseBlocks = yaml.getInt("settings.phases.combat-phase-blocks", config.combatPhaseBlocks)
        
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
            autoStartEnabled = plugin.config.getBoolean("default-game.auto-start-enabled", false),
            minPlayers = plugin.config.getInt("default-game.min-players", 2),
            maxPlayersPerTeam = plugin.config.getInt("default-game.max-players-per-team", 10),
            respawnDelayBase = plugin.config.getInt("default-game.respawn-delay-base", 10),
            respawnDelayPerDeath = plugin.config.getInt("default-game.respawn-delay-per-death", 2),
            respawnDelayMax = plugin.config.getInt("default-game.respawn-delay-max", 20),
            buildDuration = plugin.config.getInt("default-phases.build-duration", 120),
            buildPhaseGameMode = plugin.config.getString("default-phases.build-phase-gamemode", "SURVIVAL")!!.also {
                plugin.logger.info("[ConfigManager] buildPhaseGameMode from config: $it")
            },
            combatDuration = plugin.config.getInt("default-phases.combat-duration", 120),
            resultDuration = plugin.config.getInt("default-phases.result-duration", 15),
            matchMode = MatchMode.FIXED_ROUNDS,
            matchTarget = plugin.config.getInt("match.default-target", 3),
            matchIntervalDuration = plugin.config.getInt("match.interval-duration", 30)
        )
    }
}