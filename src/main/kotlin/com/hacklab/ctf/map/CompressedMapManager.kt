package com.hacklab.ctf.map

import com.hacklab.ctf.Main
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * 圧縮形式でマップデータを保存・管理するクラス
 * YAMLに保存するが、ブロックデータは圧縮してBase64エンコード
 */
class CompressedMapManager(private val plugin: Main) {
    private val mapsFolder = File(plugin.dataFolder, "maps")
    
    init {
        if (!mapsFolder.exists()) {
            mapsFolder.mkdirs()
        }
    }
    
    /**
     * マップデータを圧縮して保存
     */
    fun saveMap(gameName: String, region: MapRegion): Boolean {
        try {
            val file = File(mapsFolder, "$gameName.yml")
            val config = YamlConfiguration()
            
            // リージョン情報を保存
            config.set("world", region.world.name)
            config.set("pos1.x", region.pos1.blockX)
            config.set("pos1.y", region.pos1.blockY)
            config.set("pos1.z", region.pos1.blockZ)
            config.set("pos2.x", region.pos2.blockX)
            config.set("pos2.y", region.pos2.blockY)
            config.set("pos2.z", region.pos2.blockZ)
            
            // ブロックデータを収集
            val blockDataList = mutableListOf<String>()
            var totalBlocks = 0
            var airBlocks = 0
            
            println("[CompressedMapManager] 保存範囲: ${region.minX},${region.minY},${region.minZ} から ${region.maxX},${region.maxY},${region.maxZ}")
            
            for (x in region.minX..region.maxX) {
                for (y in region.minY..region.maxY) {
                    for (z in region.minZ..region.maxZ) {
                        totalBlocks++
                        val block = region.world.getBlockAt(x, y, z)
                        if (block.type != Material.AIR) {
                            val relX = x - region.minX
                            val relY = y - region.minY
                            val relZ = z - region.minZ
                            blockDataList.add("$relX,$relY,$relZ:${block.blockData.asString}")
                        } else {
                            airBlocks++
                        }
                    }
                }
            }
            
            println("[CompressedMapManager] スキャン完了: 総ブロック数=$totalBlocks, AIR=$airBlocks, 保存ブロック数=${blockDataList.size}")
            
            // データを圧縮してBase64エンコード
            val compressedData = compressData(blockDataList.joinToString("\n"))
            config.set("blocks_compressed", compressedData)
            config.set("block_count", blockDataList.size)
            
            config.save(file)
            plugin.logger.info("Saved compressed map for $gameName (${blockDataList.size} blocks)")
            return true
            
        } catch (e: Exception) {
            plugin.logger.severe("Failed to save map for $gameName: ${e.message}")
            return false
        }
    }
    
    /**
     * 圧縮されたマップデータを読み込んで復元
     * @param gameName ゲーム名
     * @param targetWorld 復元先のワールド（省略時は保存時のワールド）
     */
    fun loadAndRestoreMap(gameName: String, targetWorld: org.bukkit.World? = null): Boolean {
        try {
            val file = File(mapsFolder, "$gameName.yml")
            if (!file.exists()) return false
            
            val config = YamlConfiguration.loadConfiguration(file)
            
            // ワールド取得（targetWorldが指定されていればそれを使用）
            val world = if (targetWorld != null) {
                targetWorld
            } else {
                val worldName = config.getString("world") ?: return false
                plugin.server.getWorld(worldName) ?: return false
            }
            
            // リージョン情報を読み込み
            val minX = config.getInt("pos1.x")
            val minY = config.getInt("pos1.y")
            val minZ = config.getInt("pos1.z")
            val maxX = config.getInt("pos2.x")
            val maxY = config.getInt("pos2.y")
            val maxZ = config.getInt("pos2.z")
            
            println("[CompressedMapManager] 復元範囲: $minX,$minY,$minZ から $maxX,$maxY,$maxZ")
            println("[CompressedMapManager] 復元先ワールド: ${world.name}")
            
            // まず範囲内をすべてAIRにする
            var clearedBlocks = 0
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        world.getBlockAt(x, y, z).type = Material.AIR
                        clearedBlocks++
                    }
                }
            }
            println("[CompressedMapManager] $clearedBlocks ブロックをクリア")
            
            // 圧縮データを解凍
            val compressedData = config.getString("blocks_compressed") ?: return false
            val blockDataString = decompressData(compressedData)
            
            // ブロックを復元
            var restoredBlocks = 0
            blockDataString.split("\n").forEach { line ->
                if (line.isNotEmpty()) {
                    val parts = line.split(":")
                    if (parts.size == 2) {
                        val coords = parts[0].split(",")
                        if (coords.size == 3) {
                            val x = minX + coords[0].toInt()
                            val y = minY + coords[1].toInt()
                            val z = minZ + coords[2].toInt()
                            try {
                                val blockData = plugin.server.createBlockData(parts[1])
                                world.getBlockAt(x, y, z).blockData = blockData
                                restoredBlocks++
                            } catch (e: Exception) {
                                plugin.logger.warning("Failed to restore block at $x,$y,$z: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            println("[CompressedMapManager] 復元完了: $restoredBlocks ブロックを復元")
            plugin.logger.info("Restored map for $gameName ($restoredBlocks blocks)")
            return true
            
        } catch (e: Exception) {
            plugin.logger.severe("Failed to restore map for $gameName: ${e.message}")
            return false
        }
    }
    
    /**
     * データを圧縮してBase64エンコード
     */
    private fun compressData(data: String): String {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(data.toByteArray())
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
    
    /**
     * Base64デコードして解凍
     */
    private fun decompressData(compressedData: String): String {
        val bytes = Base64.getDecoder().decode(compressedData)
        val bais = ByteArrayInputStream(bytes)
        return GZIPInputStream(bais).use { gzip ->
            gzip.readBytes().toString(Charsets.UTF_8)
        }
    }
    
    /**
     * マップが存在するかチェック
     */
    fun hasMap(gameName: String): Boolean {
        return File(mapsFolder, "$gameName.yml").exists()
    }
    
    /**
     * マップを削除
     */
    fun deleteMap(gameName: String): Boolean {
        val file = File(mapsFolder, "$gameName.yml")
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
}