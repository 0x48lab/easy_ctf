package com.hacklab.ctf.utils

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor

enum class Team(val displayName: String, val color: NamedTextColor) {
    RED("赤チーム", NamedTextColor.RED),
    BLUE("青チーム", NamedTextColor.BLUE);
    
    fun getChatColor(): String {
        return when (this) {
            RED -> ChatColor.RED.toString()
            BLUE -> ChatColor.BLUE.toString()
        }
    }
    
    fun getColoredName(): String {
        return getChatColor() + displayName
    }
}