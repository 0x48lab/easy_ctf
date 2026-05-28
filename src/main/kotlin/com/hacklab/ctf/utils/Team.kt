package com.hacklab.ctf.utils

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Material

enum class Team(val displayName: String, val color: NamedTextColor, val colorCode: String) {
    RED("赤チーム", NamedTextColor.RED, "&c"),
    BLUE("青チーム", NamedTextColor.BLUE, "&9"),
    GREEN("緑チーム", NamedTextColor.GREEN, "&a"),
    YELLOW("黄チーム", NamedTextColor.YELLOW, "&e"),
    SPECTATOR("観戦者", NamedTextColor.GRAY, "&7");

    fun getChatColor(): String {
        return colorCode
    }

    fun getColoredName(): String {
        return getChatColor() + displayName
    }

    /** チームに対応するコンクリートブロック */
    fun getConcreteMaterial(): Material {
        return when (this) {
            RED -> Material.RED_CONCRETE
            BLUE -> Material.BLUE_CONCRETE
            GREEN -> Material.GREEN_CONCRETE
            YELLOW -> Material.YELLOW_CONCRETE
            SPECTATOR -> Material.WHITE_CONCRETE
        }
    }

    /** チームに対応するステンドグラス */
    fun getStainedGlassMaterial(): Material {
        return when (this) {
            RED -> Material.RED_STAINED_GLASS
            BLUE -> Material.BLUE_STAINED_GLASS
            GREEN -> Material.GREEN_STAINED_GLASS
            YELLOW -> Material.YELLOW_STAINED_GLASS
            SPECTATOR -> Material.WHITE_STAINED_GLASS
        }
    }

    /** チームに対応するバナー */
    fun getBannerMaterial(): Material {
        return when (this) {
            RED -> Material.RED_BANNER
            BLUE -> Material.BLUE_BANNER
            GREEN -> Material.GREEN_BANNER
            YELLOW -> Material.YELLOW_BANNER
            SPECTATOR -> Material.WHITE_BANNER
        }
    }

    /** チームに対応する革防具の色 */
    fun getArmorColor(): Color {
        return when (this) {
            RED -> Color.RED
            BLUE -> Color.BLUE
            GREEN -> Color.GREEN
            YELLOW -> Color.YELLOW
            SPECTATOR -> Color.WHITE
        }
    }

    companion object {
        /** プレイ可能なチーム一覧（SPECTATORを除く） */
        fun playableTeams(): List<Team> = entries.filter { it != SPECTATOR }

        /** チーム数に応じた有効チーム一覧を取得 */
        fun getActiveTeams(teamCount: Int): List<Team> {
            return playableTeams().take(teamCount.coerceIn(2, 4))
        }
    }
}
