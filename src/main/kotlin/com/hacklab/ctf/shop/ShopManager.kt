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
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.enchantments.Enchantment

class ShopManager(private val plugin: Main) {
    
    private val shopItems = mutableListOf<ShopItem>()
    private val shopItemKey = NamespacedKey(plugin, "shop_item_id")
    private val deathBehaviorKey = NamespacedKey(plugin, "death_behavior")
    private val playerPages = mutableMapOf<Player, Int>() // プレイヤーごとの現在のページ
    private val purchaseTracker = mutableMapOf<String, MutableMap<String, MutableMap<String, Int>>>() // ゲームID -> プレイヤー/チーム -> アイテムID -> 購入数
    
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
        
        // エンチャント付きアイテム
        shopItems.add(ShopItem("enchanted_bow", "§bエンチャント弓", Material.BOW, 1, 50, ShopCategory.WEAPONS,
            enchantments = mapOf(
                Enchantment.POWER to 2,
                Enchantment.INFINITY to 1
            ),
            lore = listOf("§7パワー II", "§7無限 I")
        ))
        
        shopItems.add(ShopItem("enchanted_sword", "§bエンチャント剣", Material.DIAMOND_SWORD, 1, 100, ShopCategory.WEAPONS,
            enchantments = mapOf(
                Enchantment.SHARPNESS to 3,
                Enchantment.KNOCKBACK to 1
            ),
            lore = listOf("§7ダメージ増加 III", "§7ノックバック I")
        ))
        
        shopItems.add(ShopItem("unbreaking_pickaxe", "§b耐久無限ツルハシ", Material.IRON_PICKAXE, 1, 40, ShopCategory.WEAPONS,
            unbreakable = true,
            lore = listOf("§7壊れない")
        ))
        
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
        
        // 特殊エンチャント防具
        shopItems.add(ShopItem("protection_armor", "§5プロテクション防具セット", Material.DIAMOND_CHESTPLATE, 1, 150, ShopCategory.ARMOR,
            enchantments = mapOf(
                Enchantment.PROTECTION to 2
            ),
            lore = listOf("§7ダメージ軽減 II", "§7ヘルメット、チェストプレート", "§7レギンス、ブーツのセット")
        ))
        
        // 特殊アイテム
        shopItems.add(ShopItem("water_bucket", "§9水バケツ", Material.WATER_BUCKET, 1, 15, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("lava_bucket", "§c溶岩バケツ", Material.LAVA_BUCKET, 1, 20, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("ladder", "§6はしご x16", Material.LADDER, 16, 10, ShopCategory.BLOCKS))
        shopItems.add(ShopItem("fence", "§6フェンス x16", Material.OAK_FENCE, 16, 8, ShopCategory.BLOCKS))
        
        // 購入制限付きアイテムの例
        shopItems.add(ShopItem("ender_chest", "§5エンダーチェスト", Material.ENDER_CHEST, 1, 50, ShopCategory.BLOCKS,
            maxPurchasePerTeam = 1,
            lore = listOf("§7チームで１つまで")
        ))
        
        shopItems.add(ShopItem("enchanted_golden_apple", "§6§lエンチャント金リンゴ", Material.ENCHANTED_GOLDEN_APPLE, 1, 100, ShopCategory.CONSUMABLES,
            maxPurchasePerPlayer = 2,
            deathBehavior = DeathBehavior.DROP,
            lore = listOf("§7一人2個まで")
        ))
    }
    
    fun openShop(player: Player, game: Game, team: Team, page: Int = 0) {
        playerPages[player] = page
        val inventory = createShopInventory(player, game, team, page)
        player.openInventory(inventory)
    }
    
    private fun createShopInventory(player: Player, game: Game, team: Team, page: Int): Inventory {
        val inventory = Bukkit.createInventory(null, 54, "§6§lショップ §7- §e${game.getTeamCurrency(team)}G §7(ページ ${page + 1})")
        
        // カテゴリーのページング
        val categories = listOf(
            ShopCategory.WEAPONS to "§c§l武器",
            ShopCategory.ARMOR to "§b§l防具",
            ShopCategory.CONSUMABLES to "§e§l消耗品",
            ShopCategory.BLOCKS to "§5§lブロック"
        )
        
        if (page < categories.size) {
            val (category, categoryName) = categories[page]
            val categoryItems = shopItems.filter { it.category == category }
            
            // カテゴリーヘッダー
            val headerMaterial = when (category) {
                ShopCategory.WEAPONS -> Material.DIAMOND_SWORD
                ShopCategory.ARMOR -> Material.DIAMOND_CHESTPLATE
                ShopCategory.CONSUMABLES -> Material.GOLDEN_APPLE
                ShopCategory.BLOCKS -> Material.STONE
            }
            addCategoryHeader(inventory, 4, categoryName, headerMaterial)
            
            // アイテムを配置（最大44個）
            var slot = 9
            categoryItems.take(44).forEach { item ->
                if (slot % 9 == 8) slot++ // 最右列をスキップ
                if (slot < 45) {
                    addShopItemToInventory(inventory, slot, item, game, team, player)
                    slot++
                }
            }
        }
        
        // ナビゲーションボタン
        // 前のページ
        if (page > 0) {
            val prevPage = ItemStack(Material.ARROW).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§a§l← 前のページ")
                }
            }
            inventory.setItem(45, prevPage)
        }
        
        // メインメニュー
        val mainMenu = ItemStack(Material.COMPASS).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§6§lメインメニュー")
                setLore(listOf(
                    "§7カテゴリー一覧：",
                    "§c武器",
                    "§b防具",
                    "§e消耗品",
                    "§5ブロック"
                ))
            }
        }
        inventory.setItem(49, mainMenu)
        
        // 次のページ
        if (page < categories.size - 1) {
            val nextPage = ItemStack(Material.ARROW).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§a§l次のページ →")
                }
            }
            inventory.setItem(53, nextPage)
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
    
    private fun addShopItemToInventory(inventory: Inventory, slot: Int, item: ShopItem, game: Game, team: Team, player: Player) {
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
            
            // 購入制限情報
            val gameId = game.name
            val playerPurchases = getPurchaseCount(gameId, player.uniqueId.toString(), item.id)
            val teamPurchases = getPurchaseCount(gameId, "team_$team", item.id)
            
            if (item.maxPurchasePerPlayer > 0) {
                loreList.add("§7個人購入制限: §e$playerPurchases§7/§e${item.maxPurchasePerPlayer}")
            }
            if (item.maxPurchasePerTeam > 0) {
                loreList.add("§7チーム購入制限: §e$teamPurchases§7/§e${item.maxPurchasePerTeam}")
            }
            
            // 購入可能かどうか
            val currency = game.getTeamCurrency(team)
            val canPurchase = checkPurchaseLimit(gameId, player, team, item)
            
            if (!canPurchase) {
                loreList.add("§c§l✗ 購入制限に達しました")
            } else if (currency >= discountedPrice) {
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
        // プレーンテキストに変換して比較
        val plainItemName = itemName.replace("§[0-9a-fklmnor]".toRegex(), "")
        val item = shopItems.find { 
            val plainDisplayName = it.displayName.replace("§[0-9a-fklmnor]".toRegex(), "")
            plainDisplayName == plainItemName
        } ?: return false
        
        // フェーズチェック
        if (!item.availablePhases.contains(game.phase)) {
            player.sendMessage(Component.text("このアイテムは現在のフェーズでは購入できません").color(NamedTextColor.RED))
            return false
        }
        
        // 購入制限チェック
        if (!checkPurchaseLimit(game.name, player, team, item)) {
            player.sendMessage(Component.text("購入制限に達しています").color(NamedTextColor.RED))
            return false
        }
        
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
                giveArmorSet(player, item)
            }
            else -> {
                val itemStack = createShopItemStack(item)
                
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
        
        // 購入数を記録
        recordPurchase(game.name, player.uniqueId.toString(), item.id)
        recordPurchase(game.name, "team_$team", item.id)
        
        player.sendMessage(Component.text("${item.displayName} を購入しました！").color(NamedTextColor.GREEN))
        return true
    }
    
    private fun createShopItemStack(item: ShopItem): ItemStack {
        return item.createItemStack().apply {
            itemMeta = itemMeta?.apply {
                val container = persistentDataContainer
                container.set(shopItemKey, PersistentDataType.STRING, item.id)
                container.set(deathBehaviorKey, PersistentDataType.STRING, item.deathBehavior.name)
                
                // エンチャントはcreateItemStack()で既に追加されている
            }
        }
    }
    
    private fun giveArmorSet(player: Player, item: ShopItem) {
        val armorMaterials = when (item.material) {
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
        
        val armorItems = armorMaterials.map { material ->
            ItemStack(material).apply {
                itemMeta = itemMeta?.apply {
                    val container = persistentDataContainer
                    container.set(shopItemKey, PersistentDataType.STRING, item.id)
                    container.set(deathBehaviorKey, PersistentDataType.STRING, item.deathBehavior.name)
                }
            }
        }
        
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
    
    fun getItemDeathBehavior(itemStack: ItemStack): DeathBehavior? {
        val meta = itemStack.itemMeta ?: return null
        val behaviorString = meta.persistentDataContainer.get(deathBehaviorKey, PersistentDataType.STRING)
        return behaviorString?.let { 
            try {
                DeathBehavior.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
    
    fun isShopItem(itemStack: ItemStack): Boolean {
        val meta = itemStack.itemMeta ?: return false
        return meta.persistentDataContainer.has(shopItemKey, PersistentDataType.STRING)
    }
    
    fun getShopItem(displayName: String): ShopItem? {
        // プレーンテキストに変換して比較
        val plainDisplayName = displayName.replace("§[0-9a-fklmnor]".toRegex(), "")
        return shopItems.find {
            val plainItemName = it.displayName.replace("§[0-9a-fklmnor]".toRegex(), "")
            plainItemName == plainDisplayName
        }
    }
    
    fun createShopItem(): ItemStack {
        return ItemStack(Material.EMERALD).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§a§lショップ")
                setLore(listOf(
                    "§7右クリックでショップを開く"
                ))
            }
        }
    }
    
    fun getCurrentPage(player: Player): Int {
        return playerPages[player] ?: 0
    }
    
    fun openCategoryMenu(player: Player, game: Game, team: Team) {
        val inventory = Bukkit.createInventory(null, 27, "§6§lショップ - カテゴリー選択")
        
        // 武器カテゴリー
        val weapons = ItemStack(Material.DIAMOND_SWORD).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§c§l武器")
                setLore(listOf(
                    "§7剣、斧、弓など",
                    "§7",
                    "§eクリックして開く"
                ))
            }
        }
        inventory.setItem(10, weapons)
        
        // 防具カテゴリー
        val armor = ItemStack(Material.DIAMOND_CHESTPLATE).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§b§l防具")
                setLore(listOf(
                    "§7鉄、ダイヤ、ネザライトの防具",
                    "§7",
                    "§eクリックして開く"
                ))
            }
        }
        inventory.setItem(12, armor)
        
        // 消耗品カテゴリー
        val consumables = ItemStack(Material.GOLDEN_APPLE).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§e§l消耗品")
                setLore(listOf(
                    "§7エンダーパール、金リンゴなど",
                    "§7",
                    "§eクリックして開く"
                ))
            }
        }
        inventory.setItem(14, consumables)
        
        // ブロックカテゴリー
        val blocks = ItemStack(Material.STONE).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§5§lブロック")
                setLore(listOf(
                    "§7建築用ブロック、TNTなど",
                    "§7",
                    "§eクリックして開く"
                ))
            }
        }
        inventory.setItem(16, blocks)
        
        player.openInventory(inventory)
    }
    
    private fun checkPurchaseLimit(gameId: String, player: Player, team: Team, item: ShopItem): Boolean {
        // プレイヤー制限チェック
        if (item.maxPurchasePerPlayer > 0) {
            val playerPurchases = getPurchaseCount(gameId, player.uniqueId.toString(), item.id)
            if (playerPurchases >= item.maxPurchasePerPlayer) {
                return false
            }
        }
        
        // チーム制限チェック
        if (item.maxPurchasePerTeam > 0) {
            val teamPurchases = getPurchaseCount(gameId, "team_$team", item.id)
            if (teamPurchases >= item.maxPurchasePerTeam) {
                return false
            }
        }
        
        return true
    }
    
    private fun getPurchaseCount(gameId: String, purchaserId: String, itemId: String): Int {
        return purchaseTracker
            .getOrPut(gameId) { mutableMapOf() }
            .getOrPut(purchaserId) { mutableMapOf() }
            .getOrDefault(itemId, 0)
    }
    
    private fun recordPurchase(gameId: String, purchaserId: String, itemId: String) {
        val gameTracker = purchaseTracker.getOrPut(gameId) { mutableMapOf() }
        val purchaserTracker = gameTracker.getOrPut(purchaserId) { mutableMapOf() }
        purchaserTracker[itemId] = purchaserTracker.getOrDefault(itemId, 0) + 1
    }
    
    fun resetGamePurchases(gameId: String) {
        purchaseTracker.remove(gameId)
    }
}