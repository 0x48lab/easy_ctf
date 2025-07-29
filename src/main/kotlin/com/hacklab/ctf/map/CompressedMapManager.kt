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
            val rawData = blockDataList.joinToString("\n")
            println("[CompressedMapManager] 保存前のデータ例（最初の3行）:")
            blockDataList.take(3).forEach { println("  $it") }
            
            val compressedData = compressData(rawData)
            println("[CompressedMapManager] 圧縮後のデータサイズ: ${compressedData.length} 文字")
            
            config.set("blocks_compressed", compressedData)
            config.set("block_count", blockDataList.size)
            
            config.save(file)
            plugin.logger.info(plugin.languageManager.getMessage("log.map-saved", "game" to gameName, "blocks" to blockDataList.size.toString()))
            return true
            
        } catch (e: Exception) {
            plugin.logger.severe(plugin.languageManager.getMessage("log.map-save-failed", "game" to gameName, "error" to (e.message ?: "Unknown error")))
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
            val compressedData = config.getString("blocks_compressed")
            if (compressedData == null) {
                println("[CompressedMapManager] 圧縮データが見つかりません")
                return false
            }
            
            println("[CompressedMapManager] 圧縮データサイズ: ${compressedData.length} 文字")
            
            val blockDataString = decompressData(compressedData)
            val lines = blockDataString.split("\n")
            println("[CompressedMapManager] 解凍後のライン数: ${lines.size}")
            
            // ブロックを復元
            var restoredBlocks = 0
            lines.forEach { line ->
                if (line.isNotEmpty()) {
                    if (restoredBlocks < 3) {
                        println("[CompressedMapManager] 復元データ例: $line")
                    }
                    val parts = line.split(":", limit = 2)
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
                                plugin.logger.warning(plugin.languageManager.getMessage("log.block-restore-failed", "x" to x.toString(), "y" to y.toString(), "z" to z.toString(), "error" to (e.message ?: "Unknown error")))
                            }
                        } else {
                            println("[CompressedMapManager] 無効な座標形式: ${parts[0]}")
                        }
                    } else {
                        println("[CompressedMapManager] 無効なデータ形式: $line")
                    }
                }
            }
            
            println("[CompressedMapManager] 復元完了: $restoredBlocks ブロックを復元")
            plugin.logger.info(plugin.languageManager.getMessage("log.map-restored-blocks", "game" to gameName, "blocks" to restoredBlocks.toString()))
            return true
            
        } catch (e: Exception) {
            plugin.logger.severe(plugin.languageManager.getMessage("log.map-restore-error", "game" to gameName, "error" to (e.message ?: "Unknown error")))
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