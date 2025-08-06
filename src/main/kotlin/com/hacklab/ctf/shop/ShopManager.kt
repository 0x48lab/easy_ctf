package com.hacklab.ctf.shop

import com.hacklab.ctf.Main
import com.hacklab.ctf.Game
import com.hacklab.ctf.utils.Team
import com.hacklab.ctf.utils.GamePhase
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
        val lang = plugin.languageManager
        
        // 武器
        shopItems.add(ShopItem("wooden_sword", lang.getMessage("shop.items.wooden_sword"), Material.WOODEN_SWORD, 1, 10, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("stone_sword", lang.getMessage("shop.items.stone_sword"), Material.STONE_SWORD, 1, 15, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("iron_sword", lang.getMessage("shop.items.iron_sword"), Material.IRON_SWORD, 1, 25, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("diamond_sword", lang.getMessage("shop.items.diamond_sword"), Material.DIAMOND_SWORD, 1, 60, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("netherite_sword", lang.getMessage("shop.items.netherite_sword"), Material.NETHERITE_SWORD, 1, 100, ShopCategory.WEAPONS))
        
        shopItems.add(ShopItem("wooden_axe", lang.getMessage("shop.items.wooden_axe"), Material.WOODEN_AXE, 1, 15, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("stone_axe", lang.getMessage("shop.items.stone_axe"), Material.STONE_AXE, 1, 20, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("iron_axe", lang.getMessage("shop.items.iron_axe"), Material.IRON_AXE, 1, 45, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("diamond_axe", lang.getMessage("shop.items.diamond_axe"), Material.DIAMOND_AXE, 1, 80, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("netherite_axe", lang.getMessage("shop.items.netherite_axe"), Material.NETHERITE_AXE, 1, 120, ShopCategory.WEAPONS))
        
        // その他装備
        shopItems.add(ShopItem("shield", lang.getMessage("shop.items.shield"), Material.SHIELD, 1, 20, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("bow", lang.getMessage("shop.items.bow"), Material.BOW, 1, 20, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("arrows", lang.getMessage("shop.items.arrows"), Material.ARROW, 8, 10, ShopCategory.CONSUMABLES, DeathBehavior.DROP))
        
        // エンチャント付きアイテム
        shopItems.add(ShopItem("enchanted_bow", lang.getMessage("shop.items.enchanted_bow"), Material.BOW, 1, 50, ShopCategory.WEAPONS,
            enchantments = mapOf(
                Enchantment.POWER to 2,
                Enchantment.INFINITY to 1
            ),
            lore = listOf(lang.getMessage("shop.items.enchanted_bow.lore1"), lang.getMessage("shop.items.enchanted_bow.lore2"))
        ))
        
        shopItems.add(ShopItem("enchanted_sword", lang.getMessage("shop.items.enchanted_sword"), Material.DIAMOND_SWORD, 1, 100, ShopCategory.WEAPONS,
            enchantments = mapOf(
                Enchantment.SHARPNESS to 3,
                Enchantment.KNOCKBACK to 1
            ),
            lore = listOf(lang.getMessage("shop.items.enchanted_sword.lore1"), lang.getMessage("shop.items.enchanted_sword.lore2"))
        ))
        
        
        // 消耗品
        shopItems.add(ShopItem("bread", lang.getMessage("shop.items.bread"), Material.BREAD, 8, 5, ShopCategory.CONSUMABLES, DeathBehavior.DROP))
        shopItems.add(ShopItem("cooked_beef", lang.getMessage("shop.items.cooked_beef"), Material.COOKED_BEEF, 8, 10, ShopCategory.CONSUMABLES, DeathBehavior.DROP))
        shopItems.add(ShopItem("golden_apple", lang.getMessage("shop.items.golden_apple"), Material.GOLDEN_APPLE, 1, 30, ShopCategory.CONSUMABLES, DeathBehavior.DROP))
        shopItems.add(ShopItem("ender_pearl", lang.getMessage("shop.items.ender_pearl"), Material.ENDER_PEARL, 1, 50, ShopCategory.CONSUMABLES, DeathBehavior.DROP))
        
        // 建築ブロック - チームカラーブロックは動的に追加されるため、ここでは追加しない
        
        // 防具
        shopItems.add(ShopItem("iron_helmet", lang.getMessage("shop.items.iron_helmet"), Material.IRON_HELMET, 1, 15, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("iron_chestplate", lang.getMessage("shop.items.iron_chestplate"), Material.IRON_CHESTPLATE, 1, 30, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("iron_leggings", lang.getMessage("shop.items.iron_leggings"), Material.IRON_LEGGINGS, 1, 25, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("iron_boots", lang.getMessage("shop.items.iron_boots"), Material.IRON_BOOTS, 1, 15, ShopCategory.WEAPONS))
        
        shopItems.add(ShopItem("diamond_helmet", lang.getMessage("shop.items.diamond_helmet"), Material.DIAMOND_HELMET, 1, 40, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("diamond_chestplate", lang.getMessage("shop.items.diamond_chestplate"), Material.DIAMOND_CHESTPLATE, 1, 80, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("diamond_leggings", lang.getMessage("shop.items.diamond_leggings"), Material.DIAMOND_LEGGINGS, 1, 70, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("diamond_boots", lang.getMessage("shop.items.diamond_boots"), Material.DIAMOND_BOOTS, 1, 40, ShopCategory.WEAPONS))
        
        shopItems.add(ShopItem("netherite_helmet", lang.getMessage("shop.items.netherite_helmet"), Material.NETHERITE_HELMET, 1, 60, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("netherite_chestplate", lang.getMessage("shop.items.netherite_chestplate"), Material.NETHERITE_CHESTPLATE, 1, 120, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("netherite_leggings", lang.getMessage("shop.items.netherite_leggings"), Material.NETHERITE_LEGGINGS, 1, 100, ShopCategory.WEAPONS))
        shopItems.add(ShopItem("netherite_boots", lang.getMessage("shop.items.netherite_boots"), Material.NETHERITE_BOOTS, 1, 60, ShopCategory.WEAPONS))
        
        // 特殊ブロック（どちらのチームでも使える）
        shopItems.add(ShopItem("tnt", lang.getMessage("shop.items.tnt"), Material.TNT, 1, 100, ShopCategory.BLOCKS))
        
        
        // 特殊アイテム
        
        // 建築用ツール（建築フェーズのみ）
        shopItems.add(ShopItem("shears", lang.getMessage("shop.items.shears"), Material.SHEARS, 1, 15, ShopCategory.WEAPONS,
            availablePhases = setOf(GamePhase.BUILD)))
        
        // 戦闘用ツール削除 - ブロック破壊制限が撤廃されたため不要
        
        // 購入制限付きアイテムの例
        // エンダーチェスト削除（チームカラーブロックのみ販売）
        
        shopItems.add(ShopItem("enchanted_golden_apple", lang.getMessage("shop.items.enchanted_golden_apple"), Material.ENCHANTED_GOLDEN_APPLE, 1, 100, ShopCategory.CONSUMABLES,
            maxPurchasePerPlayer = 2,
            deathBehavior = DeathBehavior.DROP,
            lore = listOf(lang.getMessage("shop.items.enchanted_golden_apple.lore"))
        ))
    }
    
    fun openShop(player: Player, game: Game, team: Team, page: Int = 0) {
        playerPages[player] = page
        val inventory = createShopInventory(player, game, team, page)
        player.openInventory(inventory)
    }
    
    private fun getTeamColoredBlockItems(team: Team): List<ShopItem> {
        val items = mutableListOf<ShopItem>()
        
        // チームカラーブロックは無限に提供されるため、ショップでは販売しない
        
        val lang = plugin.languageManager
        
        // フェンスや装置などの追加アイテム（どちらのチームでも使用可能）
        items.add(ShopItem("fence", lang.getMessage("shop.items.oak_fence"), Material.OAK_FENCE, 16, 8, ShopCategory.BLOCKS))
        items.add(ShopItem("iron_fence", lang.getMessage("shop.items.iron_bars"), Material.IRON_BARS, 16, 12, ShopCategory.BLOCKS))
        items.add(ShopItem("glass_pane", lang.getMessage("shop.items.glass"), Material.GLASS_PANE, 16, 6, ShopCategory.BLOCKS))
        items.add(ShopItem("ladder", lang.getMessage("shop.items.ladder"), Material.LADDER, 16, 10, ShopCategory.BLOCKS))
        items.add(ShopItem("trapdoor", lang.getMessage("shop.items.oak_trapdoor"), Material.OAK_TRAPDOOR, 4, 8, ShopCategory.BLOCKS))
        items.add(ShopItem("iron_trapdoor", lang.getMessage("shop.items.iron_trapdoor"), Material.IRON_TRAPDOOR, 2, 15, ShopCategory.BLOCKS))
        items.add(ShopItem("door", lang.getMessage("shop.items.oak_door"), Material.OAK_DOOR, 2, 10, ShopCategory.BLOCKS))
        items.add(ShopItem("iron_door", lang.getMessage("shop.items.iron_door"), Material.IRON_DOOR, 1, 20, ShopCategory.BLOCKS))
        items.add(ShopItem("torch", lang.getMessage("shop.items.torch"), Material.TORCH, 16, 5, ShopCategory.BLOCKS))
        items.add(ShopItem("redstone_torch", lang.getMessage("shop.items.redstone_torch"), Material.REDSTONE_TORCH, 8, 8, ShopCategory.BLOCKS))
        items.add(ShopItem("stone_button", lang.getMessage("shop.items.stone_button"), Material.STONE_BUTTON, 4, 5, ShopCategory.BLOCKS))
        items.add(ShopItem("lever", lang.getMessage("shop.items.lever"), Material.LEVER, 4, 5, ShopCategory.BLOCKS))
        items.add(ShopItem("pressure_plate", lang.getMessage("shop.items.stone_pressure_plate"), Material.STONE_PRESSURE_PLATE, 4, 8, ShopCategory.BLOCKS))
        items.add(ShopItem("piston", lang.getMessage("shop.items.piston"), Material.PISTON, 2, 20, ShopCategory.BLOCKS))
        items.add(ShopItem("sticky_piston", lang.getMessage("shop.items.sticky_piston"), Material.STICKY_PISTON, 2, 30, ShopCategory.BLOCKS))
        items.add(ShopItem("redstone", lang.getMessage("shop.items.redstone_dust"), Material.REDSTONE, 16, 10, ShopCategory.BLOCKS))
        items.add(ShopItem("redstone_block", lang.getMessage("shop.items.redstone_block"), Material.REDSTONE_BLOCK, 1, 15, ShopCategory.BLOCKS))
        items.add(ShopItem("hopper", lang.getMessage("shop.items.hopper"), Material.HOPPER, 1, 25, ShopCategory.BLOCKS))
        items.add(ShopItem("dispenser", lang.getMessage("shop.items.dispenser"), Material.DISPENSER, 1, 20, ShopCategory.BLOCKS))
        items.add(ShopItem("slime_block", lang.getMessage("shop.items.slime_block"), Material.SLIME_BLOCK, 4, 25, ShopCategory.BLOCKS))
        
        return items
    }
    
    private fun createShopInventory(player: Player, game: Game, team: Team, page: Int): Inventory {
        val lang = plugin.languageManager
        val title = lang.getMessage("shop.title")
            .replace("{currency}", game.getTeamCurrency(team).toString())
            .replace("{page}", (page + 1).toString())
        val inventory = Bukkit.createInventory(null, 54, LegacyComponentSerializer.legacySection().deserialize(title))
        
        // カテゴリーのページング
        val categories = listOf(
            ShopCategory.WEAPONS to lang.getMessage("shop.categories.weapons"),
            ShopCategory.CONSUMABLES to lang.getMessage("shop.categories.consumables"),
            ShopCategory.BLOCKS to lang.getMessage("shop.categories.blocks")
        )
        
        if (page < categories.size) {
            val (category, categoryName) = categories[page]
            val categoryItems = if (category == ShopCategory.BLOCKS) {
                // ブロックカテゴリーの場合、チームカラーのブロックを追加
                val baseItems = shopItems.filter { it.category == category && it.availablePhases.contains(game.phase) }
                val teamBlocks = getTeamColoredBlockItems(team)
                baseItems + teamBlocks
            } else {
                shopItems.filter { it.category == category && it.availablePhases.contains(game.phase) }
            }
            
            // カテゴリーヘッダー
            val headerMaterial = when (category) {
                ShopCategory.WEAPONS -> Material.DIAMOND_SWORD
                ShopCategory.ARMOR -> Material.DIAMOND_CHESTPLATE // 使用されないが必要
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
                    displayName(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.navigation.previous_page")))
                }
            }
            inventory.setItem(45, prevPage)
        }
        
        // メインメニュー
        val mainMenu = ItemStack(Material.COMPASS).apply {
            itemMeta = itemMeta?.apply {
                displayName(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.navigation.main_menu")))
                lore(listOf(
                    LegacyComponentSerializer.legacySection().deserialize("§7カテゴリー一覧："),
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_descriptions.weapons")),
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_descriptions.consumables")),
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_descriptions.blocks"))
                ))
            }
        }
        inventory.setItem(49, mainMenu)
        
        // 次のページ
        if (page < categories.size - 1) {
            val nextPage = ItemStack(Material.ARROW).apply {
                itemMeta = itemMeta?.apply {
                    displayName(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.navigation.next_page")))
                }
            }
            inventory.setItem(53, nextPage)
        }
        
        return inventory
    }
    
    private fun addCategoryHeader(inventory: Inventory, slot: Int, name: String, material: Material) {
        val header = ItemStack(material).apply {
            itemMeta = itemMeta?.apply {
                displayName(LegacyComponentSerializer.legacySection().deserialize(name))
                lore(listOf(LegacyComponentSerializer.legacySection().deserialize(plugin.languageManager.getMessage("shop.navigation.category"))))
            }
        }
        inventory.setItem(slot, header)
    }
    
    private fun addShopItemToInventory(inventory: Inventory, slot: Int, item: ShopItem, game: Game, team: Team, player: Player) {
        val displayItem = item.createItemStack()
        
        // 価格計算（割引適用）
        val discountedPrice = calculateDiscountedPrice(item, game, team)
        
        displayItem.itemMeta = displayItem.itemMeta?.apply {
            // displayNameを明示的に設定（ShopItem.createItemStack()で設定されたものを保持）
            displayName(LegacyComponentSerializer.legacySection().deserialize(item.displayName))
            
            val loreComponents = mutableListOf<Component>()
            item.lore.forEach { loreComponents.add(LegacyComponentSerializer.legacySection().deserialize(it)) }
            loreComponents.add(Component.empty())
            
            val lang = plugin.languageManager
            
            // 価格表示
            if (discountedPrice < item.basePrice) {
                val discountPercent = ((1 - discountedPrice.toDouble() / item.basePrice) * 100).roundToInt()
                loreComponents.add(LegacyComponentSerializer.legacySection().deserialize("§m§7${item.basePrice}G§r §a${discountedPrice}G §7(${discountPercent}%OFF)"))
            } else {
                val priceText = lang.getMessage("shop.item_info.price").replace("{price}", item.basePrice.toString())
                loreComponents.add(LegacyComponentSerializer.legacySection().deserialize(priceText))
            }
            
            // 死亡時の挙動
            val deathBehaviorText = lang.getMessage("shop.item_info.death_behavior").replace("{behavior}", getDeathBehaviorText(item.deathBehavior))
            loreComponents.add(LegacyComponentSerializer.legacySection().deserialize(deathBehaviorText))
            
            // 購入制限情報
            val gameId = game.name
            val playerPurchases = getPurchaseCount(gameId, player.uniqueId.toString(), item.id)
            val teamPurchases = getPurchaseCount(gameId, "team_$team", item.id)
            
            if (item.maxPurchasePerPlayer > 0) {
                val personalLimitText = lang.getMessage("shop.item_info.personal_limit")
                    .replace("{current}", playerPurchases.toString())
                    .replace("{max}", item.maxPurchasePerPlayer.toString())
                loreComponents.add(LegacyComponentSerializer.legacySection().deserialize(personalLimitText))
            }
            if (item.maxPurchasePerTeam > 0) {
                val teamLimitText = lang.getMessage("shop.item_info.team_limit")
                    .replace("{current}", teamPurchases.toString())
                    .replace("{max}", item.maxPurchasePerTeam.toString())
                loreComponents.add(LegacyComponentSerializer.legacySection().deserialize(teamLimitText))
            }
            
            // 購入可能かどうか
            val currency = game.getTeamCurrency(team)
            val canPurchase = checkPurchaseLimit(gameId, player, team, item)
            
            if (!canPurchase) {
                loreComponents.add(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.item_info.purchase_limit_reached")))
            } else if (currency >= discountedPrice) {
                loreComponents.add(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.item_info.can_purchase")))
            } else {
                loreComponents.add(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.item_info.insufficient_funds")))
            }
            
            lore(loreComponents)
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
            Team.SPECTATOR -> 0  // Spectators get no discount
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
        val lang = plugin.languageManager
        return when (behavior) {
            DeathBehavior.KEEP -> lang.getMessage("shop.item_info.death_behavior.keep")
            DeathBehavior.DROP -> lang.getMessage("shop.item_info.death_behavior.drop")
            DeathBehavior.DESTROY -> lang.getMessage("shop.item_info.death_behavior.destroy")
        }
    }
    
    fun handlePurchase(player: Player, itemName: String, game: Game, team: Team): Boolean {
        // デバッグログ
        plugin.logger.info(plugin.languageManager.getMessage("log.shop-purchase-called", "item" to itemName))
        
        // アイテムを名前で検索（完全一致を試みる）
        var item = shopItems.find { it.displayName == itemName }
        
        // 完全一致が見つからない場合は、レガシーフォーマットを削除して比較
        if (item == null) {
            val plainItemName = itemName.replace("§[0-9a-fklmnor]".toRegex(), "")
            item = shopItems.find { 
                val plainDisplayName = it.displayName.replace("§[0-9a-fklmnor]".toRegex(), "")
                plainDisplayName == plainItemName
            }
        }
        
        // それでも見つからない場合は、動的に作成されるアイテムから検索
        if (item == null) {
            val teamBlocks = getTeamColoredBlockItems(team)
            item = teamBlocks.find { it.displayName == itemName }
            
            if (item == null) {
                val plainItemName = itemName.replace("§[0-9a-fklmnor]".toRegex(), "")
                item = teamBlocks.find { 
                    val plainDisplayName = it.displayName.replace("§[0-9a-fklmnor]".toRegex(), "")
                    plainDisplayName == plainItemName
                }
            }
        }
        
        if (item == null) {
            plugin.logger.warning(plugin.languageManager.getMessage("log.shop-item-not-found", "item" to itemName))
            // 利用可能なアイテム名をログ出力
            plugin.logger.info(plugin.languageManager.getMessage("log.shop-available-items"))
            shopItems.forEach { shopItem ->
                plugin.logger.info(plugin.languageManager.getMessage("log.shop-item-list", "item" to shopItem.displayName))
            }
            return false
        }
        
        // フェーズチェック
        if (!item.availablePhases.contains(game.phase)) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("shop.phase-restricted")).color(NamedTextColor.RED))
            return false
        }
        
        // 購入制限チェック
        if (!checkPurchaseLimit(game.name, player, team, item)) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("shop.purchase-limit")).color(NamedTextColor.RED))
            return false
        }
        
        val price = calculateDiscountedPrice(item, game, team)
        
        // 通貨チェック
        if (game.getTeamCurrency(team) < price) {
            player.sendMessage(Component.text(plugin.languageManager.getMessage("shop.insufficient-funds", "price" to price.toString())).color(NamedTextColor.RED))
            return false
        }
        
        // 購入処理
        if (!game.spendTeamCurrency(team, price, player, item.displayName)) {
            return false
        }
        
        // アイテム付与
        when (item.id) {
            else -> {
                val itemStack = createShopItemStack(item)
                
                // インベントリに空きがない場合は足元にドロップ
                val leftover = player.inventory.addItem(itemStack)
                if (leftover.isNotEmpty()) {
                    leftover.values.forEach { 
                        player.world.dropItem(player.location, it)
                    }
                    player.sendMessage(Component.text(plugin.languageManager.getMessage("shop.inventory-full")).color(NamedTextColor.YELLOW))
                }
            }
        }
        
        // 購入数を記録
        recordPurchase(game.name, player.uniqueId.toString(), item.id)
        recordPurchase(game.name, "team_$team", item.id)
        
        // 購入金額統計を記録
        game.playerMoneySpent[player.uniqueId] = (game.playerMoneySpent[player.uniqueId] ?: 0) + price
        
        val itemName = LegacyComponentSerializer.legacySection().serialize(
            LegacyComponentSerializer.legacySection().deserialize(item.displayName)
        )
        player.sendMessage(Component.text(plugin.languageManager.getMessage("shop.purchase-success", "item" to itemName)).color(NamedTextColor.GREEN))
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
        val lang = plugin.languageManager
        return ItemStack(Material.EMERALD).apply {
            itemMeta = itemMeta?.apply {
                displayName(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.item.emerald.name")))
                lore(listOf(
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.item.emerald.lore1")),
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.item.emerald.lore2"))
                ))
                // ショップアイテムであることを識別するタグを追加
                persistentDataContainer.set(shopItemKey, PersistentDataType.STRING, "shop_opener")
            }
        }
    }
    
    fun getCurrentPage(player: Player): Int {
        return playerPages[player] ?: 0
    }
    
    fun openCategoryMenu(player: Player, game: Game, team: Team) {
        val lang = plugin.languageManager
        val inventory = Bukkit.createInventory(null, 27, LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.title")))
        
        // 武器カテゴリー
        val weapons = ItemStack(Material.DIAMOND_SWORD).apply {
            itemMeta = itemMeta?.apply {
                displayName(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.weapons.title")))
                lore(listOf(
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.weapons.description")),
                    Component.empty(),
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.click_to_open"))
                ))
            }
        }
        inventory.setItem(10, weapons)
        
        // 消耗品カテゴリー
        val consumables = ItemStack(Material.GOLDEN_APPLE).apply {
            itemMeta = itemMeta?.apply {
                displayName(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.consumables.title")))
                lore(listOf(
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.consumables.description")),
                    Component.empty(),
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.click_to_open"))
                ))
            }
        }
        inventory.setItem(12, consumables)
        
        // ブロックカテゴリー
        val blocks = ItemStack(Material.STONE).apply {
            itemMeta = itemMeta?.apply {
                displayName(LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.blocks.title")))
                lore(listOf(
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.blocks.description")),
                    Component.empty(),
                    LegacyComponentSerializer.legacySection().deserialize(lang.getMessage("shop.category_menu.click_to_open"))
                ))
            }
        }
        inventory.setItem(14, blocks)
        
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