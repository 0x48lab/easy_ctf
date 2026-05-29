package com.hacklab.ctf.config

import com.hacklab.ctf.utils.MatchMode
import com.hacklab.ctf.utils.Team
import org.bukkit.Location
import org.bukkit.World

/**
 * ゲーム設定を管理するデータクラス
 * YAMLファイルへの保存・読み込みを簡単にする
 */
data class GameConfig(
    val name: String,
    val world: World,
    
    // 位置設定
    var redFlagLocation: Location? = null,
    var redSpawnLocation: Location? = null,  // 後方互換性のため残す（最初のスポーン地点として使用）
    var blueFlagLocation: Location? = null,
    var blueSpawnLocation: Location? = null,  // 後方互換性のため残す（最初のスポーン地点として使用）
    
    // 複数スポーン地点
    var redSpawnLocations: MutableList<Location> = mutableListOf(),
    var blueSpawnLocations: MutableList<Location> = mutableListOf(),

    // 緑チーム位置設定
    var greenFlagLocation: Location? = null,
    var greenSpawnLocation: Location? = null,
    var greenSpawnLocations: MutableList<Location> = mutableListOf(),

    // 黄チーム位置設定
    var yellowFlagLocation: Location? = null,
    var yellowSpawnLocation: Location? = null,
    var yellowSpawnLocations: MutableList<Location> = mutableListOf(),

    // チーム数設定（2〜4）
    var teamCount: Int = 2,
    
    // ゲーム設定
    var autoStartEnabled: Boolean = false,
    var minPlayers: Int = 2,
    var maxPlayersPerTeam: Int = 10,
    
    // リスポーン設定
    var respawnDelayBase: Int = 10,        // 基本リスポーン時間（秒）
    var respawnDelayPerDeath: Int = 2,     // 死亡ごとの追加ペナルティ（秒）
    var respawnDelayMax: Int = 20,         // 最大リスポーン時間（秒）
    
    // フェーズ設定
    var buildDuration: Int = 300,
    var buildPhaseGameMode: String = "SURVIVAL",
    var combatDuration: Int = 600,
    var resultDuration: Int = 60,
    var intermediateDuration: Int = 15,  // マッチ中間の作戦会議時間
    
    // ブロック配布設定
    var buildPhaseBlocks: Int = 16,      // 建築フェーズで配布するブロック数（コンクリートとガラスそれぞれ）
    var combatPhaseBlocks: Int = 16,     // 戦闘フェーズで配布するブロック数
    
    // マッチ設定
    var matchMode: MatchMode = MatchMode.FIXED_ROUNDS,
    var matchTarget: Int = 3,
    
    // その他
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 必須設定が完了しているかチェック
     */
    fun isValid(): Boolean {
        val teamsToCheck = Team.getActiveTeams(teamCount)
        return teamsToCheck.all { getFlagLocation(it) != null }
    }

    /**
     * チームに対応する旗の位置を取得
     */
    fun getFlagLocation(team: Team): Location? = when(team) {
        Team.RED -> redFlagLocation
        Team.BLUE -> blueFlagLocation
        Team.GREEN -> greenFlagLocation
        Team.YELLOW -> yellowFlagLocation
        else -> null
    }

    /**
     * チームに対応する有効なスポーン地点を取得
     */
    fun getEffectiveSpawn(team: Team): Location? = when(team) {
        Team.RED -> getEffectiveRedSpawn()
        Team.BLUE -> getEffectiveBlueSpawn()
        Team.GREEN -> greenSpawnLocations.randomOrNull() ?: greenSpawnLocation ?: greenFlagLocation
        Team.YELLOW -> yellowSpawnLocations.randomOrNull() ?: yellowSpawnLocation ?: yellowFlagLocation
        else -> null
    }

    /**
     * チームに対応するすべてのスポーン地点を取得
     */
    fun getAllSpawnLocations(team: Team): List<Location> = when(team) {
        Team.RED -> getAllRedSpawnLocations()
        Team.BLUE -> getAllBlueSpawnLocations()
        Team.GREEN -> {
            val locs = mutableListOf<Location>()
            locs.addAll(greenSpawnLocations)
            greenSpawnLocation?.let { if (it !in locs) locs.add(it) }
            if (locs.isEmpty()) greenFlagLocation?.let { locs.add(it) }
            locs
        }
        Team.YELLOW -> {
            val locs = mutableListOf<Location>()
            locs.addAll(yellowSpawnLocations)
            yellowSpawnLocation?.let { if (it !in locs) locs.add(it) }
            if (locs.isEmpty()) yellowFlagLocation?.let { locs.add(it) }
            locs
        }
        else -> emptyList()
    }
    
    /**
     * スポーン地点が設定されていない場合、旗位置をスポーン地点として使用
     * 複数スポーン地点がある場合はランダムに選択
     */
    fun getEffectiveRedSpawn(): Location? {
        // 複数スポーン地点がある場合はランダムに選択
        if (redSpawnLocations.isNotEmpty()) {
            return redSpawnLocations.random()
        }
        // 後方互換性: 単一スポーン地点または旗位置を使用
        return redSpawnLocation ?: redFlagLocation
    }
    
    fun getEffectiveBlueSpawn(): Location? {
        // 複数スポーン地点がある場合はランダムに選択
        if (blueSpawnLocations.isNotEmpty()) {
            return blueSpawnLocations.random()
        }
        // 後方互換性: 単一スポーン地点または旗位置を使用
        return blueSpawnLocation ?: blueFlagLocation
    }
    
    /**
     * すべてのスポーン地点を取得（複数スポーン地点と単一スポーン地点を統合）
     */
    fun getAllRedSpawnLocations(): List<Location> {
        val locations = mutableListOf<Location>()
        locations.addAll(redSpawnLocations)
        redSpawnLocation?.let { if (it !in locations) locations.add(it) }
        if (locations.isEmpty()) {
            redFlagLocation?.let { locations.add(it) }
        }
        return locations
    }
    
    fun getAllBlueSpawnLocations(): List<Location> {
        val locations = mutableListOf<Location>()
        locations.addAll(blueSpawnLocations)
        blueSpawnLocation?.let { if (it !in locations) locations.add(it) }
        if (locations.isEmpty()) {
            blueFlagLocation?.let { locations.add(it) }
        }
        return locations
    }
    
    /**
     * 新しいスポーン地点が既存のスポーン地点と近すぎないかチェック
     * @param newLocation 新しいスポーン地点
     * @param isRedTeam 赤チームかどうか
     * @return エラーメッセージ（問題ない場合はnull）
     */
    fun validateSpawnDistance(newLocation: Location, isRedTeam: Boolean): String? {
        val minSpawnDistance = 4.0  // 最低4ブロック離す（3x3の範囲が重複しないため）
        val existingSpawns = if (isRedTeam) getAllRedSpawnLocations() else getAllBlueSpawnLocations()
        
        existingSpawns.forEachIndexed { index, spawn ->
            val distance = newLocation.distance(spawn)
            if (distance < minSpawnDistance) {
                return "スポーン地点が既存の地点${index + 1}と近すぎます（${String.format("%.1f", distance)}ブロック）。最低${minSpawnDistance}ブロック離してください"
            }
        }
        
        return null
    }
    
    /**
     * 設定のコピーを作成（更新時に使用）
     */
    fun copy(): GameConfig = GameConfig(
        name = name,
        world = world,
        redFlagLocation = redFlagLocation?.clone(),
        redSpawnLocation = redSpawnLocation?.clone(),
        blueFlagLocation = blueFlagLocation?.clone(),
        blueSpawnLocation = blueSpawnLocation?.clone(),
        redSpawnLocations = redSpawnLocations.map { it.clone() }.toMutableList(),
        blueSpawnLocations = blueSpawnLocations.map { it.clone() }.toMutableList(),
        greenFlagLocation = greenFlagLocation?.clone(),
        greenSpawnLocation = greenSpawnLocation?.clone(),
        greenSpawnLocations = greenSpawnLocations.map { it.clone() }.toMutableList(),
        yellowFlagLocation = yellowFlagLocation?.clone(),
        yellowSpawnLocation = yellowSpawnLocation?.clone(),
        yellowSpawnLocations = yellowSpawnLocations.map { it.clone() }.toMutableList(),
        teamCount = teamCount,
        autoStartEnabled = autoStartEnabled,
        minPlayers = minPlayers,
        maxPlayersPerTeam = maxPlayersPerTeam,
        respawnDelayBase = respawnDelayBase,
        respawnDelayPerDeath = respawnDelayPerDeath,
        respawnDelayMax = respawnDelayMax,
        buildDuration = buildDuration,
        buildPhaseGameMode = buildPhaseGameMode,
        combatDuration = combatDuration,
        resultDuration = resultDuration,
        intermediateDuration = intermediateDuration,
        buildPhaseBlocks = buildPhaseBlocks,
        combatPhaseBlocks = combatPhaseBlocks,
        matchMode = matchMode,
        matchTarget = matchTarget,
        createdAt = createdAt
    )

    companion object {
        /**
         * config.ymlのデフォルト値を使ってGameConfigを生成
         */
        fun createWithDefaults(name: String, world: World, plugin: org.bukkit.plugin.java.JavaPlugin): GameConfig {
            val cfg = plugin.config
            return GameConfig(
                name = name,
                world = world,
                autoStartEnabled = cfg.getBoolean("default-game.auto-start-enabled", false),
                minPlayers = cfg.getInt("default-game.min-players", 2),
                maxPlayersPerTeam = cfg.getInt("default-game.max-players-per-team", 10),
                respawnDelayBase = cfg.getInt("default-game.respawn-delay-base", 0),
                respawnDelayPerDeath = cfg.getInt("default-game.respawn-delay-per-death", 0),
                respawnDelayMax = cfg.getInt("default-game.respawn-delay-max", 0),
                buildDuration = cfg.getInt("default-phases.build-duration", 180),
                buildPhaseGameMode = cfg.getString("default-phases.build-phase-gamemode", "SURVIVAL") ?: "SURVIVAL",
                combatDuration = cfg.getInt("default-phases.combat-duration", 420),
                resultDuration = cfg.getInt("default-phases.result-duration", 15),
                intermediateDuration = cfg.getInt("default-phases.intermediate-result-duration", 15),
                buildPhaseBlocks = cfg.getInt("default-phases.build-phase-blocks", 16),
                combatPhaseBlocks = cfg.getInt("default-phases.combat-phase-blocks", 16)
            )
        }
    }
}