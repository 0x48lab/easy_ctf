package com.hacklab.ctf.managers

import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta

class EquipmentManager(private val plugin: Main) {
    
    fun giveBuildPhaseEquipment(player: Player) {
        player.inventory.clear()
        
        // ショップアイテム（エメラルド）のみ配布
        player.inventory.addItem(plugin.shopManager.createShopItem())
    }
    
    fun giveCombatPhaseEquipment(player: Player, team: Team) {
        player.inventory.clear()
        
        // ショップアイテム（エメラルド）のみ配布
        player.inventory.addItem(plugin.shopManager.createShopItem())
        
        // 防具を装備（チーム色の革防具のみ）
        giveArmor(player, team)
    }
    
    fun giveArmor(player: Player, team: Team) {
        val unbreakable = plugin.config.getBoolean("equipment.combat-phase.armor.unbreakable", true)
        val teamColor = if (team == Team.RED) Color.RED else Color.BLUE
        
        // 革防具のみ使用
        val armorPieces = listOf(
            Material.LEATHER_HELMET,
            Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS,
            Material.LEATHER_BOOTS
        )
        
        val armorItems = armorPieces.map { material ->
            ItemStack(material).apply {
                val meta = itemMeta
                if (unbreakable) {
                    meta?.isUnbreakable = true
                }
                
                // 革防具の色を設定
                if (meta is LeatherArmorMeta) {
                    meta.setColor(teamColor)
                }
                
                itemMeta = meta
            }
        }
        
        with(player.equipment!!) {
            helmet = armorItems[0]
            chestplate = armorItems[1]
            leggings = armorItems[2]
            boots = armorItems[3]
        }
    }
}