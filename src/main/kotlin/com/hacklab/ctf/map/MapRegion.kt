package com.hacklab.ctf.map

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import kotlin.math.max
import kotlin.math.min

/**
 * マップの領域を表すクラス
 * pos1とpos2で定義される直方体領域を管理
 */
data class MapRegion(
    val world: World,
    val pos1: Location,
    val pos2: Location
) {
    // 最小座標と最大座標を計算
    val minX = min(pos1.blockX, pos2.blockX)
    val minY = min(pos1.blockY, pos2.blockY)
    val minZ = min(pos1.blockZ, pos2.blockZ)
    val maxX = max(pos1.blockX, pos2.blockX)
    val maxY = max(pos1.blockY, pos2.blockY)
    val maxZ = max(pos1.blockZ, pos2.blockZ)
    
    /**
     * 指定した位置がこの領域内にあるかチェック
     */
    fun contains(location: Location): Boolean {
        if (location.world != world) return false
        
        return location.blockX in minX..maxX &&
               location.blockY in minY..maxY &&
               location.blockZ in minZ..maxZ
    }
    
    /**
     * 領域内のすべてのブロックを取得
     */
    fun getAllBlocks(): List<Block> {
        val blocks = mutableListOf<Block>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    blocks.add(world.getBlockAt(x, y, z))
                }
            }
        }
        return blocks
    }
    
    /**
     * 領域のサイズを取得
     */
    fun getSize(): Triple<Int, Int, Int> {
        return Triple(
            maxX - minX + 1,
            maxY - minY + 1,
            maxZ - minZ + 1
        )
    }
    
    /**
     * 領域の体積を取得
     */
    fun getVolume(): Int {
        val (x, y, z) = getSize()
        return x * y * z
    }
}