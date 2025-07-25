package com.hacklab.ctf.shop

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.enchantments.Enchantment
import com.hacklab.ctf.utils.GamePhase
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

data class ShopItem(
    val id: String,
    val displayName: String,
    val material: Material,
    val amount: Int,
    val basePrice: Int,
    val category: ShopCategory,
    val deathBehavior: DeathBehavior = DeathBehavior.KEEP,
    val lore: List<String> = emptyList(),
    val enchantments: Map<Enchantment, Int> = emptyMap(),
    val unbreakable: Boolean = false,
    val maxPurchasePerPlayer: Int = -1,  // -1 = 無制限
    val maxPurchasePerTeam: Int = -1,    // -1 = 無制限
    val availablePhases: Set<GamePhase> = setOf(GamePhase.BUILD, GamePhase.COMBAT)
) {
    fun createItemStack(): ItemStack {
        return ItemStack(material, amount).apply {
            itemMeta = itemMeta?.apply {
                // レガシーフォーマットをAdventure APIに変換
                displayName(LegacyComponentSerializer.legacySection().deserialize(displayName))
                lore(this@ShopItem.lore.map { LegacyComponentSerializer.legacySection().deserialize(it) })
                
                // エンチャント追加
                this@ShopItem.enchantments.forEach { (enchantment, level) ->
                    addEnchant(enchantment, level, true)
                }
                
                // 耐久無限設定
                if (this@ShopItem.unbreakable) {
                    isUnbreakable = true
                }
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