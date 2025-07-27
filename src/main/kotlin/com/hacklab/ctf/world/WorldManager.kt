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
    
    /**
     * ゲーム用のテンポラリワールドを作成
     */
    fun createTempWorld(gameName: String): World? {
        val worldName = "$tempWorldPrefix$gameName"
        
        try {
            // 既存のワールドがあれば削除
            if (Bukkit.getWorld(worldName) != null) {
                deleteWorld(worldName)
            }
            
            // ワールド作成設定
            val creator = WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)  // フラットワールド
                .generateStructures(false)
                .generator(VoidWorldGenerator())  // カスタムジェネレーター（void）
            
            // ワールド作成
            val world = creator.createWorld()
            
            if (world != null) {
                // ワールド設定
                setupWorldRules(world)
                
                // スポーン地点を設定（0, 64, 0）
                world.setSpawnLocation(0, 64, 0)
                
                // 初期プラットフォームを作成
                createInitialPlatform(world)
                
                plugin.logger.info("Created temporary world: $worldName")
                return world
            }
            
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to create temporary world: $worldName", e)
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
        val baseY = 63
        for (x in -2..2) {
            for (z in -2..2) {
                world.getBlockAt(x, baseY, z).type = Material.BEDROCK
            }
        }
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
     * Voidワールドジェネレーター
     */
    private class VoidWorldGenerator : ChunkGenerator() {
        override fun generateChunkData(
            world: World,
            random: Random,
            x: Int,
            z: Int,
            biome: BiomeGrid
        ): ChunkData {
            // 空のチャンクを返す（何も生成しない）
            return createChunkData(world)
        }
        
        override fun getDefaultPopulators(world: World): List<org.bukkit.generator.BlockPopulator> {
            return emptyList()
        }
        
        override fun canSpawn(world: World, x: Int, z: Int): Boolean {
            return true
        }
        
        override fun getFixedSpawnLocation(world: World, random: Random): Location {
            return Location(world, 0.0, 64.0, 0.0)
        }
    }
}