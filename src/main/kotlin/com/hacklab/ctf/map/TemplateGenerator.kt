package com.hacklab.ctf.map

import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import org.bukkit.Material
import org.bukkit.entity.Player

class TemplateGenerator(private val plugin: Main) {

    enum class TemplateSize(val width: Int, val depth: Int, val height: Int, val teamCount: Int, val displayName: String) {
        SMALL(48, 32, 40, 2, "小"),
        MEDIUM(64, 48, 48, 3, "中"),
        LARGE(80, 80, 48, 4, "大")
    }

    /**
     * 向き情報: forward=前方, right=右手方向
     */
    data class Direction(val fwdX: Int, val fwdZ: Int, val rightX: Int, val rightZ: Int)

    /**
     * プレイヤーのyawから4方位を判定し、forward/right方向を返す
     */
    private fun getDirection(yaw: Float): Direction {
        val normalized = ((yaw % 360) + 360) % 360
        return when {
            normalized in 315.0..360.0 || normalized in 0.0..45.0 ->
                Direction(0, 1, -1, 0)    // 南向き: 前=+Z, 右=-X
            normalized in 45.0..135.0 ->
                Direction(-1, 0, 0, -1)   // 西向き: 前=-X, 右=-Z
            normalized in 135.0..225.0 ->
                Direction(0, -1, 1, 0)    // 北向き: 前=-Z, 右=+X
            else ->
                Direction(1, 0, 0, 1)     // 東向き: 前=+X, 右=+Z
        }
    }

    /**
     * 相対座標（右方向=r, 前方向=f）をワールド座標に変換
     */
    private fun toWorld(baseX: Int, baseZ: Int, r: Int, f: Int, dir: Direction): Pair<Int, Int> {
        return Pair(
            baseX + r * dir.rightX + f * dir.fwdX,
            baseZ + r * dir.rightZ + f * dir.fwdZ
        )
    }

    fun generate(player: Player, size: TemplateSize): Boolean {
        val world = player.world
        val baseX = player.location.blockX
        val baseY = player.location.blockY
        val baseZ = player.location.blockZ
        val dir = getDirection(player.location.yaw)

        val width = size.width   // 右方向の幅
        val depth = size.depth   // 前方向の奥行き
        val height = size.height

        plugin.logger.info("[Template] Generating ${size.name} template at ($baseX, $baseY, $baseZ), dir=$dir, size: ${width}x${depth}x${height}")

        // 羊毛マーカー（ステージの1ブロック外側に配置）
        val (pos1X, pos1Z) = toWorld(baseX, baseZ, -1, -1, dir)
        val (pos2X, pos2Z) = toWorld(baseX, baseZ, width, depth, dir)
        world.getBlockAt(pos1X, baseY - 1, pos1Z).type = Material.WHITE_WOOL
        world.getBlockAt(pos2X, baseY + height, pos2Z).type = Material.ORANGE_WOOL

        // 中央エントランス（石レンガの床）
        val centralSize = when (size) {
            TemplateSize.SMALL -> 12
            TemplateSize.MEDIUM -> 16
            TemplateSize.LARGE -> 20
        }
        val centralStartR = (width - centralSize) / 2
        val centralStartF = (depth - centralSize) / 2
        for (r in centralStartR until centralStartR + centralSize) {
            for (f in centralStartF until centralStartF + centralSize) {
                val (wx, wz) = toWorld(baseX, baseZ, r, f, dir)
                world.getBlockAt(wx, baseY, wz).type = Material.STONE_BRICKS
            }
        }

        // チームベースの配置
        val activeTeams = Team.getActiveTeams(size.teamCount)
        val basePositions = getBasePositions(size, width, depth)

        activeTeams.forEachIndexed { index, team ->
            if (index < basePositions.size) {
                val (relR, relF) = basePositions[index]
                val (wx, wz) = toWorld(baseX, baseZ, relR, relF, dir)
                placeTeamBase(world, wx, baseY, wz, team)
            }
        }

        return true
    }

    /**
     * 各チームベースの相対座標（右方向, 前方向）を返す。四隅配置。
     */
    private fun getBasePositions(size: TemplateSize, width: Int, depth: Int): List<Pair<Int, Int>> {
        val margin = 5
        return when (size) {
            TemplateSize.SMALL -> listOf(
                Pair(margin, margin),                         // RED: 左下（プレイヤー側）
                Pair(width - margin, depth - margin)          // BLUE: 右上（対角）
            )
            TemplateSize.MEDIUM -> listOf(
                Pair(margin, margin),                         // RED: 左下
                Pair(width - margin, depth - margin),         // BLUE: 右上
                Pair(width - margin, margin)                  // GREEN: 右下
            )
            TemplateSize.LARGE -> listOf(
                Pair(margin, margin),                         // RED: 左下
                Pair(width - margin, margin),                 // BLUE: 右下
                Pair(width - margin, depth - margin),         // GREEN: 右上
                Pair(margin, depth - margin)                  // YELLOW: 左上
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
