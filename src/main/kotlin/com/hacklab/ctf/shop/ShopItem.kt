package com.hacklab.ctf.shop

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

data class ShopItem(
    val id: String,
    val displayName: String,
    val material: Material,
    val amount: Int,
    val basePrice: Int,
    val category: ShopCategory,
    val deathBehavior: DeathBehavior = DeathBehavior.KEEP,
    val lore: List<String> = emptyList()
) {
    fun createItemStack(): ItemStack {
        return ItemStack(material, amount).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(displayName)
                setLore(lore)
            }
        }
    }
}

enum class ShopCategory {
    WEAPONS,
    ARMOR,
    CONSUMABLES,
    BLOCKS
}

enum class DeathBehavior {
    KEEP,    // 保持
    DROP,    // ドロップ
    DESTROY  // 消失
}