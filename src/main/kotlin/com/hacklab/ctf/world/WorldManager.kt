package com.hacklab.ctf.world

import com.hacklab.ctf.Main
import org.bukkit.*
import org.bukkit.generator.ChunkGenerator
import java.io.File
import java.util.Random
import java.util.logging.Level

/**
 * テンポラリワールドの作成・管理を行うクラス
 */
class WorldManager(private val plugin: Main) {
    private val tempWorldPrefix = "ctf_temp_"
    
    fun createTempWorld(gameName: String): World? {
        val worldName = "${tempWorldPrefix}$gameName"
        
        try {
            plugin.logger.info("[WorldManager] Starting creation of temporary world: $worldName")
            
            // 既存のワールドがあれば削除
            val existingWorld = Bukkit.getWorld(worldName)
            if (existingWorld != null) {
                plugin.logger.info("[WorldManager] Existing world found, deleting: $worldName")
                if (!deleteWorld(worldName)) {
                    plugin.logger.severe("[WorldManager] Failed to delete existing world: $worldName")
                    return null
                }
            }
            
            // ワールドフォルダが存在するかチェック
            val worldFolder = File(Bukkit.getWorldContainer(), worldName)
            if (worldFolder.exists()) {
                plugin.logger.info("[WorldManager] World folder exists, attempting to delete: ${worldFolder.absolutePath}")
                if (!deleteWorldFolder(worldFolder)) {
                    plugin.logger.severe("[WorldManager] Failed to delete world folder: ${worldFolder.absolutePath}")
                    return null
                }
            }
            
            // ワールド作成設定
            plugin.logger.info("[WorldManager] Creating WorldCreator for: $worldName")
            val creator = WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)  // 通常ワールド（ただしVoidGeneratorで空に）
                .generateStructures(false)
                .generator(VoidWorldGenerator())  // Voidジェネレーターを再有効化
            
            plugin.logger.info("[WorldManager] WorldCreator configured:")
            plugin.logger.info("[WorldManager]   Environment: ${creator.environment()}")
            plugin.logger.info("[WorldManager]   Type: ${creator.type()}")
            plugin.logger.info("[WorldManager]   Structures: ${creator.generateStructures()}")
            
            // ワールド作成
            plugin.logger.info("[WorldManager] Attempting to create world...")
            val world = creator.createWorld()
            
            if (world != null) {
                plugin.logger.info("[WorldManager] World created successfully: ${world.name}")
                plugin.logger.info("[WorldManager] World UID: ${world.uid}")
                plugin.logger.info("[WorldManager] World folder exists: ${File(Bukkit.getWorldContainer(), worldName).exists()}")
                
                // ワールド設定
                setupWorldRules(world)
                
                // スポーン地点を設定（0, 64, 0）
                world.setSpawnLocation(0, 64, 0)
                plugin.logger.info("[WorldManager] Spawn location set to: 0, 64, 0")
                
                // 初期プラットフォームを作成
                createInitialPlatform(world)
                plugin.logger.info("[WorldManager] Initial platform created")
                
                // Bukkit内での登録確認
                val verifyWorld = Bukkit.getWorld(worldName)
                if (verifyWorld != null) {
                    plugin.logger.info("[WorldManager] World verification successful: ${verifyWorld.name}")
                } else {
                    plugin.logger.severe("[WorldManager] World verification failed: world not found in Bukkit.getWorlds()")
                }
                
                plugin.logger.info("[WorldManager] Temporary world setup completed: $worldName")
                return world
            } else {
                plugin.logger.severe("[WorldManager] World creation returned null for: $worldName")
                plugin.logger.severe("[WorldManager] This indicates a critical failure in Bukkit's world creation")
            }
            
        } catch (e: Exception) {
            plugin.logger.severe("[WorldManager] Exception during world creation for: $worldName")
            plugin.logger.severe("[WorldManager] Exception type: ${e::class.java.simpleName}")
            plugin.logger.severe("[WorldManager] Exception message: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }
    
    /**
     * ワールドのゲームルールを設定
     */
    private fun setupWorldRules(world: World) {
        world.apply {
            // 時間を固定（昼間）
            setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            time = 6000L
            
            // 天候を晴れに固定
            setGameRule(GameRule.DO_WEATHER_CYCLE, false)
            setStorm(false)
            isThundering = false
            
            // モブスポーンを無効化
            setGameRule(GameRule.DO_MOB_SPAWNING, false)
            
            // その他の設定
            setGameRule(GameRule.KEEP_INVENTORY, false)
            setGameRule(GameRule.DO_FIRE_TICK, false)
            setGameRule(GameRule.MOB_GRIEFING, false)
            setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false)
            
            // PVPは後で制御するので有効にしておく
            pvp = true
            
            // 難易度
            difficulty = Difficulty.NORMAL
            
            // ワールドボーダー設定（1000x1000）
            worldBorder.apply {
                center = Location(world, 0.0, 64.0, 0.0)
                size = 1000.0
                warningDistance = 50
                warningTime = 10
                damageAmount = 2.0
                damageBuffer = 5.0
            }
        }
    }
    
    /**
     * 初期プラットフォームを作成（念のため）
     */
    private fun createInitialPlatform(world: World) {
        // スポーン地点(0, 64, 0)周辺に小さなプラットフォームを作成
        // これは後でマップデータで上書きされるが、念のため
        val baseY = 63
        for (x in -2..2) {
            for (z in -2..2) {
                world.getBlockAt(x, baseY, z).type = Material.STONE
            }
        }
        plugin.logger.info("[WorldManager] Created initial platform at Y=$baseY")
    }
    
    /**
     * ワールドを削除
     */
    fun deleteWorld(worldName: String): Boolean {
        val world = Bukkit.getWorld(worldName)
        
        if (world != null) {
            // 全プレイヤーを退避
            val mainWorld = Bukkit.getWorlds()[0]
            world.players.forEach { player ->
                player.teleport(mainWorld.spawnLocation)
            }
            
            // ワールドをアンロード
            if (!Bukkit.unloadWorld(world, false)) {
                plugin.logger.warning("Failed to unload world: $worldName")
                return false
            }
        }
        
        // ワールドフォルダを削除
        val worldFolder = File(Bukkit.getWorldContainer(), worldName)
        if (worldFolder.exists()) {
            return deleteWorldFolder(worldFolder)
        }
        
        return true
    }
    
    /**
     * ワールドフォルダを再帰的に削除
     */
    private fun deleteWorldFolder(folder: File): Boolean {
        if (folder.isDirectory) {
            folder.listFiles()?.forEach { file ->
                if (!deleteWorldFolder(file)) {
                    return false
                }
            }
        }
        return folder.delete()
    }
    
    /**
     * ゲーム終了時のクリーンアップ
     */
    fun cleanupTempWorld(gameName: String) {
        val worldName = "$tempWorldPrefix$gameName"
        
        // 少し遅延を入れて削除（プレイヤーの退出を待つ）
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (deleteWorld(worldName)) {
                plugin.logger.info("Deleted temporary world: $worldName")
            } else {
                plugin.logger.warning("Failed to delete temporary world: $worldName")
            }
        }, 20L) // 1秒後
    }
    
    /**
     * テンポラリワールドかどうかチェック
     */
    fun isTempWorld(worldName: String): Boolean {
        return worldName.startsWith(tempWorldPrefix)
    }
    
    /**
     * 全てのテンポラリワールドをクリーンアップ（サーバー停止時用）
     */
    fun cleanupAllTempWorlds() {
        Bukkit.getWorlds()
            .filter { it.name.startsWith(tempWorldPrefix) }
            .forEach { world ->
                plugin.logger.info("Cleaning up temporary world: ${world.name}")
                deleteWorld(world.name)
            }
    }
    
    /**
     * Voidワールドジェネレーター（完全に空のワールドを生成）
     */
    private class VoidWorldGenerator : ChunkGenerator() {
        override fun generateNoise(
            worldInfo: org.bukkit.generator.WorldInfo,
            random: Random,
            chunkX: Int,
            chunkZ: Int,
            chunkData: ChunkData
        ) {
            // 何も生成しない（完全に空のチャンク）
            // ChunkDataはデフォルトで空気ブロックで満たされている
        }
        
        override fun getDefaultPopulators(world: World): List<org.bukkit.generator.BlockPopulator> {
            return emptyList()
        }
        
        override fun canSpawn(world: World, x: Int, z: Int): Boolean {
            return true
        }
        
        override fun getFixedSpawnLocation(world: World, random: Random): Location? {
            // スポーン位置は0, 64, 0に固定
            return Location(world, 0.0, 64.0, 0.0)
        }
    }
}