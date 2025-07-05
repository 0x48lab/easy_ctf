package com.hacklab.ctf.utils

import net.kyori.adventure.text.format.NamedTextColor

enum class Team(val displayName: String, val color: NamedTextColor) {
    RED("赤チーム", NamedTextColor.RED),
    BLUE("青チーム", NamedTextColor.BLUE)
}