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
        
        // ツールを配布
        val tools = plugin.config.getStringList("equipment.build-phase.tools")
        for (toolName in tools) {
            try {
                val material = Material.valueOf(toolName.uppercase())
                player.inventory.addItem(ItemStack(material))
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid tool material: $toolName")
            }
        }
        
        // ブロックを配布
        val blocks = plugin.config.getStringList("equipment.build-phase.blocks")
        for (blockEntry in blocks) {
            try {
                val parts = blockEntry.split(":")
                val materialName = parts[0].uppercase()
                val amount = if (parts.size > 1) parts[1].toIntOrNull() ?: 1 else 1
                
                val material = Material.valueOf(materialName)
                player.inventory.addItem(ItemStack(material, amount))
            } catch (e: Exception) {
                plugin.logger.warning("Invalid block entry: $blockEntry")
            }
        }
    }
    
    fun giveCombatPhaseEquipment(player: Player, team: Team) {
        player.inventory.clear()
        
        // 武器を配布
        val weapons = plugin.config.getStringList("equipment.combat-phase.weapons")
        for (weaponName in weapons) {
            try {
                val material = Material.valueOf(weaponName.uppercase())
                player.inventory.addItem(ItemStack(material))
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid weapon material: $weaponName")
            }
        }
        
        // 弾薬を配布
        val ammunition = plugin.config.getStringList("equipment.combat-phase.ammunition")
        for (ammoEntry in ammunition) {
            try {
                val parts = ammoEntry.split(":")
                val materialName = parts[0].uppercase()
                val amount = if (parts.size > 1) parts[1].toIntOrNull() ?: 1 else 1
                
                val material = Material.valueOf(materialName)
                player.inventory.addItem(ItemStack(material, amount))
            } catch (e: Exception) {
                plugin.logger.warning("Invalid ammunition entry: $ammoEntry")
            }
        }
        
        // 食料を配布
        val food = plugin.config.getStringList("equipment.combat-phase.food")
        for (foodEntry in food) {
            try {
                val parts = foodEntry.split(":")
                val materialName = parts[0].uppercase()
                val amount = if (parts.size > 1) parts[1].toIntOrNull() ?: 1 else 1
                
                val material = Material.valueOf(materialName)
                player.inventory.addItem(ItemStack(material, amount))
            } catch (e: Exception) {
                plugin.logger.warning("Invalid food entry: $foodEntry")
            }
        }
        
        // 防具を装備
        if (plugin.config.getBoolean("equipment.combat-phase.armor.enable", true)) {
            giveArmor(player, team)
        }
    }
    
    private fun giveArmor(player: Player, team: Team) {
        val armorType = (plugin.config.getString("equipment.combat-phase.armor.type") ?: "LEATHER").uppercase()
        val unbreakable = plugin.config.getBoolean("equipment.combat-phase.armor.unbreakable", true)
        val teamColor = if (team == Team.RED) Color.RED else Color.BLUE
        
        val armorPieces = when (armorType) {
            "LEATHER" -> listOf(
                Material.LEATHER_HELMET,
                Material.LEATHER_CHESTPLATE,
                Material.LEATHER_LEGGINGS,
                Material.LEATHER_BOOTS
            )
            "CHAINMAIL" -> listOf(
                Material.CHAINMAIL_HELMET,
                Material.CHAINMAIL_CHESTPLATE,
                Material.CHAINMAIL_LEGGINGS,
                Material.CHAINMAIL_BOOTS
            )
            "IRON" -> listOf(
                Material.IRON_HELMET,
                Material.IRON_CHESTPLATE,
                Material.IRON_LEGGINGS,
                Material.IRON_BOOTS
            )
            "DIAMOND" -> listOf(
                Material.DIAMOND_HELMET,
                Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS
            )
            "NETHERITE" -> listOf(
                Material.NETHERITE_HELMET,
                Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_BOOTS
            )
            else -> {
                plugin.logger.warning("Invalid armor type: $armorType, using LEATHER")
                listOf(
                    Material.LEATHER_HELMET,
                    Material.LEATHER_CHESTPLATE,
                    Material.LEATHER_LEGGINGS,
                    Material.LEATHER_BOOTS
                )
            }
        }
        
        val armorItems = armorPieces.map { material ->
            ItemStack(material).apply {
                val meta = itemMeta
                if (unbreakable) {
                    meta?.isUnbreakable = true
                }
                
                // 革防具の場合は色を設定
                if (armorType == "LEATHER" && meta is LeatherArmorMeta) {
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