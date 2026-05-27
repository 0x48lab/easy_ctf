package com.hacklab.ctf.managers

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import net.kyori.adventure.text.Component
import org.bukkit.NamespacedKey
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * チーム通貨・割引率・ショップアイテム管理。
 */
class CurrencyManager(
    private val game: Game,
    private val plugin: Main
) {

    fun getTeamCurrency(team: Team): Int {
        return game.matchWrapper?.getTeamCurrency(team) ?: game.teamCurrency[team] ?: 0
    }

    fun removeTeamCurrency(team: Team, amount: Int) {
        if (game.matchWrapper != null) {
            game.matchWrapper!!.removeTeamCurrency(team, amount)
        } else {
            val current = game.teamCurrency[team] ?: 0
            game.teamCurrency[team] = maxOf(0, current - amount)
        }
    }

    fun getDiscountRate(team: Team): Double {
        val redScore = game.score[Team.RED] ?: 0
        val blueScore = game.score[Team.BLUE] ?: 0
        val difference = when (team) {
            Team.RED -> blueScore - redScore
            Team.BLUE -> redScore - blueScore
            else -> 0
        }

        return when {
            difference >= 4 -> plugin.config.getDouble("shop.discount.4-point-plus", 0.4)
            difference == 3 -> plugin.config.getDouble("shop.discount.3-point", 0.3)
            difference == 2 -> plugin.config.getDouble("shop.discount.2-point", 0.2)
            difference == 1 -> plugin.config.getDouble("shop.discount.1-point", 0.1)
            else -> 0.0
        }
    }

    fun createShopItem(): ItemStack {
        val emerald = ItemStack(Material.EMERALD)
        val meta = emerald.itemMeta
        meta.displayName(plugin.languageManager.getMessageAsComponent("shop.item.emerald.name"))

        val lore = mutableListOf<Component>()
        lore.add(plugin.languageManager.getMessageAsComponent("shop.item.emerald.lore1"))
        lore.add(plugin.languageManager.getMessageAsComponent("shop.item.emerald.lore2"))
        meta.lore(lore)

        meta.persistentDataContainer.set(
            NamespacedKey(plugin, "is_shop_item"),
            PersistentDataType.BYTE,
            1
        )

        emerald.itemMeta = meta
        return emerald
    }

    fun isShopItem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.EMERALD) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(
            NamespacedKey(plugin, "is_shop_item"),
            PersistentDataType.BYTE
        )
    }

    fun resetGamePurchases() {
        plugin.shopManager.clearPurchaseHistory(game.gameName)
    }

    fun addTeamCurrency(team: Team, amount: Int, reason: String = "") {
        if (game.matchWrapper != null) {
            game.matchWrapper!!.addTeamCurrency(team, amount)
        } else {
            val current = game.teamCurrency[team] ?: 0
            game.teamCurrency[team] = current + amount
        }

        if (reason.isNotEmpty()) {
            game.getTeamPlayers(team).forEach { player ->
                player.sendMessage(plugin.languageManager.getMessageAsComponent("currency.team-notification",
                    "reason" to reason,
                    "amount" to amount.toString()
                ))
            }
        }

        game.updateScoreboard()
    }

    fun spendTeamCurrency(team: Team, amount: Int, player: Player, itemName: String): Boolean {
        if (game.matchWrapper != null) {
            return game.matchWrapper!!.useTeamCurrency(team, amount)
        } else {
            val current = game.teamCurrency[team] ?: 0
            if (current < amount) return false

            game.teamCurrency[team] = current - amount

            game.getTeamPlayers(team).forEach { p ->
                p.sendMessage(plugin.languageManager.getMessageAsComponent("currency.purchase-notification",
                    "player" to player.name,
                    "item" to itemName,
                    "amount" to amount.toString()
                ))
                p.sendMessage(plugin.languageManager.getMessageAsComponent("currency.balance-notification",
                    "balance" to game.teamCurrency[team].toString()
                ))
            }

            return true
        }
    }

    fun initializeCurrency() {
        val initialAmount = plugin.config.getInt("currency.initial", 50)
        game.teamCurrency[Team.RED] = initialAmount
        game.teamCurrency[Team.BLUE] = initialAmount
    }
}
