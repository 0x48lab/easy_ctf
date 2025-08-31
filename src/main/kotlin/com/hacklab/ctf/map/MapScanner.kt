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
        
        // 一時リストをクリア
        tempBeacons.clear()
        
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
        
        // ビーコンをスポーン地点との距離で分類
        assignBeaconsToTeams(redSpawns, blueSpawns, redFlags, blueFlags, errors)
        
        // 検証
        validateScanResult(redSpawns, blueSpawns, redFlags, blueFlags, errors)
        
        return ScanResult(redSpawns, blueSpawns, redFlags, blueFlags, errors)
    }
    
    /**
     * ビーコンの旗位置を一時的に保存
     */
    private val tempBeacons = mutableListOf<Location>()
    
    /**
     * ビーコンを一時リストに追加（後でスポーン地点との距離で判定）
     */
    private fun checkBeaconFlag(
        beaconBlock: Block,
        redFlags: MutableList<Location>,
        blueFlags: MutableList<Location>
    ) {
        val location = beaconBlock.location.clone().add(0.5, 0.0, 0.5)
        tempBeacons.add(location)
    }
    
    /**
     * ビーコンをスポーン地点との距離で各チームに割り当て
     */
    private fun assignBeaconsToTeams(
        redSpawns: List<Location>,
        blueSpawns: List<Location>,
        redFlags: MutableList<Location>,
        blueFlags: MutableList<Location>,
        errors: MutableList<String>
    ) {
        // スポーン地点がない場合はエラー
        if (redSpawns.isEmpty() || blueSpawns.isEmpty()) {
            // スポーン地点がない場合は後の検証でエラーになるため、ここでは処理しない
            return
        }
        
        // 各ビーコンについて、最も近いスポーン地点のチームに割り当てる
        for (beacon in tempBeacons) {
            // 赤スポーンまでの最小距離
            val minRedDistance = redSpawns.minOf { it.distance(beacon) }
            // 青スポーンまでの最小距離
            val minBlueDistance = blueSpawns.minOf { it.distance(beacon) }
            
            // より近いチームに割り当て
            when {
                minRedDistance < minBlueDistance -> {
                    redFlags.add(beacon)
                    plugin.logger.info("ビーコン at ${beacon.blockX}, ${beacon.blockY}, ${beacon.blockZ} -> RED (距離: ${String.format("%.1f", minRedDistance)})")
                }
                minBlueDistance < minRedDistance -> {
                    blueFlags.add(beacon)
                    plugin.logger.info("ビーコン at ${beacon.blockX}, ${beacon.blockY}, ${beacon.blockZ} -> BLUE (距離: ${String.format("%.1f", minBlueDistance)})")
                }
                else -> {
                    // 距離が同じ場合はエラー
                    errors.add("ビーコン at ${beacon.blockX}, ${beacon.blockY}, ${beacon.blockZ} は両チームのスポーンから等距離です")
                }
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