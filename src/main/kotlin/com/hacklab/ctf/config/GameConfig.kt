package com.hacklab.ctf.config

import com.hacklab.ctf.utils.MatchMode
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
    var redSpawnLocation: Location? = null,
    var blueFlagLocation: Location? = null,
    var blueSpawnLocation: Location? = null,
    
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
    var matchIntervalDuration: Int = 30,
    
    // その他
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 必須設定が完了しているかチェック
     */
    fun isValid(): Boolean {
        return redFlagLocation != null && blueFlagLocation != null
    }
    
    /**
     * スポーン地点が設定されていない場合、旗位置をスポーン地点として使用
     */
    fun getEffectiveRedSpawn(): Location? = redSpawnLocation ?: redFlagLocation
    fun getEffectiveBlueSpawn(): Location? = blueSpawnLocation ?: blueFlagLocation
    
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
        matchIntervalDuration = matchIntervalDuration,
        createdAt = createdAt
    )
}