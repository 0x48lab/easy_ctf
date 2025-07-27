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
        // スポーン地点の検証
        when {
            redSpawns.isEmpty() -> {
                errors.add("赤チームのスポーン地点（赤のコンクリートブロック）が見つかりません")
            }
            redSpawns.size > 1 -> {
                errors.add("赤チームのスポーン地点が複数見つかりました（${redSpawns.size}個）")
            }
        }
        
        when {
            blueSpawns.isEmpty() -> {
                errors.add("青チームのスポーン地点（青のコンクリートブロック）が見つかりません")
            }
            blueSpawns.size > 1 -> {
                errors.add("青チームのスポーン地点が複数見つかりました（${blueSpawns.size}個）")
            }
        }
        
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
        
        // 距離の検証（エラーがない場合のみ）
        if (errors.isEmpty()) {
            val redSpawn = redSpawns[0]
            val blueSpawn = blueSpawns[0]
            val redFlag = redFlags[0]
            val blueFlag = blueFlags[0]
            
            // 旗とスポーンの最小距離チェック
            val minDistance = 3.0
            
            if (redSpawn.distance(redFlag) < minDistance) {
                errors.add("赤チームの旗とスポーン地点が近すぎます（最低${minDistance}ブロック必要）")
            }
            
            if (blueSpawn.distance(blueFlag) < minDistance) {
                errors.add("青チームの旗とスポーン地点が近すぎます（最低${minDistance}ブロック必要）")
            }
        }
    }
}