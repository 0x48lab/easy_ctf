package com.hacklab.ctf.block

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.Team
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player

/**
 * ブロック接続システムの管理。
 * Game への参照を通じてゲーム状態を読み取り、ブロック接続関連の処理を行う。
 */
class BlockConnectionSystem(
    private val game: Game,
    private val plugin: Main
) {

    /**
     * ブロック設置が許可されているかをチェック
     * @param player 設置するプレイヤー
     * @param block 設置するブロック
     * @return 設置可能な場合はペアレントの位置、不可の場合null
     */
    fun canPlaceBlock(player: Player, block: org.bukkit.block.Block): Location? {
        val team = game.getPlayerTeam(player.uniqueId) ?: return null

        // 観戦者はブロックを設置できない
        if (team == Team.SPECTATOR) return null

        val location = block.location

        // 建築フェーズ中の敵陣建築制限（ゾーン制）
        // チームカラーブロックか否かを問わず、敵陣側には一切設置できない。
        // 建築フェーズを「敵陣への妨害」ではなく「自陣～中立地帯での通路構築」に集中させるための制限。
        if (game.phase == GamePhase.BUILD &&
            game.territorySystem.restrictionEnabled &&
            game.territorySystem.isInEnemyTerritory(location, team)
        ) {
            game.showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-enemy-territory"))
            return null
        }

        // スポーン地点と旗周辺の建築制限チェック（3x3、Y座標全て）
        // 赤チームの旗周辺チェック
        game.redFlagLocation?.let { flagLoc ->
            if (kotlin.math.abs(location.blockX - flagLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - flagLoc.blockZ) <= 1) {
                game.showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-flag"))
                return null
            }
        }

        // 青チームの旗周辺チェック
        game.blueFlagLocation?.let { flagLoc ->
            if (kotlin.math.abs(location.blockX - flagLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - flagLoc.blockZ) <= 1) {
                game.showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-flag"))
                return null
            }
        }

        // 複数スポーン地点に対応
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(game.gameName)

        // 赤チームのスポーン地点周辺チェック（複数対応）
        val redSpawnLocations = config?.getAllRedSpawnLocations() ?: listOfNotNull(game.redSpawnLocation)
        for (spawnLoc in redSpawnLocations) {
            if (kotlin.math.abs(location.blockX - spawnLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - spawnLoc.blockZ) <= 1) {
                game.showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-spawn"))
                return null
            }
        }

        // 青チームのスポーン地点周辺チェック（複数対応）
        val blueSpawnLocations = config?.getAllBlueSpawnLocations() ?: listOfNotNull(game.blueSpawnLocation)
        for (spawnLoc in blueSpawnLocations) {
            if (kotlin.math.abs(location.blockX - spawnLoc.blockX) <= 1 &&
                kotlin.math.abs(location.blockZ - spawnLoc.blockZ) <= 1) {
                game.showActionBarError(player, plugin.languageManager.getMessage("action-bar.cannot-place-spawn"))
                return null
            }
        }

        // チームカラーのブロックかチェック
        val teamBlocks = Game.TEAM_BLOCKS[team] ?: return null
        val isTeamBlock = block.type in teamBlocks

        plugin.logger.info("[canPlaceBlock] isTeamBlock: $isTeamBlock, block.type: ${block.type}")

        // チームカラーブロック以外は制限なしで設置可能
        if (!isTeamBlock) {
            plugin.logger.info("[canPlaceBlock] Non-team block, allowing placement freely")
            // ダミーの位置を返す（recordBlockPlacementで使用されるが、非チームブロックなので影響なし）
            return location
        }

        // 以下、チームカラーブロック（コンクリート・ステンドグラス）のみの制限

        // 自チームの旗位置を取得
        val teamFlagLocation = when (team) {
            Team.RED -> game.redFlagLocation
            Team.BLUE -> game.blueFlagLocation
            Team.SPECTATOR -> null
            else -> game.flagLocations[team]
        } ?: return null

        // 自チームのスポーン地点を取得（複数対応）
        val teamSpawnLocations = when (team) {
            Team.RED -> config?.getAllRedSpawnLocations() ?: listOfNotNull(game.redSpawnLocation)
            Team.BLUE -> config?.getAllBlueSpawnLocations() ?: listOfNotNull(game.blueSpawnLocation)
            Team.SPECTATOR -> emptyList()
            else -> listOfNotNull(game.spawnLocations[team])
        }

        // 既に設置したブロックを取得
        val placedBlocks = game.teamPlacedBlocks[team] ?: mutableSetOf()

        // デバッグログ追加
        plugin.logger.info("[canPlaceBlock] Team: $team, PlacedBlocks count: ${placedBlocks.size}, Phase: ${game.phase}")
        plugin.logger.info("[canPlaceBlock] Team block placement check - Location: ${location}")

        // チームカラーブロックの設置制限：
        // 1. 旗から3ブロック以内なら旗位置を返す
        // 2. スポーン地点から3ブロック以内ならスポーン地点を返す
        // 3. 既存のチームブロックに隣接していれば、その位置を返す
        // 4. それ以外は設置不可

        // 旗から3ブロック以内かチェック
        if (location.world == teamFlagLocation.world && location.distance(teamFlagLocation) <= 3.0) {
            plugin.logger.info("[canPlaceBlock] Team block is within 3 blocks of team flag")
            // チームカラーブロックの場合は旗の基礎ブロックの位置を返す
            val flagBaseBlocks = mutableListOf<Location>()
            for (x in -1..1) {
                for (z in -1..1) {
                    flagBaseBlocks.add(teamFlagLocation.clone().add(x.toDouble(), -1.0, z.toDouble()))
                }
            }
            val result = flagBaseBlocks.minByOrNull { it.distance(location) } ?: teamFlagLocation
            plugin.logger.info("[canPlaceBlock] Returning flag base location: $result")
            return result
        }

        // スポーン地点から3ブロック以内かチェック
        for (teamSpawnLocation in teamSpawnLocations) {
            if (location.world == teamSpawnLocation.world && location.distance(teamSpawnLocation) <= 3.0) {
                plugin.logger.info("[canPlaceBlock] Team block is within 3 blocks of spawn location")
                return teamSpawnLocation
            }
        }

        // 既存チームブロックに隣接チェック
        plugin.logger.info("[canPlaceBlock] Checking team block placement, need adjacent team block")
        for (placedBlock in placedBlocks) {
            if (isAdjacent(location, placedBlock)) {
                plugin.logger.info("[canPlaceBlock] Found adjacent team block")
                return placedBlock
            }
        }

        // どの条件も満たさない場合は設置不可
        game.showActionBarError(player, plugin.languageManager.getMessage("action-bar.place-restriction-flag"))
        return null
    }

    fun hasAdjacentTeamBlock(location: Location, team: Team): Boolean {
        val world = location.world
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ

        // 26方向（3x3x3の立方体から中心を除いた全方向）をチェック
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    // 中心（0,0,0）はスキップ
                    if (dx == 0 && dy == 0 && dz == 0) continue

                    val pos = Location(world, (x + dx).toDouble(), (y + dy).toDouble(), (z + dz).toDouble())
                    val block = pos.block
                    val blockType = block.type

                    // 自チームのブロックかチェック
                    val isTeamBlock = when (team) {
                        Team.RED -> blockType == Material.RED_CONCRETE
                        Team.BLUE -> blockType == Material.BLUE_CONCRETE
                        Team.SPECTATOR -> false
                        else -> blockType == team.getConcreteMaterial()
                    }

                    if (isTeamBlock) {
                        plugin.logger.info("[hasAdjacentTeamBlock] Found adjacent $team block at $pos (offset: $dx,$dy,$dz)")
                        return true
                    }

                    // ビーコン（旗）も自チームのブロックとして扱う
                    if (blockType == Material.BEACON) {
                        val flagLocation = when (team) {
                            Team.RED -> game.redFlagLocation
                            Team.BLUE -> game.blueFlagLocation
                            Team.SPECTATOR -> null
                            else -> game.flagLocations[team]
                        }

                        if (flagLocation != null &&
                            pos.blockX == flagLocation.blockX &&
                            pos.blockY == flagLocation.blockY &&
                            pos.blockZ == flagLocation.blockZ) {
                            plugin.logger.info("[hasAdjacentTeamBlock] Found adjacent $team flag at $pos")
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * フェンスや装置かどうかをチェック
     */
    private fun isDeviceOrFence(material: Material): Boolean {
        return material in setOf(
            Material.OAK_FENCE, Material.IRON_BARS, Material.GLASS_PANE,
            Material.LADDER, Material.OAK_TRAPDOOR, Material.IRON_TRAPDOOR,
            Material.OAK_DOOR, Material.IRON_DOOR, Material.TORCH,
            Material.REDSTONE_TORCH, Material.STONE_BUTTON, Material.LEVER,
            Material.STONE_PRESSURE_PLATE, Material.PISTON, Material.STICKY_PISTON,
            Material.REDSTONE, Material.REDSTONE_BLOCK, Material.HOPPER,
            Material.DISPENSER, Material.TNT
        )
    }

    /**
     * 2つの位置が隣接しているかチェック（縦横斜め）
     */
    private fun isAdjacent(loc1: Location, loc2: Location): Boolean {
        val dx = kotlin.math.abs(loc1.blockX - loc2.blockX)
        val dy = kotlin.math.abs(loc1.blockY - loc2.blockY)
        val dz = kotlin.math.abs(loc1.blockZ - loc2.blockZ)

        // 隣接の定義：各軸の差が1以下で、少なくとも1つの軸で差がある
        return dx <= 1 && dy <= 1 && dz <= 1 && (dx + dy + dz) > 0
    }

    /**
     * ブロック設置を記録（ツリー構造で管理）
     */
    fun recordBlockPlacement(team: Team, location: Location, parent: Location) {
        val block = location.block

        // すべての設置されたブロックを記録（フェンスや装置も含む）
        val blocks = game.teamPlacedBlocks.getOrPut(team) { mutableSetOf() }
        blocks.add(location.clone())

        // チームカラーブロックの場合はツリー構造も記録
        val teamBlocks = Game.TEAM_BLOCKS[team] ?: return
        if (block.type in teamBlocks) {
            val trees = game.teamBlockTrees.getOrPut(team) { mutableMapOf() }

            // 親ノードを取得または作成（旗の場合）
            val parentNode = trees[parent] ?: Game.BlockNode(parent, null).also { trees[parent] = it }

            // 新しいノードを作成
            val newNode = Game.BlockNode(location, parent)
            trees[location] = newNode

            // 親ノードに子として追加
            parentNode.children.add(location)

            // 隣接する白いブロックを再接続する
            reconnectWhiteBlocks(team, location)
        }
        // フェンスや装置は設置記録しない（破壊されても切断判定に影響しない）
    }

    /**
     * 新しく設置したブロックの隣接する白いブロックを再接続し、チームカラーに戻す
     */
    private fun reconnectWhiteBlocks(team: Team, newBlockLocation: Location) {
        val offsets = listOf(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH_EAST, BlockFace.NORTH_WEST,
            BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST
        )

        val teamBlocks = Game.TEAM_BLOCKS[team] ?: return
        val blocks = game.teamPlacedBlocks.getOrPut(team) { mutableSetOf() }
        val trees = game.teamBlockTrees.getOrPut(team) { mutableMapOf() }
        val newNode = trees[newBlockLocation] ?: return

        // 隣接ブロックをチェック
        for (face in offsets) {
            val adjacentLoc = newBlockLocation.block.getRelative(face).location
            val adjacentBlock = adjacentLoc.block

            // 白いコンクリートの場合
            if (adjacentBlock.type == Game.NEUTRAL_BLOCK) {
                // このブロックから再帰的に接続された白いブロックを全て収集
                val connectedWhiteBlocks = mutableSetOf<Location>()
                collectConnectedWhiteBlocks(adjacentLoc, connectedWhiteBlocks)

                // 収集した白いブロックを全てチームカラーに戻す
                val blockType = teamBlocks.first() // チームの最初のブロックタイプを使用
                for (whiteLoc in connectedWhiteBlocks) {
                    whiteLoc.block.type = blockType

                    // ブロックリストに追加
                    blocks.add(whiteLoc.clone())

                    // ツリーに追加（新しく設置したブロックの子として）
                    val whiteNode = Game.BlockNode(whiteLoc, newBlockLocation)
                    trees[whiteLoc] = whiteNode
                    newNode.children.add(whiteLoc)
                }
            }
        }
    }

    /**
     * 接続された白いブロックを再帰的に収集
     */
    private fun collectConnectedWhiteBlocks(location: Location, result: MutableSet<Location>) {
        if (location in result) return
        if (location.block.type != Game.NEUTRAL_BLOCK) return

        result.add(location.clone())

        val offsets = listOf(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH_EAST, BlockFace.NORTH_WEST,
            BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST
        )

        for (face in offsets) {
            val adjacentLoc = location.block.getRelative(face).location
            collectConnectedWhiteBlocks(adjacentLoc, result)
        }
    }

    /**
     * ブロック破壊を記録し、切断されたブロックを処理
     */
    fun recordBlockBreak(location: Location) {
        // 非チームブロックの設置記録を削除
        game.placedNonTeamBlocks.remove(location)

        // 破壊されたブロックがどのチームのものか特定
        var ownerTeam: Team? = null
        for ((team, blocks) in game.teamPlacedBlocks) {
            if (location in blocks) {
                ownerTeam = team
                break
            }
        }

        if (ownerTeam == null) return

        // ブロックを削除
        game.teamPlacedBlocks[ownerTeam]?.remove(location)
        val trees = game.teamBlockTrees[ownerTeam] ?: return
        val node = trees[location] ?: return

        // 親ノードから子として削除
        node.parent?.let { parentLoc ->
            trees[parentLoc]?.children?.remove(location)
        }

        // 破壊されたブロック自体をツリーから削除
        trees.remove(location)

        // ビーコンからの到達可能性をチェックして、切断されたブロックを特定
        val reachableBlocks = findReachableBlocks(ownerTeam)

        // 到達不可能なブロックを白いブロックに変換
        val allTeamBlocks = game.teamPlacedBlocks[ownerTeam] ?: mutableSetOf()
        for (blockLoc in allTeamBlocks.toList()) {
            if (blockLoc !in reachableBlocks && blockLoc != location) {
                // ブロックを白いコンクリートに変換
                val block = blockLoc.block
                when (block.type) {
                    Material.RED_CONCRETE, Material.BLUE_CONCRETE -> block.type = Material.WHITE_CONCRETE
                    else -> {}
                }

                // リストから削除
                game.teamPlacedBlocks[ownerTeam]?.remove(blockLoc)
                trees.remove(blockLoc)
            }
        }
    }

    /**
     * ビーコンから到達可能なブロックを探索（BFS）
     */
    private fun findReachableBlocks(team: Team): Set<Location> {
        // 観戦者にはチームブロックがない
        if (team == Team.SPECTATOR) return emptySet()

        val reachable = mutableSetOf<Location>()
        val queue = mutableListOf<Location>()

        // ビーコンの位置を起点として追加
        val beaconLocation = when (team) {
            Team.RED -> game.redFlagLocation
            Team.BLUE -> game.blueFlagLocation
            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
            else -> game.flagLocations[team]
        } ?: return emptySet()

        // スポーン地点を取得（複数対応）
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(game.gameName)
        val spawnLocations = when (team) {
            Team.RED -> config?.getAllRedSpawnLocations() ?: listOfNotNull(game.redSpawnLocation ?: game.redFlagLocation)
            Team.BLUE -> config?.getAllBlueSpawnLocations() ?: listOfNotNull(game.blueSpawnLocation ?: game.blueFlagLocation)
            Team.SPECTATOR -> emptyList()
            else -> listOfNotNull(game.spawnLocations[team] ?: game.flagLocations[team])
        }.map { loc ->
            // 一時ワールドで実行している場合は座標を変換
            if (beaconLocation.world != null && loc.world != beaconLocation.world) {
                Location(beaconLocation.world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
            } else {
                loc
            }
        }

        // ビーコン周辺3ブロック以内のブロックを起点に追加
        val teamBlocks = game.teamPlacedBlocks[team] ?: return emptySet()
        for (block in teamBlocks) {
            // ワールドが同じ場合のみ距離計算
            if (block.world == beaconLocation.world && block.distance(beaconLocation) <= 3.0) {
                queue.add(block)
                reachable.add(block)
            }
        }

        // スポーン装飾ブロック（ルートノード）を起点として追加
        val trees = game.teamBlockTrees[team] ?: mutableMapOf()
        for ((blockLoc, node) in trees) {
            if (node.parent == null) {
                // 親がないブロック（スポーン装飾ブロック）は常に接続されている
                if (blockLoc !in reachable) {
                    queue.add(blockLoc)
                    reachable.add(blockLoc)
                }
            }
        }

        // すべてのスポーン地点周辺3ブロック以内のブロックも起点に追加
        for (spawnLocation in spawnLocations) {
            for (block in teamBlocks) {
                // ワールドが同じ場合のみ距離計算
                if (block.world == spawnLocation.world && block.distance(spawnLocation) <= 3.0) {
                    if (block !in reachable) {
                        queue.add(block)
                        reachable.add(block)
                    }
                }
            }
        }

        // BFSで隣接ブロックを探索
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)

            // 隣接する全方向（26方向）をチェック
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue

                        val neighbor = current.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble())
                        if (neighbor in teamBlocks && neighbor !in reachable) {
                            reachable.add(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }
            }
        }

        return reachable
    }
}
