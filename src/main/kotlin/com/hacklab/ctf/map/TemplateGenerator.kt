package com.hacklab.ctf.map

import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

class TemplateGenerator(private val plugin: Main) {

    enum class TemplateSize(val width: Int, val depth: Int, val height: Int, val teamCount: Int, val displayName: String) {
        SMALL(48, 40, 32, 2, "小"),
        MEDIUM(64, 48, 48, 3, "中"),
        LARGE(80, 80, 48, 4, "大")
    }

    fun generate(player: Player, size: TemplateSize): Boolean {
        val world = player.world
        val loc = player.location

        // Player's position is the starting corner (min X, min Y, min Z)
        val baseX = loc.blockX
        val baseY = loc.blockY
        val baseZ = loc.blockZ

        val width = size.width
        val depth = size.depth
        val height = size.height

        plugin.logger.info("[Template] Generating ${size.name} template at ($baseX, $baseY, $baseZ), size: ${width}x${depth}x${height}")

        // Step 1: Clear the area (set to AIR)
        for (x in 0 until width) {
            for (z in 0 until depth) {
                for (y in 0 until height) {
                    world.getBlockAt(baseX + x, baseY + y, baseZ + z).type = Material.AIR
                }
            }
        }

        // Step 2: Place floor (Y = baseY, stone)
        for (x in 0 until width) {
            for (z in 0 until depth) {
                world.getBlockAt(baseX + x, baseY, baseZ + z).type = Material.STONE
            }
        }

        // Step 3: Central entrance zone (stone bricks, centered)
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

        // Step 4: Glass walls around perimeter (from Y+1 to Y+height-1)
        for (y in 1 until height) {
            for (x in 0 until width) {
                world.getBlockAt(baseX + x, baseY + y, baseZ).type = Material.GLASS
                world.getBlockAt(baseX + x, baseY + y, baseZ + depth - 1).type = Material.GLASS
            }
            for (z in 0 until depth) {
                world.getBlockAt(baseX, baseY + y, baseZ + z).type = Material.GLASS
                world.getBlockAt(baseX + width - 1, baseY + y, baseZ + z).type = Material.GLASS
            }
        }

        // Step 5: Wool markers at pos1 and pos2
        world.getBlockAt(baseX, baseY, baseZ).type = Material.WHITE_WOOL  // pos1
        world.getBlockAt(baseX + width - 1, baseY + height - 1, baseZ + depth - 1).type = Material.ORANGE_WOOL  // pos2

        // Step 6: Team bases
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
        val margin = 8  // distance from wall to base center
        return when (size) {
            TemplateSize.SMALL -> listOf(
                Pair(baseX + width / 2, baseZ + margin),           // RED: near end
                Pair(baseX + width / 2, baseZ + depth - margin)    // BLUE: far end
            )
            TemplateSize.MEDIUM -> listOf(
                Pair(baseX + width / 2, baseZ + margin),                  // RED: top center
                Pair(baseX + margin, baseZ + depth - margin),             // BLUE: bottom left
                Pair(baseX + width - margin, baseZ + depth - margin)      // GREEN: bottom right
            )
            TemplateSize.LARGE -> listOf(
                Pair(baseX + margin, baseZ + margin),                     // RED: NW
                Pair(baseX + width - margin, baseZ + margin),             // BLUE: NE
                Pair(baseX + width - margin, baseZ + depth - margin),     // GREEN: SE
                Pair(baseX + margin, baseZ + depth - margin)              // YELLOW: SW
            )
        }
    }

    private fun placeTeamBase(world: org.bukkit.World, centerX: Int, baseY: Int, centerZ: Int, team: Team) {
        // Team colored concrete floor (5x5)
        for (dx in -2..2) {
            for (dz in -2..2) {
                world.getBlockAt(centerX + dx, baseY, centerZ + dz).type = team.getConcreteMaterial()
            }
        }

        // Iron block base (3x3) at Y+1 level (under beacon)
        for (dx in -1..1) {
            for (dz in -1..1) {
                world.getBlockAt(centerX + dx, baseY + 1, centerZ + dz).type = Material.IRON_BLOCK
            }
        }

        // Beacon at center Y+2
        world.getBlockAt(centerX, baseY + 2, centerZ).type = Material.BEACON

        // Stained glass on beacon Y+3
        world.getBlockAt(centerX, baseY + 3, centerZ).type = team.getStainedGlassMaterial()

        // Banner on top Y+4
        world.getBlockAt(centerX, baseY + 4, centerZ).type = team.getBannerMaterial()
    }
}
