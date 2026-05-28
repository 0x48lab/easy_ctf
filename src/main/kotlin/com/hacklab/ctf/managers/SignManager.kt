package com.hacklab.ctf.managers

import com.hacklab.ctf.Main
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class SignManager(private val plugin: Main) {

    data class RankingSign(
        val location: Location,
        val category: String,
        val rank: Int
    )

    private val signsFile = File(plugin.dataFolder, "ranking_signs.yml")
    private val rankingSigns = mutableListOf<RankingSign>()

    init {
        loadSigns()
    }

    fun loadSigns() {
        rankingSigns.clear()
        if (!signsFile.exists()) return

        val config = YamlConfiguration.loadConfiguration(signsFile)
        val signsList = config.getConfigurationSection("signs") ?: return

        for (key in signsList.getKeys(false)) {
            try {
                val worldName = config.getString("signs.$key.world") ?: continue
                val world = Bukkit.getWorld(worldName) ?: continue
                val x = config.getInt("signs.$key.x")
                val y = config.getInt("signs.$key.y")
                val z = config.getInt("signs.$key.z")
                val category = config.getString("signs.$key.category") ?: "score"
                val rank = config.getInt("signs.$key.rank", 1)

                rankingSigns.add(RankingSign(Location(world, x.toDouble(), y.toDouble(), z.toDouble()), category, rank))
            } catch (e: Exception) {
                plugin.logger.warning("[SignManager] Failed to load sign $key: ${e.message}")
            }
        }

        plugin.logger.info("[SignManager] Loaded ${rankingSigns.size} ranking signs")
    }

    fun saveSigns() {
        val config = YamlConfiguration()

        rankingSigns.forEachIndexed { index, sign ->
            val path = "signs.$index"
            config.set("$path.world", sign.location.world.name)
            config.set("$path.x", sign.location.blockX)
            config.set("$path.y", sign.location.blockY)
            config.set("$path.z", sign.location.blockZ)
            config.set("$path.category", sign.category)
            config.set("$path.rank", sign.rank)
        }

        config.save(signsFile)
    }

    fun addSign(location: Location, category: String, rank: Int): Boolean {
        // 既存の看板を同じ位置で上書き
        rankingSigns.removeIf {
            it.location.blockX == location.blockX &&
            it.location.blockY == location.blockY &&
            it.location.blockZ == location.blockZ &&
            it.location.world.name == location.world.name
        }

        rankingSigns.add(RankingSign(location, category, rank))
        saveSigns()
        updateSign(RankingSign(location, category, rank))
        return true
    }

    fun removeSign(location: Location): Boolean {
        val removed = rankingSigns.removeIf {
            it.location.blockX == location.blockX &&
            it.location.blockY == location.blockY &&
            it.location.blockZ == location.blockZ &&
            it.location.world.name == location.world.name
        }
        if (removed) saveSigns()
        return removed
    }

    fun updateAllSigns() {
        rankingSigns.forEach { updateSign(it) }
    }

    private fun updateSign(rankingSign: RankingSign) {
        val block = rankingSign.location.block
        if (block.state !is Sign) return

        val sign = block.state as Sign
        val side = sign.getSide(Side.FRONT)

        val categoryName = when (rankingSign.category) {
            "score" -> "スコア"
            "kills" -> "キル数"
            "captures" -> "旗取り"
            "winrate" -> "勝率"
            "kd" -> "K/D"
            else -> rankingSign.category
        }

        // ランキングデータを取得
        val playerData = getPlayerDataForRank(rankingSign.category, rankingSign.rank)

        if (playerData != null) {
            side.line(0, net.kyori.adventure.text.Component.text("§6§l#${rankingSign.rank} $categoryName"))
            side.line(1, net.kyori.adventure.text.Component.text("§f${playerData.first}"))
            side.line(2, net.kyori.adventure.text.Component.text("§e${playerData.second}"))
            side.line(3, net.kyori.adventure.text.Component.text("§7CTF Ranking"))
        } else {
            side.line(0, net.kyori.adventure.text.Component.text("§6§l#${rankingSign.rank} $categoryName"))
            side.line(1, net.kyori.adventure.text.Component.text("§7---"))
            side.line(2, net.kyori.adventure.text.Component.text(""))
            side.line(3, net.kyori.adventure.text.Component.text("§7CTF Ranking"))
        }

        sign.update()
    }

    /**
     * 指定カテゴリ・順位のプレイヤーデータを取得
     * @return Pair<プレイヤー名, スコア表示文字列> or null
     */
    private fun getPlayerDataForRank(category: String, rank: Int): Pair<String, String>? {
        val statsManager = plugin.playerStatisticsManager
        val index = rank - 1

        return when (category) {
            "score", "skill" -> {
                val top = statsManager.getTopPlayersByScore(rank)
                if (top.size > index) {
                    val (uuid, score) = top[index]
                    val name = Bukkit.getOfflinePlayer(uuid).name ?: "Unknown"
                    Pair(name, "Score: ${String.format("%.0f", score)}")
                } else null
            }
            "kills" -> {
                val top = statsManager.getTopPlayersByKills(rank)
                if (top.size > index) {
                    val (uuid, kills) = top[index]
                    val name = Bukkit.getOfflinePlayer(uuid).name ?: "Unknown"
                    Pair(name, "Kills: $kills")
                } else null
            }
            "captures" -> {
                val top = statsManager.getTopPlayersByCaptures(rank)
                if (top.size > index) {
                    val (uuid, captures) = top[index]
                    val name = Bukkit.getOfflinePlayer(uuid).name ?: "Unknown"
                    Pair(name, "Captures: $captures")
                } else null
            }
            "winrate", "wins" -> {
                val top = statsManager.getTopPlayersByWinRate(rank, 3)
                if (top.size > index) {
                    val (uuid, rate) = top[index]
                    val name = Bukkit.getOfflinePlayer(uuid).name ?: "Unknown"
                    Pair(name, "Win: ${String.format("%.0f", rate)}%")
                } else null
            }
            "kd" -> {
                val allStats = statsManager.getAllPlayerStats()
                val sorted = allStats.entries
                    .filter { it.value.gamesPlayed >= 3 }
                    .sortedByDescending { it.value.getKDRatio() }
                if (sorted.size > index) {
                    val entry = sorted[index]
                    val name = Bukkit.getOfflinePlayer(entry.key).name ?: "Unknown"
                    Pair(name, "K/D: ${String.format("%.2f", entry.value.getKDRatio())}")
                } else null
            }
            else -> null
        }
    }
}
