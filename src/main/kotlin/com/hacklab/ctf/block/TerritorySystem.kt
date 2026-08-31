package com.hacklab.ctf.block

import com.hacklab.ctf.Game
import com.hacklab.ctf.Main
import com.hacklab.ctf.utils.Team
import org.bukkit.Location

/**
 * マップ上の領域区分。
 * 自ビーコン・敵ビーコンからの距離比で判定する。
 */
enum class Zone {
    /** 自陣側 */
    HOME,

    /** 中立地帯（通路建築の主戦場） */
    NEUTRAL,

    /** 敵陣側 */
    ENEMY
}

/**
 * 領域（テリトリー）判定システム。
 *
 * 建築フェーズ中の敵陣建築禁止（ゾーン制）と、
 * 自陣コア半径内での敵ブロックダメージ無効化の判定を担当する。
 *
 * 距離は水平（X/Z）距離で計算する。テンポラリワールドと元ワールドで
 * Location の world 参照が食い違うケースがあるため、world は比較せず座標のみを使う。
 */
class TerritorySystem(
    private val game: Game,
    private val plugin: Main
) {

    /** 建築フェーズの敵陣建築制限が有効か */
    val restrictionEnabled: Boolean
        get() = plugin.config.getBoolean("mechanics.build-phase.territory-restriction", true)

    /** 距離比でこの値未満なら敵陣扱い（0.5 = 中央） */
    private val enemyTerritoryRatio: Double
        get() = plugin.config.getDouble("mechanics.build-phase.enemy-territory-ratio", 0.45)

    /** 敵ビーコン/スポーンからこの距離以内は無条件で敵陣扱い */
    private val enemyNoBuildRadius: Double
        get() = plugin.config.getDouble("mechanics.build-phase.enemy-beacon-no-build-radius", 20.0)

    /** 自陣コア半径（この範囲内では敵ブロックのダメージを無効化する） */
    val homeSafeRadius: Double
        get() = plugin.config.getDouble("mechanics.home-safe-radius", 8.0)

    /**
     * 指定位置がどのゾーンかを判定する。
     * 判定に必要な位置情報が揃っていない場合は NEUTRAL（＝制限なし）を返す。
     */
    fun getZone(location: Location, team: Team): Zone {
        if (team == Team.SPECTATOR) return Zone.NEUTRAL

        val ownAnchors = getAnchors(team)
        val enemyAnchors = getEnemyAnchors(team)
        if (ownAnchors.isEmpty() || enemyAnchors.isEmpty()) return Zone.NEUTRAL

        val distOwn = ownAnchors.minOf { horizontalDistance(location, it) }
        val distEnemy = enemyAnchors.minOf { horizontalDistance(location, it) }

        // 敵ビーコン近傍は無条件で敵陣。
        // ただし自陣より敵陣に近い場合のみ適用する（狭いマップで自陣まで敵陣判定になるのを防ぐ）
        if (distEnemy <= enemyNoBuildRadius && distEnemy < distOwn) return Zone.ENEMY

        val total = distOwn + distEnemy
        if (total <= 0.0) return Zone.NEUTRAL

        // ratio: 自ビーコン上で 1.0、敵ビーコン上で 0.0、中央で 0.5
        val ratio = distEnemy / total
        return when {
            ratio < enemyTerritoryRatio -> Zone.ENEMY
            ratio > 1.0 - enemyTerritoryRatio -> Zone.HOME
            else -> Zone.NEUTRAL
        }
    }

    /** 敵陣側かどうか */
    fun isInEnemyTerritory(location: Location, team: Team): Boolean =
        getZone(location, team) == Zone.ENEMY

    /**
     * 自陣コア（自ビーコン・自スポーンから homeSafeRadius 以内）かどうか。
     * 敵ブロックのダメージ無効化・即時撤去の判定に使う。
     */
    fun isInHomeArea(location: Location, team: Team): Boolean {
        if (team == Team.SPECTATOR) return false
        val anchors = getAnchors(team)
        if (anchors.isEmpty()) return false
        return anchors.any { horizontalDistance(location, it) <= homeSafeRadius }
    }

    /**
     * スポーン地点のキャッシュ。
     * GameManager.getGameConfig() は毎回 YAML をディスクから読み込むため、
     * 毎tick呼ばれるシールドタスクやブロック設置イベントから直接呼んではいけない。
     */
    private var spawnAnchorCache: MutableMap<Team, List<Location>> = mutableMapOf()

    /** ゲーム設定が変わった際にキャッシュを破棄する */
    fun invalidateCache() {
        spawnAnchorCache = mutableMapOf()
    }

    /** 指定チームの拠点（旗＋全スポーン地点） */
    private fun getAnchors(team: Team): List<Location> {
        val anchors = mutableListOf<Location>()
        game.flagLocations[team]?.let { anchors.add(it) }
        anchors.addAll(getSpawnAnchors(team))
        return anchors
    }

    /** 指定チームのスポーン地点（キャッシュ付き） */
    private fun getSpawnAnchors(team: Team): List<Location> {
        spawnAnchorCache[team]?.let { return it }

        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(game.gameName)
        val computed = if (config != null) {
            config.getAllSpawnLocations(team).map { it.clone() }
        } else {
            listOfNotNull(game.spawnLocations[team]?.clone())
        }

        // 未設定の段階で空リストをキャッシュしないようにする
        if (computed.isNotEmpty()) {
            spawnAnchorCache[team] = computed
        }
        return computed
    }

    /** 自チーム以外（観戦者を除く）の拠点 */
    private fun getEnemyAnchors(team: Team): List<Location> =
        game.getActiveTeams()
            .filter { it != team && it != Team.SPECTATOR }
            .flatMap { getAnchors(it) }

    /**
     * 水平距離（Y座標を無視）。
     * world 参照が異なる可能性があるため Location.distance() は使わない。
     */
    private fun horizontalDistance(a: Location, b: Location): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }
}
