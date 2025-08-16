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
import org.bukkit.event.inventory.InventoryClickEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import kotlin.math.roundToInt
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.enchantments.Enchantment
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

class ShopManager(private val plugin: Main) {
    private val shopItems = mutableListOf<ShopItem>()
    private var shopConfig: YamlConfiguration? = null
    val playerPurchases = mutableMapOf<UUID, MutableMap<String, Int>>()
    val teamPurchases = mutableMapOf<String, MutableMap<String, Int>>()
    
    init {
        loadShopConfig()
        loadShopItems()
    }
    
    private fun loadShopConfig() {
        val configFile = File(plugin.dataFolder, "shop.yml")
        
        // ファイルが存在しない場合はリソースからコピー
        if (!configFile.exists()) {
            plugin.saveResource("shop.yml", false)
        }
        
        shopConfig = YamlConfiguration.loadConfiguration(configFile)
    }
    
    /**
     * 言語キーまたは直接テキストを処理する
     * "lang:"で始まる場合は言語ファイルから取得、それ以外は直接返す
     */
    private fun processLangKey(value: String): String {
        return if (value.startsWith("lang:")) {
            val key = value.substring(5) // "lang:"を除去
            plugin.languageManager.getMessage(key)
        } else {
            value
        }
    }
    
    private fun loadShopItems() {
        shopItems.clear()
        val config = shopConfig ?: return
        
        val itemsSection = config.getConfigurationSection("items") ?: return
        
        for (itemId in itemsSection.getKeys(false)) {
            val itemConfig = itemsSection.getConfigurationSection(itemId) ?: continue
            
            try {
                // 必須項目
                val materialName = itemConfig.getString("material") ?: continue
                val material = Material.getMaterial(materialName) ?: continue
                val price = itemConfig.getInt("price", 10)
                
                // オプション項目
                val amount = itemConfig.getInt("amount", 1)
                val categoryName = itemConfig.getString("category", "BLOCKS")
                val category = try {
                    ShopCategory.valueOf(categoryName ?: "BLOCKS")
                } catch (e: IllegalArgumentException) {
                    ShopCategory.BLOCKS
                }
                
                val deathBehaviorName = itemConfig.getString("death-behavior", "KEEP")
                val deathBehavior = try {
                    DeathBehavior.valueOf(deathBehaviorName ?: "KEEP")
                } catch (e: IllegalArgumentException) {
                    DeathBehavior.KEEP
                }
                
                // 表示名の処理
                val displayNameRaw = itemConfig.getString("display-name", itemId)
                val displayName = processLangKey(displayNameRaw ?: itemId)
                
                // 説明文の処理
                val loreRaw = itemConfig.getStringList("lore")
                val lore = loreRaw.map { processLangKey(it) }
                
                // エンチャントの処理
                val enchantments = mutableMapOf<Enchantment, Int>()
                val enchantSection = itemConfig.getConfigurationSection("enchantments")
                if (enchantSection != null) {
                    for (enchantName in enchantSection.getKeys(false)) {
                        val enchantment = Enchantment.getByName(enchantName)
                        if (enchantment != null) {
                            val level = enchantSection.getInt(enchantName, 1)
                            enchantments[enchantment] = level
                        }
                    }
                }
                
                // 購入制限
                val maxPerPlayer = itemConfig.getInt("max-purchase-per-player", -1)
                val maxPerTeam = itemConfig.getInt("max-purchase-per-team", -1)
                
                // 利用可能フェーズ
                val phasesList = itemConfig.getStringList("available-phases")
                val availablePhases = if (phasesList.isEmpty()) {
                    setOf(GamePhase.BUILD, GamePhase.COMBAT)
                } else {
                    phasesList.mapNotNull { phaseName ->
                        try {
                            GamePhase.valueOf(phaseName)
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    }.toSet()
                }
                
                // 耐久無限
                val unbreakable = itemConfig.getBoolean("unbreakable", false)
                
                // ShopItemを作成して追加
                val shopItem = ShopItem(
                    id = itemId,
                    displayName = displayName,
                    material = material,
                    amount = amount,
                    basePrice = price,
                    category = category,
                    deathBehavior = deathBehavior,
                    lore = lore,
                    enchantments = enchantments,
                    unbreakable = unbreakable,
                    maxPurchasePerPlayer = maxPerPlayer,
                    maxPurchasePerTeam = maxPerTeam,
                    availablePhases = availablePhases
                )
                
                shopItems.add(shopItem)
                
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load shop item '$itemId': ${e.message}")
            }
        }
        
        plugin.logger.info("Loaded ${shopItems.size} shop items from shop.yml")
    }
    
    /**
     * 設定ファイルをリロード
     */
    fun reload() {
        loadShopConfig()
        loadShopItems()
        playerPurchases.clear()
        teamPurchases.clear()
    }

    
    fun isShopItem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EMERALD) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(
            NamespacedKey(plugin, "is_shop_item"),
            PersistentDataType.BYTE
        )
    }
    
    fun createShopItem(): ItemStack {
        val emerald = ItemStack(Material.EMERALD)
        val meta = emerald.itemMeta
        meta.displayName(plugin.languageManager.getMessageAsComponent("shop.item.emerald.name"))
        
        val lore = mutableListOf<Component>()
        lore.add(plugin.languageManager.getMessageAsComponent("shop.item.emerald.lore1"))
        lore.add(plugin.languageManager.getMessageAsComponent("shop.item.emerald.lore2"))
        meta.lore(lore)
        
        // NBTタグを設定してショップアイテムであることを識別
        meta.persistentDataContainer.set(
            NamespacedKey(plugin, "is_shop_item"),
            PersistentDataType.BYTE,
            1
        )
        
        emerald.itemMeta = meta
        return emerald
    }
    
    fun getShopItems(phase: GamePhase? = null, category: ShopCategory? = null): List<ShopItem> {
        return shopItems.filter { item ->
            (phase == null || item.availablePhases.contains(phase)) &&
            (category == null || item.category == category)
        }
    }
    
    fun getShopItem(id: String): ShopItem? {
        return shopItems.find { it.id == id }
    }
    
    fun canPurchase(player: Player, item: ShopItem, game: Game): Pair<Boolean, String> {
        val team = game.getPlayerTeam(player.uniqueId) ?: return false to "Team not found"
        
        // フェーズチェック
        if (!item.availablePhases.contains(game.phase)) {
            return false to plugin.languageManager.getMessage("shop.phase-restricted")
        }
        
        // 通貨チェック
        val teamCurrency = if (game.matchWrapper != null) {
            game.matchWrapper!!.getTeamCurrency(team)
        } else {
            game.getTeamCurrency(team)
        }
        
        val discountRate = game.getDiscountRate(team)
        val finalPrice = (item.basePrice * (1.0 - discountRate)).toInt()
        
        if (teamCurrency < finalPrice) {
            return false to plugin.languageManager.getMessage("shop.insufficient-funds")
        }
        
        // プレイヤー購入制限チェック
        if (item.maxPurchasePerPlayer > 0) {
            val playerPurchaseCount = getPlayerPurchaseCount(player.uniqueId, item.id)
            if (playerPurchaseCount >= item.maxPurchasePerPlayer) {
                return false to plugin.languageManager.getMessage("shop.purchase-limit")
            }
        }
        
        // チーム購入制限チェック
        if (item.maxPurchasePerTeam > 0) {
            val teamPurchaseCount = getTeamPurchaseCount(game.gameName, team, item.id)
            if (teamPurchaseCount >= item.maxPurchasePerTeam) {
                return false to plugin.languageManager.getMessage("shop.purchase-limit")
            }
        }
        
        return true to "OK"
    }
    
    fun purchaseItem(player: Player, item: ShopItem, game: Game): Boolean {
        val (canPurchase, _) = canPurchase(player, item, game)
        if (!canPurchase) return false
        
        val team = game.getPlayerTeam(player.uniqueId) ?: return false
        val discountRate = game.getDiscountRate(team)
        val finalPrice = (item.basePrice * (1.0 - discountRate)).toInt()
        
        // 通貨を減らす
        if (game.matchWrapper != null) {
            game.matchWrapper!!.removeTeamCurrency(team, finalPrice)
        } else {
            game.removeTeamCurrency(team, finalPrice)
        }
        
        // アイテムを与える
        val itemStack = item.createItemStack()
        
        // チームカラーブロックの場合は色を変更
        if (item.category == ShopCategory.BLOCKS) {
            when (item.material) {
                Material.RED_CONCRETE, Material.BLUE_CONCRETE -> {
                    val teamMaterial = when (team) {
                        Team.RED -> Material.RED_CONCRETE
                        Team.BLUE -> Material.BLUE_CONCRETE
                        else -> item.material
                    }
                    itemStack.type = teamMaterial
                }
                Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS -> {
                    val teamMaterial = when (team) {
                        Team.RED -> Material.RED_STAINED_GLASS
                        Team.BLUE -> Material.BLUE_STAINED_GLASS
                        else -> item.material
                    }
                    itemStack.type = teamMaterial
                }
                else -> {}
            }
        }
        
        player.inventory.addItem(itemStack)
        
        // 購入記録
        recordPurchase(player.uniqueId, game.gameName, team, item.id)
        
        // 通知
        player.sendMessage(plugin.languageManager.getMessageAsComponent("shop.purchase-success"))
        
        // チーム全体に通知
        val notificationMessage = plugin.languageManager.getMessage(
            "currency.purchase-notification",
            "player" to player.name,
            "item" to item.displayName
        )
        
        val allPlayers = when (team) {
            Team.RED -> game.redTeam
            Team.BLUE -> game.blueTeam
            else -> emptySet()
        }
        
        allPlayers.forEach { playerId ->
            if (playerId != player.uniqueId) {
                Bukkit.getPlayer(playerId)?.sendMessage(notificationMessage)
            }
        }
        
        return true
    }
    
    private fun recordPurchase(playerId: UUID, gameName: String, team: Team, itemId: String) {
        // プレイヤー別記録
        val playerMap = playerPurchases.getOrPut(playerId) { mutableMapOf() }
        playerMap[itemId] = playerMap.getOrDefault(itemId, 0) + 1
        
        // チーム別記録
        val teamKey = "$gameName:${team.name}"
        val teamMap = teamPurchases.getOrPut(teamKey) { mutableMapOf() }
        teamMap[itemId] = teamMap.getOrDefault(itemId, 0) + 1
    }
    
    fun getPlayerPurchaseCount(playerId: UUID, itemId: String): Int {
        return playerPurchases[playerId]?.get(itemId) ?: 0
    }
    
    fun getTeamPurchaseCount(gameName: String, team: Team, itemId: String): Int {
        val teamKey = "$gameName:${team.name}"
        return teamPurchases[teamKey]?.get(itemId) ?: 0
    }
    
    fun clearPurchaseHistory(gameName: String) {
        // プレイヤー購入履歴はそのまま（プレイヤー制限用）
        // チーム購入履歴のみクリア
        val keysToRemove = teamPurchases.keys.filter { it.startsWith("$gameName:") }
        keysToRemove.forEach { teamPurchases.remove(it) }
    }
    
    fun resetPlayerPurchases(playerId: UUID) {
        playerPurchases.remove(playerId)
    }
    
    fun openShop(player: Player, game: Game) {
        openCategoryMenu(player, game)
    }
    
    private fun openCategoryMenu(player: Player, game: Game) {
        val team = game.getPlayerTeam(player.uniqueId) ?: return
        val teamCurrency = if (game.matchWrapper != null) {
            game.matchWrapper!!.getTeamCurrency(team)
        } else {
            game.getTeamCurrency(team)
        }
        
        val inventory = Bukkit.createInventory(
            null,
            27,
            plugin.languageManager.getMessageAsComponent("shop.category_menu.title")
        )
        
        // カテゴリーアイコンを設定
        val categories = listOf(
            Triple(11, ShopCategory.WEAPONS, Material.DIAMOND_SWORD),
            Triple(13, ShopCategory.CONSUMABLES, Material.GOLDEN_APPLE),
            Triple(15, ShopCategory.BLOCKS, Material.STONE)
        )
        
        for ((slot, category, iconMaterial) in categories) {
            val icon = ItemStack(iconMaterial)
            val meta = icon.itemMeta
            
            // カテゴリー設定から表示名と説明を取得
            val categoryConfig = shopConfig?.getConfigurationSection("categories.${category.name}")
            val displayName = if (categoryConfig != null) {
                processLangKey(categoryConfig.getString("display-name") ?: category.name)
            } else {
                plugin.languageManager.getMessage("shop.categories.${category.name.lowercase()}")
            }
            
            val description = if (categoryConfig != null) {
                processLangKey(categoryConfig.getString("description") ?: "")
            } else {
                plugin.languageManager.getMessage("shop.category_descriptions.${category.name.lowercase()}")
            }
            
            meta.displayName(
                Component.text(displayName)
                    .color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.BOLD, true)
            )
            
            val loreList = mutableListOf<Component>()
            loreList.add(Component.text(description).color(NamedTextColor.GRAY))
            loreList.add(Component.empty())
            loreList.add(Component.text(plugin.languageManager.getMessage("shop.category_menu.click_to_open"))
                .color(NamedTextColor.GREEN))
            
            meta.lore(loreList)
            icon.itemMeta = meta
            
            inventory.setItem(slot, icon)
        }
        
        player.openInventory(inventory)
    }
    
    fun openCategoryShop(player: Player, game: Game, category: ShopCategory, page: Int = 0) {
        val team = game.getPlayerTeam(player.uniqueId) ?: return
        val teamCurrency = if (game.matchWrapper != null) {
            game.matchWrapper!!.getTeamCurrency(team)
        } else {
            game.getTeamCurrency(team)
        }
        
        val items = getShopItems(game.phase, category)
        val itemsPerPage = 45
        val totalPages = (items.size + itemsPerPage - 1) / itemsPerPage
        val currentPage = page.coerceIn(0, totalPages - 1)
        
        val inventory = Bukkit.createInventory(
            null,
            54,
            plugin.languageManager.getMessageAsComponent("shop.title", 
                "currency" to teamCurrency.toString(),
                "page" to (currentPage + 1).toString()
            )
        )
        
        // アイテム表示
        val startIndex = currentPage * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, items.size)
        val pageItems = items.subList(startIndex, endIndex)
        
        for ((index, shopItem) in pageItems.withIndex()) {
            val displayItem = createDisplayItem(shopItem, player, game)
            inventory.setItem(index, displayItem)
        }
        
        // ナビゲーション
        if (currentPage > 0) {
            val prevPage = ItemStack(Material.ARROW)
            val prevMeta = prevPage.itemMeta
            prevMeta.displayName(plugin.languageManager.getMessageAsComponent("shop.navigation.previous_page"))
            prevPage.itemMeta = prevMeta
            inventory.setItem(48, prevPage)
        }
        
        val backButton = ItemStack(Material.BARRIER)
        val backMeta = backButton.itemMeta
        backMeta.displayName(plugin.languageManager.getMessageAsComponent("shop.navigation.main_menu"))
        backButton.itemMeta = backMeta
        inventory.setItem(49, backButton)
        
        if (currentPage < totalPages - 1) {
            val nextPage = ItemStack(Material.ARROW)
            val nextMeta = nextPage.itemMeta
            nextMeta.displayName(plugin.languageManager.getMessageAsComponent("shop.navigation.next_page"))
            nextPage.itemMeta = nextMeta
            inventory.setItem(50, nextPage)
        }
        
        player.openInventory(inventory)
    }
    
    private fun createDisplayItem(shopItem: ShopItem, player: Player, game: Game): ItemStack {
        val team = game.getPlayerTeam(player.uniqueId) ?: return ItemStack(Material.BARRIER)
        val teamCurrency = if (game.matchWrapper != null) {
            game.matchWrapper!!.getTeamCurrency(team)
        } else {
            game.getTeamCurrency(team)
        }
        
        val discountRate = game.getDiscountRate(team)
        val finalPrice = (shopItem.basePrice * (1.0 - discountRate)).toInt()
        
        val displayItem = shopItem.createItemStack()
        val meta = displayItem.itemMeta
        
        val lore = mutableListOf<Component>()
        
        // 既存のlore
        if (shopItem.lore.isNotEmpty()) {
            shopItem.lore.forEach { loreText ->
                lore.add(Component.text(loreText).color(NamedTextColor.GRAY))
            }
            lore.add(Component.empty())
        }
        
        // 価格表示
        lore.add(plugin.languageManager.getMessageAsComponent("shop.item_info.price", "price" to finalPrice.toString()))
        
        // 割引表示
        if (discountRate > 0) {
            val originalPrice = shopItem.basePrice
            val discountPercent = (discountRate * 100).toInt()
            // Adventure APIのLegacyComponentSerializerを使用してレガシーカラーコードを処理
            val discountText = "&m${originalPrice}&r &c-${discountPercent}%"
            lore.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(discountText))
        }
        
        // 死亡時の挙動
        val behaviorKey = when (shopItem.deathBehavior) {
            DeathBehavior.KEEP -> "shop.item_info.death_behavior_types.keep"
            DeathBehavior.DROP -> "shop.item_info.death_behavior_types.drop"
            DeathBehavior.DESTROY -> "shop.item_info.death_behavior_types.destroy"
        }
        lore.add(plugin.languageManager.getMessageAsComponent("shop.item_info.death_behavior",
            "behavior" to plugin.languageManager.getMessage(behaviorKey)))
        
        // 購入制限表示
        if (shopItem.maxPurchasePerPlayer > 0) {
            val current = getPlayerPurchaseCount(player.uniqueId, shopItem.id)
            lore.add(plugin.languageManager.getMessageAsComponent("shop.item_info.personal_limit",
                "current" to current.toString(),
                "max" to shopItem.maxPurchasePerPlayer.toString()))
        }
        
        if (shopItem.maxPurchasePerTeam > 0) {
            val current = getTeamPurchaseCount(game.gameName, team, shopItem.id)
            lore.add(plugin.languageManager.getMessageAsComponent("shop.item_info.team_limit",
                "current" to current.toString(),
                "max" to shopItem.maxPurchasePerTeam.toString()))
        }
        
        // 購入可否
        val (canPurchase, reason) = canPurchase(player, shopItem, game)
        if (canPurchase) {
            lore.add(Component.text("✔ " + plugin.languageManager.getMessage("shop.item_info.can_purchase"))
                .color(NamedTextColor.GREEN))
        } else {
            when (reason) {
                plugin.languageManager.getMessage("shop.insufficient-funds") ->
                    lore.add(Component.text("✗ " + plugin.languageManager.getMessage("shop.item_info.insufficient_funds"))
                        .color(NamedTextColor.RED))
                plugin.languageManager.getMessage("shop.purchase-limit") ->
                    lore.add(Component.text("✗ " + plugin.languageManager.getMessage("shop.item_info.purchase_limit_reached"))
                        .color(NamedTextColor.RED))
                else ->
                    lore.add(Component.text("✗ $reason").color(NamedTextColor.RED))
            }
        }
        
        meta.lore(lore)
        displayItem.itemMeta = meta
        
        return displayItem
    }
    
    fun handleInventoryClick(event: InventoryClickEvent, game: Game) {
        val player = event.whoClicked as? Player ?: return
        val clickedItem = event.currentItem ?: return
        
        event.isCancelled = true
        
        val title = event.view.title()
        val titleText = (title as? net.kyori.adventure.text.TextComponent)?.content() ?: ""
        
        when {
            titleText.contains(plugin.languageManager.getMessage("shop.category_menu.title")) -> {
                // カテゴリー選択メニュー
                when (clickedItem.type) {
                    Material.DIAMOND_SWORD -> openCategoryShop(player, game, ShopCategory.WEAPONS)
                    Material.GOLDEN_APPLE -> openCategoryShop(player, game, ShopCategory.CONSUMABLES)
                    Material.STONE -> openCategoryShop(player, game, ShopCategory.BLOCKS)
                    else -> {}
                }
            }
            titleText.contains(plugin.languageManager.getMessage("shop.title").split("{")[0]) -> {
                // ショップメニュー
                val slot = event.slot
                
                // ページ切り替えボタンの判定（スロット位置で判断）
                if (slot == 48 && clickedItem.type == Material.ARROW) {
                    // 前のページ（左下のボタン）
                    val currentPage = getCurrentPage(titleText)
                    val category = getCurrentCategory(event.inventory)
                    openCategoryShop(player, game, category, currentPage - 1)
                } else if (slot == 50 && clickedItem.type == Material.ARROW) {
                    // 次のページ（右下のボタン）
                    val currentPage = getCurrentPage(titleText)
                    val category = getCurrentCategory(event.inventory)
                    openCategoryShop(player, game, category, currentPage + 1)
                } else if (clickedItem.type == Material.BARRIER) {
                    // メインメニューに戻る
                    openCategoryMenu(player, game)
                } else {
                    // アイテム購入処理
                    val shopItem = findShopItemByDisplayItem(clickedItem)
                    if (shopItem != null) {
                        if (purchaseItem(player, shopItem, game)) {
                            // 購入成功後、インベントリを更新
                            val currentPage = getCurrentPage(titleText)
                            val category = getCurrentCategory(event.inventory)
                            openCategoryShop(player, game, category, currentPage)
                        }
                    }
                }
            }
        }
    }
    
    private fun getCurrentPage(titleText: String): Int {
        val match = Regex("\\((\\d+)\\)").find(titleText)
        return (match?.groupValues?.get(1)?.toIntOrNull() ?: 1) - 1
    }
    
    private fun getCurrentCategory(inventory: Inventory): ShopCategory {
        // インベントリ内のアイテムから現在のカテゴリーを推測
        for (i in 0 until minOf(45, inventory.size)) {
            val item = inventory.getItem(i) ?: continue
            val shopItem = findShopItemByDisplayItem(item)
            if (shopItem != null) {
                return shopItem.category
            }
        }
        return ShopCategory.BLOCKS
    }
    
    private fun findShopItemByDisplayItem(item: ItemStack): ShopItem? {
        // NBTタグからショップアイテムIDを取得
        val meta = item.itemMeta ?: return null
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("EasyCTF") ?: return null
        val itemId = meta.persistentDataContainer.get(
            NamespacedKey(plugin, "shop_item"),
            PersistentDataType.STRING
        ) ?: return null
        
        return getShopItem(itemId)
    }
}