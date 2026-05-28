package com.hacklab.ctf.map

import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block

/**
 * マップ内の特定ブロックを検出するクラス
 * RED/BLUE/GREEN/YELLOW の4チームのスポーン地点と旗を自動検出する
 */
class MapScanner(private val plugin: Main) {

    data class ScanResult(
        val spawns: Map<Team, List<Location>>,
        val flags: Map<Team, List<Location>>,
        val errors: List<String>
    ) {
        fun isValid(): Boolean = errors.isEmpty()

        // --- 後方互換プロパティ ---
        val redSpawns: List<Location> get() = spawns[Team.RED] ?: emptyList()
        val blueSpawns: List<Location> get() = spawns[Team.BLUE] ?: emptyList()
        val greenSpawns: List<Location> get() = spawns[Team.GREEN] ?: emptyList()
        val yellowSpawns: List<Location> get() = spawns[Team.YELLOW] ?: emptyList()

        val redFlags: List<Location> get() = flags[Team.RED] ?: emptyList()
        val blueFlags: List<Location> get() = flags[Team.BLUE] ?: emptyList()
        val greenFlags: List<Location> get() = flags[Team.GREEN] ?: emptyList()
        val yellowFlags: List<Location> get() = flags[Team.YELLOW] ?: emptyList()

        /** 指定チームのスポーン地点を取得 */
        fun getSpawnsForTeam(team: Team): List<Location> = spawns[team] ?: emptyList()

        /** 指定チームの旗位置を取得 */
        fun getFlagsForTeam(team: Team): List<Location> = flags[team] ?: emptyList()
    }

    /** コンクリートブロック → チーム のマッピング */
    private val concreteToTeam: Map<Material, Team> = mapOf(
        Material.RED_CONCRETE to Team.RED,
        Material.BLUE_CONCRETE to Team.BLUE,
        Material.GREEN_CONCRETE to Team.GREEN,
        Material.YELLOW_CONCRETE to Team.YELLOW
    )

    /**
     * マップ領域をスキャンして特定のブロックを検出
     */
    fun scan(region: MapRegion): ScanResult {
        val spawns = mutableMapOf<Team, MutableList<Location>>()
        val flags = mutableMapOf<Team, MutableList<Location>>()
        val errors = mutableListOf<String>()

        // 一時リストをクリア
        tempBeacons.clear()

        // すべてのブロックをスキャン
        var scannedBlocks = 0
        for (x in region.minX..region.maxX) {
            for (y in region.minY..region.maxY) {
                for (z in region.minZ..region.maxZ) {
                    val block = region.world.getBlockAt(x, y, z)
                    val location = block.location
                    scannedBlocks++

                    val team = concreteToTeam[block.type]
                    if (team != null) {
                        // チームコンクリート発見 → スポーン地点
                        spawns.getOrPut(team) { mutableListOf() }
                            .add(location.clone().add(0.5, 1.0, 0.5))
                    } else if (block.type == Material.BEACON) {
                        // ビーコン発見
                        tempBeacons.add(block.location.clone().add(0.5, 0.0, 0.5))
                    }
                }
            }
        }

        // ビーコンをスポーン地点との距離で分類
        assignBeaconsToTeams(spawns, flags, errors)

        // 検証
        validateScanResult(spawns, flags, errors)

        // immutable に変換
        val immutableSpawns = spawns.mapValues { it.value.toList() }
        val immutableFlags = flags.mapValues { it.value.toList() }

        return ScanResult(immutableSpawns, immutableFlags, errors)
    }

    /**
     * ビーコンの旗位置を一時的に保存
     */
    private val tempBeacons = mutableListOf<Location>()

    /**
     * ビーコンをスポーン地点との距離で各チームに割り当て
     */
    private fun assignBeaconsToTeams(
        spawns: Map<Team, MutableList<Location>>,
        flags: MutableMap<Team, MutableList<Location>>,
        errors: MutableList<String>
    ) {
        // スポーン地点があるチームだけを対象にする
        val teamsWithSpawns = spawns.filter { it.value.isNotEmpty() }
        if (teamsWithSpawns.isEmpty()) {
            return
        }

        // 各ビーコンについて、最も近いスポーン地点のチームに割り当てる
        for (beacon in tempBeacons) {
            // 各チームへの最小距離を計算
            val distances = teamsWithSpawns.mapValues { (_, spawnList) ->
                spawnList.minOf { it.distance(beacon) }
            }

            // 最小距離のチームを取得
            val minDistance = distances.minBy { it.value }
            val closestTeams = distances.filter { it.value == minDistance.value }

            if (closestTeams.size == 1) {
                val team = closestTeams.keys.first()
                flags.getOrPut(team) { mutableListOf() }.add(beacon)
                plugin.logger.info("ビーコン at ${beacon.blockX}, ${beacon.blockY}, ${beacon.blockZ} -> ${team.name} (距離: ${String.format("%.1f", minDistance.value)})")
            } else {
                // 複数チームから等距離の場合はエラー
                val teamNames = closestTeams.keys.joinToString(", ") { it.name }
                errors.add("ビーコン at ${beacon.blockX}, ${beacon.blockY}, ${beacon.blockZ} は複数チーム(${teamNames})のスポーンから等距離です")
            }
        }
    }

    /**
     * スキャン結果を検証
     * 注意: RED/BLUEは必須、GREEN/YELLOWはオプション（存在する場合のみ検証）
     */
    private fun validateScanResult(
        spawns: Map<Team, MutableList<Location>>,
        flags: Map<Team, MutableList<Location>>,
        errors: MutableList<String>
    ) {
        // 必須チーム（RED/BLUE）のスポーン地点検証
        if (spawns[Team.RED].isNullOrEmpty()) {
            errors.add(plugin.languageManager.getMessage("scanner.red-spawn-not-found"))
        }
        if (spawns[Team.BLUE].isNullOrEmpty()) {
            errors.add(plugin.languageManager.getMessage("scanner.blue-spawn-not-found"))
        }

        // スポーン地点が存在するすべてのチームについて旗を検証
        val activeTeams = spawns.filter { it.value.isNotEmpty() }.keys

        for (team in activeTeams) {
            val teamFlags = flags[team] ?: emptyList()
            val teamName = team.name.lowercase()

            when {
                teamFlags.isEmpty() -> {
                    errors.add(plugin.languageManager.getMessage("scanner.${teamName}-flag-not-found"))
                }
                teamFlags.size > 1 -> {
                    errors.add(plugin.languageManager.getMessage("scanner.${teamName}-flag-multiple", "count" to teamFlags.size.toString()))
                }
            }
        }

        // スポーン地点同士の距離検証（同一チーム内）
        val minSpawnDistance = 4.0

        for ((team, teamSpawns) in spawns) {
            if (teamSpawns.size > 1) {
                for (i in 0 until teamSpawns.size - 1) {
                    for (j in i + 1 until teamSpawns.size) {
                        val distance = teamSpawns[i].distance(teamSpawns[j])
                        if (distance < minSpawnDistance) {
                            val teamName = team.name.lowercase()
                            errors.add(plugin.languageManager.getMessage("scanner.${teamName}-spawns-too-close", "point1" to (i+1).toString(), "point2" to (j+1).toString(), "distance" to String.format("%.1f", distance), "min" to minSpawnDistance.toString()))
                        }
                    }
                }
            }
        }

        // 旗とスポーン地点の距離検証（エラーがない場合のみ）
        if (errors.isEmpty()) {
            val minFlagSpawnDistance = 3.0

            for (team in activeTeams) {
                val teamFlags = flags[team] ?: continue
                val teamSpawns = spawns[team] ?: continue
                if (teamFlags.isEmpty() || teamSpawns.isEmpty()) continue

                val flag = teamFlags[0]
                val tooCloseSpawns = teamSpawns.filter { spawn ->
                    spawn.distance(flag) < minFlagSpawnDistance
                }
                if (tooCloseSpawns.isNotEmpty()) {
                    val teamName = team.name.lowercase()
                    errors.add(plugin.languageManager.getMessage("scanner.${teamName}-flag-spawn-too-close", "count" to tooCloseSpawns.size.toString(), "min" to minFlagSpawnDistance.toString()))
                }
            }
        }
    }
}
