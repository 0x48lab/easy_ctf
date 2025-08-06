package com.hacklab.ctf.map

import com.hacklab.ctf.Main
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block

/**
 * マップ内の特定ブロックを検出するクラス
 */
class MapScanner(private val plugin: Main) {
    
    data class ScanResult(
        val redSpawns: List<Location>,
        val blueSpawns: List<Location>,
        val redFlags: List<Location>,
        val blueFlags: List<Location>,
        val errors: List<String>
    ) {
        fun isValid(): Boolean = errors.isEmpty()
    }
    
    /**
     * マップ領域をスキャンして特定のブロックを検出
     */
    fun scan(region: MapRegion): ScanResult {
        val redSpawns = mutableListOf<Location>()
        val blueSpawns = mutableListOf<Location>()
        val redFlags = mutableListOf<Location>()
        val blueFlags = mutableListOf<Location>()
        val errors = mutableListOf<String>()
        
        // スキャン開始
        
        // すべてのブロックをスキャン
        var scannedBlocks = 0
        for (x in region.minX..region.maxX) {
            for (y in region.minY..region.maxY) {
                for (z in region.minZ..region.maxZ) {
                    val block = region.world.getBlockAt(x, y, z)
                    val location = block.location
                    scannedBlocks++
                    
                    when (block.type) {
                        // 赤のコンクリート = 赤チームスポーン
                        Material.RED_CONCRETE -> {
                            // 赤コンクリート発見
                            redSpawns.add(location.clone().add(0.5, 1.0, 0.5))
                        }
                        // 青のコンクリート = 青チームスポーン
                        Material.BLUE_CONCRETE -> {
                            // 青コンクリート発見
                            blueSpawns.add(location.clone().add(0.5, 1.0, 0.5))
                        }
                        // ビーコンをチェック
                        Material.BEACON -> {
                            // ビーコン発見
                            checkBeaconFlag(block, redFlags, blueFlags)
                        }
                        else -> {}
                    }
                }
            }
        }
        
        // スキャン完了
        
        // 検証
        validateScanResult(redSpawns, blueSpawns, redFlags, blueFlags, errors)
        
        return ScanResult(redSpawns, blueSpawns, redFlags, blueFlags, errors)
    }
    
    /**
     * ビーコンの上のガラスブロックをチェックして旗位置を判定
     */
    private fun checkBeaconFlag(
        beaconBlock: Block,
        redFlags: MutableList<Location>,
        blueFlags: MutableList<Location>
    ) {
        val aboveBlock = beaconBlock.getRelative(0, 1, 0)
        
        when (aboveBlock.type) {
            Material.RED_STAINED_GLASS -> {
                // ビーコンの上に赤ガラス発見
                redFlags.add(beaconBlock.location.clone().add(0.5, 0.0, 0.5))
            }
            Material.BLUE_STAINED_GLASS -> {
                // ビーコンの上に青ガラス発見
                blueFlags.add(beaconBlock.location.clone().add(0.5, 0.0, 0.5))
            }
            else -> {
                // ビーコンの上のブロック: ${aboveBlock.type}
            }
        }
    }
    
    /**
     * スキャン結果を検証
     */
    private fun validateScanResult(
        redSpawns: List<Location>,
        blueSpawns: List<Location>,
        redFlags: List<Location>,
        blueFlags: List<Location>,
        errors: MutableList<String>
    ) {
        // スポーン地点の検証（複数スポーン地点を許可）
        if (redSpawns.isEmpty()) {
            errors.add(plugin.languageManager.getMessage("scanner.red-spawn-not-found"))
        }
        // 複数のスポーン地点は許可されるため、エラーとしない
        
        if (blueSpawns.isEmpty()) {
            errors.add(plugin.languageManager.getMessage("scanner.blue-spawn-not-found"))
        }
        // 複数のスポーン地点は許可されるため、エラーとしない
        
        // 旗位置の検証
        when {
            redFlags.isEmpty() -> {
                errors.add(plugin.languageManager.getMessage("scanner.red-flag-not-found"))
            }
            redFlags.size > 1 -> {
                errors.add(plugin.languageManager.getMessage("scanner.red-flag-multiple", "count" to redFlags.size.toString()))
            }
        }
        
        when {
            blueFlags.isEmpty() -> {
                errors.add(plugin.languageManager.getMessage("scanner.blue-flag-not-found"))
            }
            blueFlags.size > 1 -> {
                errors.add(plugin.languageManager.getMessage("scanner.blue-flag-multiple", "count" to blueFlags.size.toString()))
            }
        }
        
        // スポーン地点同士の距離検証（9x9のコンクリートが重複しないようにする）
        val minSpawnDistance = 4.0  // 最低4ブロック離す（3x3の範囲が重複しないため）
        
        // 赤チームのスポーン地点同士の距離をチェック
        if (redSpawns.size > 1) {
            for (i in 0 until redSpawns.size - 1) {
                for (j in i + 1 until redSpawns.size) {
                    val distance = redSpawns[i].distance(redSpawns[j])
                    if (distance < minSpawnDistance) {
                        errors.add(plugin.languageManager.getMessage("scanner.red-spawns-too-close", "point1" to (i+1).toString(), "point2" to (j+1).toString(), "distance" to String.format("%.1f", distance), "min" to minSpawnDistance.toString()))
                    }
                }
            }
        }
        
        // 青チームのスポーン地点同士の距離をチェック
        if (blueSpawns.size > 1) {
            for (i in 0 until blueSpawns.size - 1) {
                for (j in i + 1 until blueSpawns.size) {
                    val distance = blueSpawns[i].distance(blueSpawns[j])
                    if (distance < minSpawnDistance) {
                        errors.add(plugin.languageManager.getMessage("scanner.blue-spawns-too-close", "point1" to (i+1).toString(), "point2" to (j+1).toString(), "distance" to String.format("%.1f", distance), "min" to minSpawnDistance.toString()))
                    }
                }
            }
        }
        
        // 距離の検証（エラーがない場合のみ）
        if (errors.isEmpty() && redFlags.isNotEmpty() && blueFlags.isNotEmpty()) {
            val redFlag = redFlags[0]
            val blueFlag = blueFlags[0]
            val minDistance = 3.0
            
            // 赤チームの各スポーン地点と旗の距離をチェック
            if (redSpawns.isNotEmpty()) {
                val tooCloseRedSpawns = redSpawns.filter { spawn ->
                    spawn.distance(redFlag) < minDistance
                }
                if (tooCloseRedSpawns.isNotEmpty()) {
                    errors.add(plugin.languageManager.getMessage("scanner.red-flag-spawn-too-close", "count" to tooCloseRedSpawns.size.toString(), "min" to minDistance.toString()))
                }
            }
            
            // 青チームの各スポーン地点と旗の距離をチェック
            if (blueSpawns.isNotEmpty()) {
                val tooCloseBlueSpawns = blueSpawns.filter { spawn ->
                    spawn.distance(blueFlag) < minDistance
                }
                if (tooCloseBlueSpawns.isNotEmpty()) {
                    errors.add(plugin.languageManager.getMessage("scanner.blue-flag-spawn-too-close", "count" to tooCloseBlueSpawns.size.toString(), "min" to minDistance.toString()))
                }
            }
        }
    }
}