package com.hacklab.ctf.flag

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import java.time.Duration
import java.util.*

/**
 * 旗（フラグ）システムの管理。
 * Game への参照を通じてゲーム状態を読み取り、旗関連の処理を行う。
 */
class FlagSystem(
    private val game: Game,
    private val plugin: Main
) {

    fun setupFlags() {
        // 旗の設置（ビーコン）
        Team.values().filter { it != Team.SPECTATOR }.forEach { team ->
            val flagLocation = when (team) {
                Team.RED -> game.redFlagLocation
                Team.BLUE -> game.blueFlagLocation
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
                else -> game.flagLocations[team]
            } ?: return@forEach

            setupFlagBeacon(flagLocation, team)
        }
    }

    fun setupFlagBeacon(location: Location, team: Team) {
        // 観戦者には旗を設置しない
        if (team == Team.SPECTATOR) return

        // ビーコンを設置
        location.block.type = Material.BEACON

        // 3x3の鉄ブロックベースを設置し、teamPlacedBlocksとteamBlockTreesに追加
        for (x in -1..1) {
            for (z in -1..1) {
                val blockLoc = location.clone().add(x.toDouble(), -1.0, z.toDouble())
                blockLoc.block.type = Material.IRON_BLOCK
                // 旗の基礎ブロックをteamPlacedBlocksに追加（接続チェックの起点として）
                val blocks = game.teamPlacedBlocks.getOrPut(team) { mutableSetOf() }
                blocks.add(blockLoc)

                // teamBlockTreesにも追加（ルートノードとして）
                val trees = game.teamBlockTrees.getOrPut(team) { mutableMapOf() }
                trees[blockLoc] = Game.BlockNode(blockLoc, null) // ルートノードなので親はnull
            }
        }

        // チーム色のステンドグラスを上に設置
        val glassType = when (team) {
            Team.RED -> Material.RED_STAINED_GLASS
            Team.BLUE -> Material.BLUE_STAINED_GLASS
            else -> Material.WHITE_STAINED_GLASS
        }
        location.clone().add(0.0, 1.0, 0.0).block.type = glassType

        // バナーをステンドグラスの上に設置
        placeBannerOnBeacon(location, team)

        // 旗位置を記録
        when (team) {
            Team.RED -> game.redFlagLocation = location
            Team.BLUE -> game.blueFlagLocation = location
            Team.SPECTATOR -> {} // 観戦者は旗位置を記録しない
            else -> game.flagLocations[team] = location
        }
    }

    fun dropFlag(player: Player, team: Team, deathLocation: Location? = null) {
        // 観戦者は旗を持つことができない
        if (team == Team.SPECTATOR) return

        val carrier = when (team) {
            Team.RED -> game.redFlagCarrier
            Team.BLUE -> game.blueFlagCarrier
            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
            else -> game.flagCarriers[team]
        }

        if (carrier != player.uniqueId) return

        // キャリアをクリア
        when (team) {
            Team.RED -> game.redFlagCarrier = null
            Team.BLUE -> game.blueFlagCarrier = null
            Team.SPECTATOR -> {} // 観戦者は旗キャリアではない
            else -> game.flagCarriers[team] = null
        }

        // グロー効果を解除
        player.isGlowing = false

        // ヘルメットを元の革帽子に戻す
        restoreHelmet(player)

        // ドロップ位置を決定（死亡位置が指定されている場合はそれを使用）
        val baseLocation = deathLocation ?: player.location

        // 旗が回収不可能な状況かチェック
        val isUnrecoverable = when {
            // 奈落（Y座標が0以下）
            baseLocation.blockY <= 0 -> true

            // 溶岩の中
            baseLocation.block.type == Material.LAVA -> true

            // 高所から落下中（下に安全な場所がない）
            isLocationFalling(baseLocation) && findSafeDropLocation(baseLocation) == null -> true

            // 死亡位置の周囲が危険（溶岩や奈落に囲まれている）
            isLocationSurroundedByDanger(baseLocation) -> true

            else -> false
        }

        if (isUnrecoverable) {
            // 回収不可能な場合は即座に旗を返却
            val flagLocation = when (team) {
                Team.RED -> game.redFlagLocation
                Team.BLUE -> game.blueFlagLocation
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
                else -> game.flagLocations[team]
            } ?: return

            setupFlagBeacon(flagLocation, team)

            game.getAllPlayers().forEach {
                it.sendMessage(plugin.languageManager.getMessageAsComponent("flag.returned-uncollectable",
                    "color" to team.colorCode,
                    "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")))
            }
            return
        }

        // ドロップ位置はプレイヤーの現在位置
        val dropLocation = baseLocation

        // 旗をドロップ
        val itemStack = ItemStack(Material.BEACON)
        val meta = itemStack.itemMeta
        meta.displayName(plugin.languageManager.getMessageAsComponent("flag.flag-name", "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")))
        meta.isUnbreakable = true
        itemStack.itemMeta = meta

        val droppedItem = player.world.dropItem(dropLocation, itemStack)
        droppedItem.setGlowing(true)
        droppedItem.customName(plugin.languageManager.getMessageAsComponent("flag.flag-name", "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")))
        droppedItem.isCustomNameVisible = true
        droppedItem.isInvulnerable = true
        droppedItem.setGravity(false) // 重力を無効化して落下を防ぐ
        droppedItem.velocity = org.bukkit.util.Vector(0, 0, 0) // 速度をゼロにする

        // ドロップ情報を記録
        game.droppedFlags[dropLocation] = Pair(team, System.currentTimeMillis())

        // ドロップ位置にバナーブロックを設置
        placeDroppedBanner(dropLocation, team)

        // メッセージ
        game.getAllPlayers().forEach {
            it.sendMessage(plugin.languageManager.getMessageAsComponent("flag.flag-dropped", "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")))
        }
    }

    fun checkDroppedFlags() {
        val iterator = game.droppedFlags.iterator()
        while (iterator.hasNext()) {
            val (location, data) = iterator.next()
            val (team, dropTime) = data

            // 30秒経過したら元の位置に戻す
            if (System.currentTimeMillis() - dropTime > 30000) {
                // ドロップアイテムを削除
                location.world.getNearbyEntities(location, 50.0, 50.0, 50.0)
                    .filterIsInstance<org.bukkit.entity.Item>()
                    .find { it.itemStack.type == Material.BEACON }
                    ?.remove()

                // ドロップ位置のバナーブロックを除去
                removeDroppedBanner(location)

                // 旗を元の位置に再設置
                val flagLocation = when (team) {
                    Team.RED -> game.redFlagLocation
                    Team.BLUE -> game.blueFlagLocation
                    Team.SPECTATOR -> null // 観戦者用（到達しないはず）
                    else -> game.flagLocations[team]
                } ?: continue

                setupFlagBeacon(flagLocation, team)

                iterator.remove()

                game.getAllPlayers().forEach {
                    it.sendMessage(plugin.languageManager.getMessageAsComponent("flag.returned-home",
                        "color" to team.colorCode,
                        "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")))
                }
            }
        }
    }

    fun pickupFlag(player: Player, item: org.bukkit.entity.Item): Boolean {
        val team = game.getPlayerTeam(player.uniqueId) ?: return false

        // アイテムがビーコンかチェック
        if (item.itemStack.type != Material.BEACON) return false

        // アイテムのメタデータから旗のチームを判別
        val itemMeta = item.itemStack.itemMeta
        val displayName = itemMeta?.displayName()

        // どちらのチームの旗かを判定（displayNameとcustomNameの両方をチェック）
        val flagTeam = when {
            displayName != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName).contains("赤") -> Team.RED
            displayName != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName).contains("青") -> Team.BLUE
            item.customName() != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.customName()!!).contains("赤") -> Team.RED
            item.customName() != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.customName()!!).contains("青") -> Team.BLUE
            else -> {
                plugin.logger.warning("Failed to determine flag team for item: displayName=${displayName}, customName=${item.customName()}")
                return false
            }
        }

        // 自分のチームの旗を拾った場合
        if (team == flagTeam) {
            // ドロップフラグから削除
            game.droppedFlags.entries.removeIf { (location, data) ->
                data.first == flagTeam && location.distance(item.location) < 2.0
            }

            // ドロップ位置のバナーブロックを除去
            removeDroppedBanner(item.location)

            // アイテムを削除
            item.remove()

            // 旗を元の位置に戻す
            val flagLocation = when (flagTeam) {
                Team.RED -> game.redFlagLocation
                Team.BLUE -> game.blueFlagLocation
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
                else -> game.flagLocations[team]
            } ?: return false

            setupFlagBeacon(flagLocation, flagTeam)

            // メッセージ
            game.getAllPlayers().forEach {
                it.sendMessage(plugin.languageManager.getMessageAsComponent("flag.flag-recovered",
                    "player" to player.name,
                    "flagTeam" to plugin.languageManager.getMessage("teams.${flagTeam.name.lowercase()}")))
            }

            return true
        }

        // 敵の旗を拾う場合
        // 既に旗を持っているかチェック
        if (player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier) {
            player.sendMessage(plugin.languageManager.getMessageAsComponent("flag.already-carrying"))
            return false
        }

        // 旗を拾う
        when (flagTeam) {
            Team.RED -> game.redFlagCarrier = player.uniqueId
            Team.BLUE -> game.blueFlagCarrier = player.uniqueId
            Team.SPECTATOR -> {} // 観戦者は旗を持てない
            else -> game.flagCarriers[flagTeam] = player.uniqueId
        }

        // ドロップフラグから削除
        game.droppedFlags.entries.removeIf { (location, data) ->
            data.first == flagTeam && location.distance(item.location) < 2.0
        }

        // アイテムを削除
        item.remove()

        // ビーコン上のバナーを除去（ベースから直接取得した場合）
        val flagLocation = when (flagTeam) {
            Team.RED -> game.redFlagLocation
            Team.BLUE -> game.blueFlagLocation
            Team.SPECTATOR -> null
            else -> game.flagLocations[flagTeam]
        }
        if (flagLocation != null) {
            removeBannerFromBeacon(flagLocation)
        }

        // プレイヤーに発光効果
        player.isGlowing = true

        // 旗キャリアにバナーをヘルメットとして装備
        equipBannerHelmet(player, flagTeam)

        // メッセージ
        game.getAllPlayers().forEach {
            it.sendMessage(plugin.languageManager.getMessageAsComponent("flag.picked-up",
                "player" to player.name,
                "color" to flagTeam.colorCode,
                "team" to plugin.languageManager.getMessage("teams.${flagTeam.name.lowercase()}")))
        }

        return true
    }

    fun checkDroppedFlagTouch() {
        // ドロップされている旗の位置を確認
        val world = game.redFlagLocation?.world ?: return

        // 全プレイヤーをチェック
        game.getAllPlayers().forEach { player ->
            val team = game.getPlayerTeam(player.uniqueId) ?: return@forEach
            if (team == Team.SPECTATOR) return@forEach // 観戦者はスキップ

            // 既に旗を持っているプレイヤーはスキップ
            if (player.uniqueId == game.redFlagCarrier || player.uniqueId == game.blueFlagCarrier) {
                return@forEach
            }

            // ドロップされた旗の近くにいるかチェック
            world.entities.filterIsInstance<org.bukkit.entity.Item>().forEach { item ->
                if (item.itemStack.type == Material.BEACON && player.location.distance(item.location) < 1.5) {
                    // どちらのチームの旗かを判定
                    val flagTeam = when {
                        item.customName()?.contains(plugin.languageManager.getMessageAsComponent("teams.red")) == true -> Team.RED
                        item.customName()?.contains(plugin.languageManager.getMessageAsComponent("teams.blue")) == true -> Team.BLUE
                        else -> return@forEach
                    }

                    // 自分のチームの旗に触れた場合
                    if (team == flagTeam) {
                        // ドロップフラグから削除
                        game.droppedFlags.entries.removeIf { (location, data) ->
                            data.first == flagTeam && location.distance(item.location) < 2.0
                        }

                        // ドロップ位置のバナーブロックを除去
                        removeDroppedBanner(item.location)

                        // アイテムを削除
                        item.remove()

                        // 旗を元の位置に戻す
                        val flagLocation = when (flagTeam) {
                            Team.RED -> game.redFlagLocation
                            Team.BLUE -> game.blueFlagLocation
                            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
                            else -> game.flagLocations[flagTeam]
                        } ?: return@forEach

                        setupFlagBeacon(flagLocation, flagTeam)

                        // メッセージ
                        game.getAllPlayers().forEach { p ->
                            p.sendMessage(plugin.languageManager.getMessageAsComponent("flag.flag-recovered",
                                "player" to player.name,
                                "flagTeam" to plugin.languageManager.getMessage("teams.${flagTeam.name.lowercase()}")))
                        }
                    }
                    // 敵の旗に触れた場合
                    else if (team != flagTeam) {
                        // 旗を拾う
                        when (flagTeam) {
                            Team.RED -> game.redFlagCarrier = player.uniqueId
                            Team.BLUE -> game.blueFlagCarrier = player.uniqueId
                            Team.SPECTATOR -> {} // 観戦者は旗を持てない
                            else -> game.flagCarriers[flagTeam] = player.uniqueId
                        }

                        // ドロップフラグから削除
                        game.droppedFlags.entries.removeIf { (location, data) ->
                            data.first == flagTeam && location.distance(item.location) < 2.0
                        }

                        // ドロップ位置のバナーブロックを除去
                        removeDroppedBanner(item.location)

                        // アイテムを削除
                        item.remove()

                        // スポーン保護を解除（旗を持った時点で保護解除）
                        game.removeSpawnProtection(player)

                        // プレイヤーに発光効果
                        player.isGlowing = true

                        // 旗キャリアにバナーをヘルメットとして装備
                        equipBannerHelmet(player, flagTeam)

                        // メッセージ
                        game.getAllPlayers().forEach { p ->
                            p.sendMessage(plugin.languageManager.getMessageAsComponent("flag.picked-up",
                                "player" to player.name,
                                "color" to flagTeam.colorCode,
                                "team" to plugin.languageManager.getMessage("teams.${flagTeam.name.lowercase()}")))
                        }
                    }
                }
            }
        }
    }

    fun captureFlag(player: Player): Boolean {
        val team = game.getPlayerTeam(player.uniqueId) ?: return false

        // 観戦者は旗をキャプチャできない
        if (team == Team.SPECTATOR) return false

        // 旗を持っているかチェック
        val carriedFlagTeam = when (player.uniqueId) {
            game.redFlagCarrier -> Team.RED
            game.blueFlagCarrier -> Team.BLUE
            else -> return false
        }

        // 自分のチームの旗拠点にいるかチェック
        val ownFlagLocation = when (team) {
            Team.RED -> game.redFlagLocation
            Team.BLUE -> game.blueFlagLocation
            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
            else -> game.flagLocations[team]
        } ?: return false

        if (player.location.distance(ownFlagLocation) > 3.0) {
            return false
        }

        // 自分のチームの旗が自陣にあるかチェック
        val ownFlagIsAtBase = when (team) {
            Team.RED -> game.redFlagCarrier == null && !game.droppedFlags.any { it.value.first == Team.RED }
            Team.BLUE -> game.blueFlagCarrier == null && !game.droppedFlags.any { it.value.first == Team.BLUE }
            Team.SPECTATOR -> false // 観戦者用（到達しないはず）
            else -> game.flagCarriers[team] == null && !game.droppedFlags.any { it.value.first == team }
        }

        if (!ownFlagIsAtBase) {
            game.showActionBarError(player, plugin.languageManager.getMessage("flag.cannot-capture-no-flag"), 5000L)
            return false
        }

        // スコア加算
        game.score[team] = (game.score[team] ?: 0) + 1

        // スコアボードを即座に更新
        game.forceUpdateScoreboard()

        // キャプチャー統計を記録
        game.playerCaptures[player.uniqueId] = (game.playerCaptures[player.uniqueId] ?: 0) + 1

        // 通貨報酬（マッチがある場合もない場合も）
        val captureReward = plugin.config.getInt("currency.capture-reward", 50)
        game.addTeamCurrency(team, captureReward, plugin.languageManager.getMessage("currency.capture-currency", "player" to player.name))

        // キャプチャーアシスト報酬
        val assists = game.captureAssists[team] ?: mutableSetOf()
        assists.remove(player.uniqueId) // キャプチャーした本人は除外

        if (assists.isNotEmpty()) {
            val assistReward = plugin.config.getInt("currency.capture-assist-reward", 20)
            assists.forEach { assisterId ->
                val assister = Bukkit.getPlayer(assisterId)
                if (assister != null) {
                    assister.sendMessage(plugin.languageManager.getMessageAsComponent("currency.capture-assist-message", "amount" to assistReward.toString()))
                }
            }
            // チーム全体に一度だけアシスト報酬を追加
            if (assists.size > 0) {
                game.addTeamCurrency(team, assistReward * assists.size, plugin.languageManager.getMessage("currency.capture-assist", "count" to assists.size.toString()))
            }
        }

        // アシストリストをクリア
        game.captureAssists[team]?.clear()

        // キャリアをクリア
        when (carriedFlagTeam) {
            Team.RED -> game.redFlagCarrier = null
            Team.BLUE -> game.blueFlagCarrier = null
            Team.SPECTATOR -> {} // 観戦者は旗を持てない
            else -> game.flagCarriers[carriedFlagTeam] = null
        }

        // グロー効果を解除
        player.isGlowing = false

        // ヘルメットを元の革帽子に戻す
        restoreHelmet(player)

        // 敵の旗を元の位置に戻す
        val enemyFlagLocation = when (carriedFlagTeam) {
            Team.RED -> game.redFlagLocation
            Team.BLUE -> game.blueFlagLocation
            Team.SPECTATOR -> null // 観戦者用（到達しないはず）
            else -> game.flagLocations[carriedFlagTeam]
        } ?: return false

        setupFlagBeacon(enemyFlagLocation, carriedFlagTeam)

        // 全プレイヤーに通知
        game.getAllPlayers().forEach { p ->
            p.sendMessage(plugin.languageManager.getMessageAsComponent("flag.captured-by",
                "color" to team.colorCode,
                "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}"),
                "player" to player.name))
            p.sendMessage(plugin.languageManager.getMessageAsComponent("flag.current-score",
                "scores" to game.getScoreDisplayText()))

            // 効果音を再生
            p.playSound(p.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        }

        // タイトル表示
        game.getAllPlayers().forEach { p ->
            p.clearTitle() // 既存のタイトルをクリア
            p.showTitle(Title.title(
                plugin.languageManager.getMessageAsComponent("flag.scored-title", "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")),
                plugin.languageManager.getMessageAsComponent("flag.score-subtitle",
                    "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}"),
                    "scores" to game.getScoreDisplayText()),
                Title.Times.times(
                    Duration.ofMillis(250),
                    Duration.ofSeconds(2),
                    Duration.ofMillis(250)
                )
            ))
        }

        return true
    }

    /**
     * プレイヤーが落下中かどうかを判定
     */
    fun isPlayerFalling(player: Player): Boolean {
        // プレイヤーの足元のブロックを確認
        val blockBelow = player.location.clone().subtract(0.0, 1.0, 0.0).block
        val blockBelow2 = player.location.clone().subtract(0.0, 2.0, 0.0).block

        // 足元が空気ブロックで、かつY速度が負（落下中）の場合
        return blockBelow.type == Material.AIR &&
               blockBelow2.type == Material.AIR &&
               player.velocity.y < -0.5
    }

    /**
     * 指定位置が落下位置かどうかを判定
     */
    fun isLocationFalling(location: Location): Boolean {
        // 位置の下のブロックを確認
        val blockBelow = location.clone().subtract(0.0, 1.0, 0.0).block
        val blockBelow2 = location.clone().subtract(0.0, 2.0, 0.0).block
        val blockBelow3 = location.clone().subtract(0.0, 3.0, 0.0).block

        // 下3ブロックが空気の場合は落下位置と判定
        return blockBelow.type == Material.AIR &&
               blockBelow2.type == Material.AIR &&
               blockBelow3.type == Material.AIR
    }

    /**
     * 安全な旗ドロップ位置を探す
     */
    fun findSafeDropLocation(originalLocation: Location): Location? {
        val world = originalLocation.world
        val startX = originalLocation.blockX
        val startY = originalLocation.blockY
        val startZ = originalLocation.blockZ

        // まず現在位置が安全かチェック
        if (isSafeLocation(originalLocation)) {
            return originalLocation.clone().add(0.5, 0.5, 0.5)
        }

        // 現在位置から下方向に安全な場所を探す（最大50ブロック下まで）
        for (y in startY downTo kotlin.math.max(0, startY - 50)) {
            val checkLoc = Location(world, startX.toDouble(), y.toDouble(), startZ.toDouble())
            if (isSafeLocation(checkLoc)) {
                return checkLoc.add(0.5, 1.0, 0.5) // ブロックの中心、1ブロック上
            }
        }

        // 下に見つからない場合、周囲を螺旋状に探索
        for (radius in 1..15) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    // 螺旋の外周のみチェック
                    if (kotlin.math.abs(dx) == radius || kotlin.math.abs(dz) == radius) {
                        // 現在の高さから上下に探索
                        for (dy in -10..10) {
                            val y = startY + dy
                            if (y in 0..255) {
                                val checkLoc = Location(world, (startX + dx).toDouble(), y.toDouble(), (startZ + dz).toDouble())
                                if (isSafeLocation(checkLoc)) {
                                    return checkLoc.add(0.5, 1.0, 0.5)
                                }
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * 位置が危険物に囲まれているかチェック
     */
    fun isLocationSurroundedByDanger(location: Location): Boolean {
        val world = location.world
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ

        var dangerCount = 0
        var totalCount = 0

        // 周囲3x3x3の範囲をチェック
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue

                    val checkLoc = Location(world, (x + dx).toDouble(), (y + dy).toDouble(), (z + dz).toDouble())
                    val block = checkLoc.block

                    totalCount++

                    // 危険なブロック
                    if (block.type == Material.LAVA ||
                        block.type == Material.VOID_AIR ||
                        (checkLoc.blockY <= 0)) {
                        dangerCount++
                    }
                }
            }
        }

        // 半分以上が危険な場合は回収不可能と判定
        return dangerCount > totalCount / 2
    }

    /**
     * 指定位置が安全かどうかを判定
     */
    fun isSafeLocation(location: Location): Boolean {
        val block = location.block
        val blockAbove = location.clone().add(0.0, 1.0, 0.0).block
        val blockAbove2 = location.clone().add(0.0, 2.0, 0.0).block

        // 足元が固体ブロックで、上2ブロックが空気の場合は安全
        return block.type.isSolid &&
               !blockAbove.type.isSolid &&
               !blockAbove2.type.isSolid &&
               block.type != Material.LAVA &&
               block.type != Material.WATER
    }

    // ========== バナー関連ヘルパー ==========

    /**
     * チームに対応するバナーのMaterialを取得
     */
    fun getTeamBannerMaterial(team: Team): Material {
        return when (team) {
            Team.RED -> Material.RED_BANNER
            Team.BLUE -> Material.BLUE_BANNER
            Team.SPECTATOR -> Material.WHITE_BANNER
            else -> team.getBannerMaterial()
        }
    }

    /**
     * チームに対応するウォールバナーのMaterialを取得
     */
    fun getTeamWallBannerMaterial(team: Team): Material {
        return when (team) {
            Team.RED -> Material.RED_WALL_BANNER
            Team.BLUE -> Material.BLUE_WALL_BANNER
            Team.SPECTATOR -> Material.WHITE_WALL_BANNER
            else -> team.getBannerMaterial() // fallback to standing banner
        }
    }

    /**
     * チームバナーのItemStackを作成
     */
    fun createTeamBannerItem(team: Team): ItemStack {
        return ItemStack(getTeamBannerMaterial(team))
    }

    /**
     * ビーコンの上にバナーブロックを設置
     * ステンドグラスの上（ビーコン+2）に立てバナーを設置
     */
    fun placeBannerOnBeacon(beaconLocation: Location, team: Team) {
        val bannerLoc = beaconLocation.clone().add(0.0, 2.0, 0.0)
        bannerLoc.block.type = getTeamBannerMaterial(team)
    }

    /**
     * ビーコン上のバナーを除去
     */
    fun removeBannerFromBeacon(beaconLocation: Location) {
        val bannerLoc = beaconLocation.clone().add(0.0, 2.0, 0.0)
        val block = bannerLoc.block
        if (block.type.name.contains("BANNER")) {
            block.type = Material.AIR
        }
    }

    /**
     * ドロップ位置にバナーブロックを設置
     */
    fun placeDroppedBanner(location: Location, team: Team) {
        val bannerLoc = location.clone()
        val block = bannerLoc.block
        // 設置先が空気ブロックの場合のみ設置
        if (!block.type.isSolid) {
            block.type = getTeamBannerMaterial(team)
        }
    }

    /**
     * ドロップ位置のバナーブロックを除去
     */
    fun removeDroppedBanner(location: Location) {
        // 周囲のバナーブロックを探して削除
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    val checkLoc = location.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble())
                    if (checkLoc.block.type.name.contains("BANNER")) {
                        checkLoc.block.type = Material.AIR
                    }
                }
            }
        }
    }

    /**
     * 旗キャリアにバナーをヘルメットとして装備させる
     */
    fun equipBannerHelmet(player: Player, flagTeam: Team) {
        val banner = createTeamBannerItem(flagTeam)
        player.equipment?.helmet = banner
    }

    /**
     * 旗キャリアのヘルメットを元のチーム色の革帽子に戻す
     */
    fun restoreHelmet(player: Player) {
        val playerTeam = game.getPlayerTeam(player.uniqueId) ?: return
        val teamColor = if (playerTeam == Team.RED) Color.RED else Color.BLUE
        val unbreakable = plugin.config.getBoolean("equipment.combat-phase.armor.unbreakable", true)
        val helmet = ItemStack(Material.LEATHER_HELMET).apply {
            val meta = itemMeta
            if (meta is LeatherArmorMeta) {
                meta.setColor(teamColor)
            }
            if (unbreakable) {
                meta?.isUnbreakable = true
            }
            itemMeta = meta
        }
        player.equipment?.helmet = helmet
    }
}
