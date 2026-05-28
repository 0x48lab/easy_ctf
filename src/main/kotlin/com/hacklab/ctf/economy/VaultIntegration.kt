package com.hacklab.ctf.economy

import com.hacklab.ctf.Main
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class VaultIntegration(private val plugin: Main) {

    private var economy: Economy? = null

    fun setup(): Boolean {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.logger.warning("[EasyCTF] Vault not found. Economy rewards will be disabled.")
            return false
        }

        val rsp = Bukkit.getServicesManager().getRegistration(Economy::class.java)
        if (rsp == null) {
            plugin.logger.warning("[EasyCTF] No economy provider found. Economy rewards will be disabled.")
            return false
        }

        economy = rsp.provider
        plugin.logger.info("[EasyCTF] Vault economy connected: ${economy?.name}")
        return true
    }

    fun deposit(player: Player, amount: Double): Boolean {
        val econ = economy ?: return false
        if (amount <= 0) return true

        val response = econ.depositPlayer(player, amount)
        return response.transactionSuccess()
    }

    fun isAvailable(): Boolean = economy != null
}
