package com.hacklab.ctf.utils

import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.bukkit.BukkitPlayer
import com.sk89q.worldedit.bukkit.WorldEditPlugin
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.Region
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * WorldEditとの連携を提供するヘルパークラス
 */
object WorldEditHelper {
    
    /**
     * WorldEditプラグインの取得
     */
    fun getWorldEdit(): WorldEditPlugin? {
        val plugin = Bukkit.getPluginManager().getPlugin("WorldEdit")
        return plugin as? WorldEditPlugin
    }
    
    /**
     * WorldEditが利用可能かチェック
     */
    fun isWorldEditAvailable(): Boolean {
        return getWorldEdit() != null
    }
    
    /**
     * プレイヤーの選択範囲を取得
     */
    fun getPlayerSelection(player: Player): Pair<Location, Location>? {
        val worldEdit = getWorldEdit() ?: return null
        
        try {
            val bukkitPlayer = BukkitAdapter.adapt(player)
            val session = WorldEdit.getInstance().sessionManager.get(bukkitPlayer)
            val world = session.getSelectionWorld() ?: return null
            val region = session.getSelection(world)
            
            val min = region.minimumPoint
            val max = region.maximumPoint
            
            val bukkitWorld = BukkitAdapter.adapt(world)
            val minLocation = Location(bukkitWorld, min.x().toDouble(), min.y().toDouble(), min.z().toDouble())
            val maxLocation = Location(bukkitWorld, max.x().toDouble(), max.y().toDouble(), max.z().toDouble())
            
            return Pair(minLocation, maxLocation)
        } catch (e: IncompleteRegionException) {
            // 選択範囲が不完全
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * プレイヤーが選択範囲を持っているかチェック
     */
    fun hasSelection(player: Player): Boolean {
        return getPlayerSelection(player) != null
    }
}