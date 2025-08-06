package com.hacklab.ctf.utils

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ChatColor

enum class Team(val displayName: String, val color: NamedTextColor, val colorCode: String) {
    RED("赤チーム", NamedTextColor.RED, "&c"),
    BLUE("青チーム", NamedTextColor.BLUE, "&9"),
    SPECTATOR("観戦者", NamedTextColor.GRAY, "&7");
    
    fun getChatColor(): String {
        return colorCode // &形式のカラーコードを返す
    }
    
    fun getColoredName(): String {
        return getChatColor() + displayName
    }
}