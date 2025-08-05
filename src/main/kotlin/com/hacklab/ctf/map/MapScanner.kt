package com.hacklab.ctf.map

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block

/**
 * マップ内の特定ブロックを検出するクラス
 */
class MapScanner {
    
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
        
        println("[MapScanner] スキャン開始: ${region.minX},${region.minY},${region.minZ} から ${region.maxX},${region.maxY},${region.maxZ}")
        
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
                            println("[MapScanner] 赤コンクリート発見: $x, $y, $z")
                            redSpawns.add(location.clone().add(0.5, 1.0, 0.5))
                        }
                        // 青のコンクリート = 青チームスポーン
                        Material.BLUE_CONCRETE -> {
                            println("[MapScanner] 青コンクリート発見: $x, $y, $z")
                            blueSpawns.add(location.clone().add(0.5, 1.0, 0.5))
                        }
                        // ビーコンをチェック
                        Material.BEACON -> {
                            println("[MapScanner] ビーコン発見: $x, $y, $z")
                            checkBeaconFlag(block, redFlags, blueFlags)
                        }
                        else -> {}
                    }
                }
            }
        }
        
        println("[MapScanner] スキャン完了: ${scannedBlocks}ブロックをスキャン")
        println("[MapScanner] 結果: 赤スポーン=${redSpawns.size}, 青スポーン=${blueSpawns.size}, 赤旗=${redFlags.size}, 青旗=${blueFlags.size}")
        
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
                println("[MapScanner] ビーコンの上に赤ガラス発見")
                redFlags.add(beaconBlock.location.clone().add(0.5, 0.0, 0.5))
            }
            Material.BLUE_STAINED_GLASS -> {
                println("[MapScanner] ビーコンの上に青ガラス発見")
                blueFlags.add(beaconBlock.location.clone().add(0.5, 0.0, 0.5))
            }
            else -> {
                println("[MapScanner] ビーコンの上のブロック: ${aboveBlock.type}")
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
            errors.add("赤チームのスポーン地点（赤のコンクリートブロック）が見つかりません")
        }
        // 複数のスポーン地点は許可されるため、エラーとしない
        
        if (blueSpawns.isEmpty()) {
            errors.add("青チームのスポーン地点（青のコンクリートブロック）が見つかりません")
        }
        // 複数のスポーン地点は許可されるため、エラーとしない
        
        // 旗位置の検証
        when {
            redFlags.isEmpty() -> {
                errors.add("赤チームの旗（ビーコン＋赤のガラス）が見つかりません")
            }
            redFlags.size > 1 -> {
                errors.add("赤チームの旗が複数見つかりました（${redFlags.size}個）")
            }
        }
        
        when {
            blueFlags.isEmpty() -> {
                errors.add("青チームの旗（ビーコン＋青のガラス）が見つかりません")
            }
            blueFlags.size > 1 -> {
                errors.add("青チームの旗が複数見つかりました（${blueFlags.size}個）")
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
                        errors.add("赤チームのスポーン地点が近すぎます（地点${i+1}と地点${j+1}が${String.format("%.1f", distance)}ブロック）。最低${minSpawnDistance}ブロック離してください")
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
                        errors.add("青チームのスポーン地点が近すぎます（地点${i+1}と地点${j+1}が${String.format("%.1f", distance)}ブロック）。最低${minSpawnDistance}ブロック離してください")
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
                    errors.add("赤チームの旗とスポーン地点が近すぎます（${tooCloseRedSpawns.size}箇所が最低${minDistance}ブロック未満）")
                }
            }
            
            // 青チームの各スポーン地点と旗の距離をチェック
            if (blueSpawns.isNotEmpty()) {
                val tooCloseBlueSpawns = blueSpawns.filter { spawn ->
                    spawn.distance(blueFlag) < minDistance
                }
                if (tooCloseBlueSpawns.isNotEmpty()) {
                    errors.add("青チームの旗とスポーン地点が近すぎます（${tooCloseBlueSpawns.size}箇所が最低${minDistance}ブロック未満）")
                }
            }
        }
    }
}