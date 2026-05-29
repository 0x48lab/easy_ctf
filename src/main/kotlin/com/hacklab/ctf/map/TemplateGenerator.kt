package com.hacklab.ctf.map

import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

class TemplateGenerator(private val plugin: Main) {

    enum class TemplateSize(val width: Int, val depth: Int, val height: Int, val teamCount: Int, val displayName: String) {
        SMALL(48, 32, 40, 2, "小"),
        MEDIUM(64, 48, 48, 3, "中"),
        LARGE(80, 80, 48, 4, "大")
    }

    fun generate(player: Player, size: TemplateSize): Boolean {
        val world = player.world
        val loc = player.location

        val baseX = loc.blockX
        val baseY = loc.blockY
        val baseZ = loc.blockZ

        val width = size.width
        val depth = size.depth
        val height = size.height

        plugin.logger.info("[Template] Generating ${size.name} template at ($baseX, $baseY, $baseZ), size: ${width}x${depth}x${height}")

        // 羊毛マーカー（pos1/pos2 クリップ用）
        world.getBlockAt(baseX, baseY, baseZ).type = Material.WHITE_WOOL  // pos1
        world.getBlockAt(baseX + width - 1, baseY + height - 1, baseZ + depth - 1).type = Material.ORANGE_WOOL  // pos2

        // 中央エントランス（石レンガの床）
        val centralSize = when (size) {
            TemplateSize.SMALL -> 12
            TemplateSize.MEDIUM -> 16
            TemplateSize.LARGE -> 20
        }
        val centralStartX = (width - centralSize) / 2
        val centralStartZ = (depth - centralSize) / 2
        for (x in centralStartX until centralStartX + centralSize) {
            for (z in centralStartZ until centralStartZ + centralSize) {
                world.getBlockAt(baseX + x, baseY, baseZ + z).type = Material.STONE_BRICKS
            }
        }

        // チームベースの配置
        val activeTeams = Team.getActiveTeams(size.teamCount)
        val basePositions = getBasePositions(size, baseX, baseZ, width, depth)

        activeTeams.forEachIndexed { index, team ->
            if (index < basePositions.size) {
                val (bx, bz) = basePositions[index]
                placeTeamBase(world, bx, baseY, bz, team)
            }
        }

        return true
    }

    private fun getBasePositions(size: TemplateSize, baseX: Int, baseZ: Int, width: Int, depth: Int): List<Pair<Int, Int>> {
        val margin = 8
        return when (size) {
            TemplateSize.SMALL -> listOf(
                Pair(baseX + width / 2, baseZ + margin),
                Pair(baseX + width / 2, baseZ + depth - margin)
            )
            TemplateSize.MEDIUM -> listOf(
                Pair(baseX + width / 2, baseZ + margin),
                Pair(baseX + margin, baseZ + depth - margin),
                Pair(baseX + width - margin, baseZ + depth - margin)
            )
            TemplateSize.LARGE -> listOf(
                Pair(baseX + margin, baseZ + margin),
                Pair(baseX + width - margin, baseZ + margin),
                Pair(baseX + width - margin, baseZ + depth - margin),
                Pair(baseX + margin, baseZ + depth - margin)
            )
        }
    }

    private fun placeTeamBase(world: org.bukkit.World, centerX: Int, baseY: Int, centerZ: Int, team: Team) {
        // チーム色コンクリート床 (5x5)
        for (dx in -2..2) {
            for (dz in -2..2) {
                world.getBlockAt(centerX + dx, baseY, centerZ + dz).type = team.getConcreteMaterial()
            }
        }

        // 鉄ブロックベース (3x3)
        for (dx in -1..1) {
            for (dz in -1..1) {
                world.getBlockAt(centerX + dx, baseY + 1, centerZ + dz).type = Material.IRON_BLOCK
            }
        }

        // ビーコン（旗）
        world.getBlockAt(centerX, baseY + 2, centerZ).type = Material.BEACON

        // チーム色ステンドグラス
        world.getBlockAt(centerX, baseY + 3, centerZ).type = team.getStainedGlassMaterial()

        // チーム色バナー
        world.getBlockAt(centerX, baseY + 4, centerZ).type = team.getBannerMaterial()
    }
}
