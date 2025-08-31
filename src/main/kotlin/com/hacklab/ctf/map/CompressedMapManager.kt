package com.hacklab.ctf.map

import com.hacklab.ctf.Main
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.Container
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
            
            // リージョン情報を保存（常にmin/maxを保存）
            config.set("world", region.world.name)
            config.set("pos1.x", region.minX)
            config.set("pos1.y", region.minY)
            config.set("pos1.z", region.minZ)
            config.set("pos2.x", region.maxX)
            config.set("pos2.y", region.maxY)
            config.set("pos2.z", region.maxZ)
            
            // ブロックデータを収集
            val blockDataList = mutableListOf<String>()
            var totalBlocks = 0
            var airBlocks = 0
            
            plugin.logger.info("[CompressedMapManager] 保存範囲: ${region.minX},${region.minY},${region.minZ} から ${region.maxX},${region.maxY},${region.maxZ}")
            
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
            
            plugin.logger.info("[CompressedMapManager] スキャン完了: 総ブロック数=$totalBlocks, AIR=$airBlocks, 保存ブロック数=${blockDataList.size}")
            
            // データを圧縮してBase64エンコード
            val rawData = blockDataList.joinToString("\n")
            plugin.logger.fine("[CompressedMapManager] 保存前のデータ例（最初の3行）:")
            blockDataList.take(3).forEach { plugin.logger.fine("  $it") }
            
            val compressedData = compressData(rawData)
            plugin.logger.info("[CompressedMapManager] 圧縮後のデータサイズ: ${compressedData.length} 文字")
            
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
     * @param game ゲームインスタンス（旗位置情報取得用、オプション）
     */
    fun loadAndRestoreMap(gameName: String, targetWorld: org.bukkit.World? = null, game: com.hacklab.ctf.Game? = null): Boolean {
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
            
            plugin.logger.info("[CompressedMapManager] 復元範囲: $minX,$minY,$minZ から $maxX,$maxY,$maxZ")
            plugin.logger.info("[CompressedMapManager] 復元先ワールド: ${world.name}")
            
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
            plugin.logger.info("[CompressedMapManager] $clearedBlocks ブロックをクリア")
            
            // 圧縮データを解凍
            val compressedData = config.getString("blocks_compressed")
            if (compressedData == null) {
                plugin.logger.warning("[CompressedMapManager] 圧縮データが見つかりません")
                return false
            }
            
            plugin.logger.info("[CompressedMapManager] 圧縮データサイズ: ${compressedData.length} 文字")
            
            val blockDataString = decompressData(compressedData)
            val lines = blockDataString.split("\n")
            plugin.logger.info("[CompressedMapManager] 解凍後のライン数: ${lines.size}")
            
            // ブロックを復元
            var restoredBlocks = 0
            lines.forEach { line ->
                if (line.isNotEmpty()) {
                    if (restoredBlocks < 3) {
                        plugin.logger.fine("[CompressedMapManager] 復元データ例: $line")
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
                            plugin.logger.warning("[CompressedMapManager] 無効な座標形式: ${parts[0]}")
                        }
                    } else {
                        plugin.logger.warning("[CompressedMapManager] 無効なデータ形式: $line")
                    }
                }
            }
            
            plugin.logger.info("[CompressedMapManager] 復元完了: $restoredBlocks ブロックを復元")
            
            // コンテナブロックの中身をクリア
            clearContainerContents(world, minX, minY, minZ, maxX, maxY, maxZ)
            
            // ビーコンの色を設定（ゲーム情報がある場合）
            if (game != null) {
                setupBeaconColors(world, game)
            }
            
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
     * コンテナブロックの中身をクリア
     */
    private fun clearContainerContents(world: org.bukkit.World, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int) {
        var clearedContainers = 0
        
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val block = world.getBlockAt(x, y, z)
                    val blockState = block.state
                    
                    // コンテナブロックの場合、インベントリをクリア
                    if (blockState is Container) {
                        blockState.inventory.clear()
                        clearedContainers++
                        plugin.logger.fine("[CompressedMapManager] コンテナの中身をクリア: ${block.type} at $x, $y, $z")
                    }
                }
            }
        }
        
        if (clearedContainers > 0) {
            plugin.logger.info("[CompressedMapManager] $clearedContainers 個のコンテナの中身をクリアしました")
        }
    }
    
    /**
     * ビーコンの上に適切な色のガラスを設置
     */
    private fun setupBeaconColors(world: org.bukkit.World, game: com.hacklab.ctf.Game) {
        // 赤チームの旗位置にビーコンと赤ガラスを設置
        game.getRedFlagLocation()?.let { flagLoc ->
            val beaconBlock = world.getBlockAt(flagLoc)
            if (beaconBlock.type == Material.BEACON) {
                // ビーコンの上に赤ガラスを設置
                val glassBlock = world.getBlockAt(flagLoc.blockX, flagLoc.blockY + 1, flagLoc.blockZ)
                glassBlock.type = Material.RED_STAINED_GLASS
                plugin.logger.info("[CompressedMapManager] 赤チームのビーコンに赤ガラスを設置: ${flagLoc.blockX}, ${flagLoc.blockY + 1}, ${flagLoc.blockZ}")
            }
        }
        
        // 青チームの旗位置にビーコンと青ガラスを設置
        game.getBlueFlagLocation()?.let { flagLoc ->
            val beaconBlock = world.getBlockAt(flagLoc)
            if (beaconBlock.type == Material.BEACON) {
                // ビーコンの上に青ガラスを設置
                val glassBlock = world.getBlockAt(flagLoc.blockX, flagLoc.blockY + 1, flagLoc.blockZ)
                glassBlock.type = Material.BLUE_STAINED_GLASS
                plugin.logger.info("[CompressedMapManager] 青チームのビーコンに青ガラスを設置: ${flagLoc.blockX}, ${flagLoc.blockY + 1}, ${flagLoc.blockZ}")
            }
        }
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