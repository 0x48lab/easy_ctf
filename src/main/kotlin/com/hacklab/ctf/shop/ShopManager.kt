package com.hacklab.ctf.shop

import com.hacklab.ctf.Main
import com.hacklab.ctf.Game
import com.hacklab.ctf.utils.Team
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.math.roundToInt

class ShopManager(private val plugin: Main) {
    
    private val shopItems = mutableListOf<ShopItem>()
    
    init {
        loadShopItems()
    }
    
    private fun loadShopItems() {
        // 武器
        shopItems.add(ShopItem("iron_sword", "§f鉄の剣", Material.IRON_SWORD, 1, 25, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("diamond_sword", "§bダイヤモンドの剣", Material.DIAMOND_SWORD, 1, 60, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("netherite_sword", "§5ネザライトの剣", Material.NETHERITE_SWORD, 1, 100, ShopCategory.WEAPONS))
        
        shopItems.add(ShopItem("iron_axe", "§f鉄の斧", Material.IRON_AXE, 1, 45, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("diamond_axe", "§bダイヤモンドの斧", Material.DIAMOND_AXE, 1, 80, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("netherite_axe", "§5ネザライトの斧", Material.NETHERITE_AXE, 1, 120, ShopCategory.WEAPONS))
        
        // 防具
        shopItems.add(ShopItem("iron_armor", "§f鉄の防具セット", Material.IRON_CHESTPLATE, 1, 40, ShopCategory.ARMOR,
            lore = listOf("§7ヘルメット、チェストプレート", "§7レギンス、ブーツのセット")))
        shopItems.add(ShopItem("diamond_armor", "§bダイヤモンドの防具セット", Material.DIAMOND_CHESTPLATE, 1, 100, ShopCategory.ARMOR,
            lore = listOf("§7ヘルメット、チェストプレート", "§7レギンス、ブーツのセット")))
        shopItems.add(ShopItem("netherite_armor", "§5ネザライトの防具セット", Material.NETHERITE_CHESTPLATE, 1, 200, ShopCategory.ARMOR,
            lore = listOf("§7ヘルメット、チェストプレート", "§7レギンス、ブーツのセット")))
        
        // その他装備
        shopItems.add(ShopItem("shield", "§f盾", Material.SHIELD, 1, 20, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("bow", "§f弓", Material.BOW, 1, 20, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("arrows", "§f矢 x8", Material.ARROW, 8, 10, ShopCategory.CONSUMABLES, DeathBehavior.DROP))
        
        // 消耗品
        shopItems.add(ShopItem("ender_pearl", "§5エンダーパール", Material.ENDER_PEARL, 1, 50, ShopCategory.CONSUMABLES, DeathBehavior.DROP))
        shopItems.add(ShopItem("golden_apple", "§6金のリンゴ", Material.GOLDEN_APPLE, 1, 30, ShopCategory.CONSUMABLES, DeathBehavior.DROP))
        
        // 建築ブロック
        shopItems.add(ShopItem("stone", "§7石 x16", Material.STONE, 16, 5, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("wood", "§6木材 x16", Material.OAK_PLANKS, 16, 5, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("dirt", "§6土 x16", Material.DIRT, 16, 3, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("sand", "§e砂 x16", Material.SAND, 16, 3, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("glass", "§fガラス x16", Material.GLASS, 16, 8, ShopCategory.BLOCKS))
        
        // 高級建築資材
        shopItems.add(ShopItem("obsidian", "§5黒曜石 x4", Material.OBSIDIAN, 4, 20, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("iron_block", "§f鉄ブロック x8", Material.IRON_BLOCK, 8, 15, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("redstone", "§cレッドストーン x16", Material.REDSTONE, 16, 10, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("tnt", "§cTNT x1", Material.TNT, 1, 30, ShopCategory.BLOCKS))
        
        // 特殊アイテム
        shopItems.add(ShopItem("water_bucket", "§9水バケツ", Material.WATER_BUCKET, 1, 15, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("lava_bucket", "§c溶岩バケツ", Material.LAVA_BUCKET, 1, 20, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("ladder", "§6はしご x16", Material.LADDER, 16, 10, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("fence", "§6フェンス x16", Material.OAK_FENCE, 16, 8, ShopCategory.BLOCKS))
    }
    
    fun openShop(player: Player, game: Game, team: Team) {
        val inventory = createShopInventory(game, team)
        player.openInventory(inventory)
    }
    
    private fun createShopInventory(game: Game, team: Team): Inventory {
        val inventory = Bukkit.createInventory(null, 54, "§6§lショップ §7- §e${game.getTeamCurrency(team)}G")
        
        // カテゴリごとにアイテムを配置
        var slot = 0
        
        // 武器
        addCategoryHeader(inventory, 0, "§c§l武器", Material.DIAMOND_SWORD)
        slot = 9
        shopItems.filter { it.category == ShopCategory.WEAPONS }.forEach { item ->
            if (slot < 18) {
                addShopItemToInventory(inventory, slot, item, game, team)
                slot++
            }
        }
        
        // 防具
        addCategoryHeader(inventory, 18, "§b§l防具", Material.DIAMOND_CHESTPLATE)
        slot = 27
        shopItems.filter { it.category == ShopCategory.ARMOR }.forEach { item ->
            if (slot < 36) {
                addShopItemToInventory(inventory, slot, item, game, team)
                slot++
            }
        }
        
        // 消耗品
        addCategoryHeader(inventory, 36, "§e§l消耗品", Material.GOLDEN_APPLE)
        slot = 45
        shopItems.filter { it.category == ShopCategory.CONSUMABLES }.forEach { item ->
            if (slot < 54) {
                addShopItemToInventory(inventory, slot, item, game, team)
                slot++
            }
        }
        
        // 建築ブロック（別ページまたは右側に配置）
        slot = 5
        shopItems.filter { it.category == ShopCategory.BLOCKS }.take(4).forEach { item ->
            addShopItemToInventory(inventory, slot, item, game, team)
            slot += 9
        }
        
        return inventory
    }
    
    private fun addCategoryHeader(inventory: Inventory, slot: Int, name: String, material: Material) {
        val header = ItemStack(material).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(name)
                setLore(listOf("§7カテゴリ"))
            }
        }
        inventory.setItem(slot, header)
    }
    
    private fun addShopItemToInventory(inventory: Inventory, slot: Int, item: ShopItem, game: Game, team: Team) {
        val displayItem = item.createItemStack()
        
        // 価格計算（割引適用）
        val discountedPrice = calculateDiscountedPrice(item, game, team)
        
        displayItem.itemMeta = displayItem.itemMeta?.apply {
            val loreList = mutableListOf<String>()
            loreList.addAll(item.lore)
            loreList.add("")
            
            // 価格表示
            if (discountedPrice < item.basePrice) {
                val discountPercent = ((1 - discountedPrice.toDouble() / item.basePrice) * 100).roundToInt()
                loreList.add("§m§7${item.basePrice}G§r §a${discountedPrice}G §7(${discountPercent}%OFF)")
            } else {
                loreList.add("§e価格: §f${item.basePrice}G")
            }
            
            // 死亡時の挙動
            loreList.add("§7死亡時: ${getDeathBehaviorText(item.deathBehavior)}")
            
            // 購入可能かどうか
            val currency = game.getTeamCurrency(team)
            if (currency >= discountedPrice) {
                loreList.add("§a§l✔ 購入可能")
            } else {
                loreList.add("§c§l✗ G不足")
            }
            
            setLore(loreList)
        }
        
        inventory.setItem(slot, displayItem)
    }
    
    private fun calculateDiscountedPrice(item: ShopItem, game: Game, team: Team): Int {
        // 建築資材は割引対象外
        if (item.category == ShopCategory.BLOCKS) {
            return item.basePrice
        }
        
        val scoreDiff = when (team) {
            Team.RED -> game.getBlueScore() - game.getRedScore()
            Team.BLUE -> game.getRedScore() - game.getBlueScore()
        }
        
        if (scoreDiff <= 0) return item.basePrice
        
        // config.ymlから割引率を取得
        val discountRates = mapOf(
            1 to plugin.config.getDouble("shop.discount.1-point", 0.1),
            2 to plugin.config.getDouble("shop.discount.2-point", 0.2),
            3 to plugin.config.getDouble("shop.discount.3-point", 0.3),
            4 to plugin.config.getDouble("shop.discount.4-point-plus", 0.4)
        )
        
        val discountRate = when {
            scoreDiff >= 4 -> discountRates[4] ?: 0.4
            else -> discountRates[scoreDiff] ?: 0.0
        }
        
        return (item.basePrice * (1 - discountRate)).roundToInt()
    }
    
    private fun getDeathBehaviorText(behavior: DeathBehavior): String {
        return when (behavior) {
            DeathBehavior.KEEP -> "§a保持"
            DeathBehavior.DROP -> "§eドロップ"
            DeathBehavior.DESTROY -> "§c消失"
        }
    }
    
    fun handlePurchase(player: Player, itemName: String, game: Game, team: Team): Boolean {
        val item = shopItems.find { it.displayName == itemName } ?: return false
        
        val price = calculateDiscountedPrice(item, game, team)
        
        // 通貨チェック
        if (game.getTeamCurrency(team) < price) {
            player.sendMessage(Component.text("購入に必要なGが不足しています！ (必要: ${price}G)").color(NamedTextColor.RED))
            return false
        }
        
        // 購入処理
        if (!game.spendTeamCurrency(team, price, player, item.displayName)) {
            return false
        }
        
        // アイテム付与
        when (item.id) {
            "iron_armor", "diamond_armor", "netherite_armor" -> {
                giveArmorSet(player, item.material)
            }
            else -> {
                val itemStack = item.createItemStack()
                
                // インベントリに空きがない場合は足元にドロップ
                val leftover = player.inventory.addItem(itemStack)
                if (leftover.isNotEmpty()) {
                    leftover.values.forEach { 
                        player.world.dropItem(player.location, it)
                    }
                    player.sendMessage(Component.text("インベントリが満杯のため、アイテムを足元にドロップしました").color(NamedTextColor.YELLOW))
                }
            }
        }
        
        // 購入したアイテムにメタデータを付与（死亡時の処理用）
        tagPurchasedItem(player, item)
        
        player.sendMessage(Component.text("${item.displayName} を購入しました！").color(NamedTextColor.GREEN))
        return true
    }
    
    private fun giveArmorSet(player: Player, chestplateMaterial: Material) {
        val armorMaterials = when (chestplateMaterial) {
            Material.IRON_CHESTPLATE -> listOf(
                Material.IRON_HELMET,
                Material.IRON_CHESTPLATE,
                Material.IRON_LEGGINGS,
                Material.IRON_BOOTS
            )
            Material.DIAMOND_CHESTPLATE -> listOf(
                Material.DIAMOND_HELMET,
                Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS
            )
            Material.NETHERITE_CHESTPLATE -> listOf(
                Material.NETHERITE_HELMET,
                Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_BOOTS
            )
            else -> return
        }
        
        val armorItems = armorMaterials.map { ItemStack(it) }
        
        // 現在の装備を保存
        val currentArmor = player.inventory.armorContents
        
        // 新しい防具を装備
        player.inventory.helmet = armorItems[0]
        player.inventory.chestplate = armorItems[1]
        player.inventory.leggings = armorItems[2]
        player.inventory.boots = armorItems[3]
        
        // 古い防具をインベントリに追加またはドロップ
        currentArmor.filterNotNull().forEach { item ->
            val leftover = player.inventory.addItem(item)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach {
                    player.world.dropItem(player.location, it)
                }
            }
        }
    }
    
    private fun tagPurchasedItem(player: Player, item: ShopItem) {
        // プレイヤーのメタデータに購入アイテム情報を保存
        val purchasedItems = player.getMetadata("ctf_purchased_items")
            .firstOrNull()?.value() as? MutableList<ShopItem> ?: mutableListOf()
        
        purchasedItems.add(item)
        player.setMetadata("ctf_purchased_items", 
            org.bukkit.metadata.FixedMetadataValue(plugin, purchasedItems))
    }
    
    fun getShopItem(displayName: String): ShopItem? {
        return shopItems.find { it.displayName == displayName }
    }
    
    fun createShopItem(): ItemStack {
        return ItemStack(Material.EMERALD).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§a§lショップ")
                setLore(listOf(
                    "§7右クリックでショップを開く",
                    "§7",
                    "§e自陣スポーン地点の近くで使用可能"
                ))
            }
        }
    }
}