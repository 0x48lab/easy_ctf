package com.hacklab.ctf

import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import com.hacklab.ctf.utils.MatchMode
import com.hacklab.ctf.shop.ShopCategory
import com.hacklab.ctf.shop.ShopItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.block.BlockFace
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import com.hacklab.ctf.managers.GameTaskScheduler
import com.hacklab.ctf.managers.GameScoreboardManager
import com.hacklab.ctf.combat.DamageHealingSystem
import com.hacklab.ctf.managers.CurrencyManager
import com.hacklab.ctf.block.BlockConnectionSystem
import com.hacklab.ctf.flag.FlagSystem
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import java.time.Duration
import java.util.*

class Game(
    val gameName: String,
    val plugin: Main,
    var world: World  // var に変更してテンポラリワールドを設定可能に
) {
    // テンポラリワールド
    private var tempWorld: World? = null
    private var originalWorld: World = world  // 元のワールドを保持
    var state = GameState.WAITING
    var phase = GamePhase.BUILD
    
    // マッチシステム参照
    var matchWrapper: MatchWrapper? = null
    private var gameEndCallback: ((Team?) -> Unit)? = null
    
    // チーム管理（汎化: 最大4チーム対応）
    var teamCount = 2 // ゲーム作成時に設定（2〜4）
    val teams = mutableMapOf<Team, MutableSet<UUID>>() // チーム -> プレイヤーUUID
    val spectators = mutableSetOf<UUID>() // 観戦者リスト
    val disconnectedPlayers = mutableMapOf<UUID, Team>() // 切断中のプレイヤー

    // 後方互換プロパティ（既存コードとの互換性を保つ）
    val redTeam: MutableSet<UUID> get() = teams.getOrPut(Team.RED) { mutableSetOf() }
    val blueTeam: MutableSet<UUID> get() = teams.getOrPut(Team.BLUE) { mutableSetOf() }

    /** このゲームで有効なチーム一覧 */
    fun getActiveTeams(): List<Team> = Team.getActiveTeams(teamCount)

    /** チーム関連のマップを動的に初期化 */
    fun initTeams() {
        getActiveTeams().forEach { team ->
            teams.getOrPut(team) { mutableSetOf() }
            score.getOrPut(team) { 0 }
            teamCurrency.getOrPut(team) { 0 }
        }
    }

    // 位置設定（汎化）
    val flagLocations = mutableMapOf<Team, Location>() // チーム -> 旗位置
    val spawnLocations = mutableMapOf<Team, Location>() // チーム -> スポーン位置
    private var mapCenterLocation: Location? = null

    // 後方互換プロパティ（位置）
    var redFlagLocation: Location?
        get() = flagLocations[Team.RED]
        set(value) { if (value != null) flagLocations[Team.RED] = value else flagLocations.remove(Team.RED) }
    var blueFlagLocation: Location?
        get() = flagLocations[Team.BLUE]
        set(value) { if (value != null) flagLocations[Team.BLUE] = value else flagLocations.remove(Team.BLUE) }
    var redSpawnLocation: Location?
        get() = spawnLocations[Team.RED]
        set(value) { if (value != null) spawnLocations[Team.RED] = value else spawnLocations.remove(Team.RED) }
    var blueSpawnLocation: Location?
        get() = spawnLocations[Team.BLUE]
        set(value) { if (value != null) spawnLocations[Team.BLUE] = value else spawnLocations.remove(Team.BLUE) }
    
    
    // 設定
    var autoStartEnabled = plugin.config.getBoolean("default-game.auto-start-enabled", false)
    var minPlayers = plugin.config.getInt("default-game.min-players", 2)
    var maxPlayersPerTeam = plugin.config.getInt("default-game.max-players-per-team", 10)
    var buildDuration = plugin.config.getInt("default-phases.build-duration", 120)
    var combatDuration = plugin.config.getInt("default-phases.combat-duration", 120)
    var resultDuration = plugin.config.getInt("default-phases.result-duration", 15)
    var intermediateDuration = plugin.config.getInt("default-phases.intermediate-result-duration", 15)
    var buildPhaseGameMode = plugin.config.getString("default-phases.build-phase-gamemode", "SURVIVAL")!!
    var buildPhaseBlocks = 16
    var combatPhaseBlocks = 16
    
    // ゲーム状態
    var score = mutableMapOf<Team, Int>()
    var currentPhaseTime = 0
    var autoStartCountdown = -1

    // 通貨管理（マッチがない場合用）
    val teamCurrency = mutableMapOf<Team, Int>()

    // 旗管理（汎化）
    val flagCarriers = mutableMapOf<Team, UUID?>() // チーム -> キャリアUUID
    val droppedFlags = mutableMapOf<Location, Pair<Team, Long>>() // 位置 -> (チーム, ドロップ時刻)

    // 後方互換プロパティ（旗キャリア）
    var redFlagCarrier: UUID?
        get() = flagCarriers[Team.RED]
        set(value) { flagCarriers[Team.RED] = value }
    var blueFlagCarrier: UUID?
        get() = flagCarriers[Team.BLUE]
        set(value) { flagCarriers[Team.BLUE] = value }

    // 旗がドロップされているかどうか
    val isRedFlagDropped: Boolean
        get() = droppedFlags.any { it.value.first == Team.RED }
    val isBlueFlagDropped: Boolean
        get() = droppedFlags.any { it.value.first == Team.BLUE }

    /** 指定チームの旗がドロップされているか */
    fun isFlagDropped(team: Team): Boolean = droppedFlags.any { it.value.first == team }
    
    // UI要素は scoreboardManager に委譲
    val bossBar: BossBar? get() = scoreboardManager.bossBar
    
    // スポーン保護
    val spawnProtection = mutableMapOf<UUID, Long>() // プレイヤー -> 保護終了時刻
    
    // プレイヤー統計
    val playerDeaths = mutableMapOf<UUID, Int>() // プレイヤー -> 死亡回数
    val playerKills = mutableMapOf<UUID, Int>() // プレイヤー -> キル数
    val killStreaks = mutableMapOf<UUID, Int>() // プレイヤー -> 現在のキルストリーク
    val playerCaptures = mutableMapOf<UUID, Int>() // プレイヤー -> 旗キャプチャー数
    val playerFlagPickups = mutableMapOf<UUID, Int>() // プレイヤー -> 旗取得数
    val playerFlagDefends = mutableMapOf<UUID, Int>() // プレイヤー -> 旗キャリアキル数（防衛）
    val playerMoneySpent = mutableMapOf<UUID, Int>() // プレイヤー -> 使用金額
    val playerBlocksPlaced = mutableMapOf<UUID, Int>() // プレイヤー -> ブロック設置数
    val playerAssists = mutableMapOf<UUID, Int>() // プレイヤー -> アシスト数
    
    // アシスト管理
    val damageTracking = mutableMapOf<UUID, MutableMap<UUID, Long>>() // 被害者 -> (攻撃者 -> 最終ダメージ時刻)
    val captureAssists = mutableMapOf<Team, MutableSet<UUID>>() // チーム -> キャプチャーに貢献したプレイヤー
    
    // ActionBar 管理は scoreboardManager に委譲
    
    // ブロック設置制限管理
    val teamPlacedBlocks = mutableMapOf<Team, MutableSet<Location>>() // チーム -> 設置したブロックの座標
    
    // ブロック接続ツリー管理
    data class BlockNode(
        val location: Location,
        val parent: Location?,
        val children: MutableSet<Location> = mutableSetOf()
    )
    val teamBlockTrees = mutableMapOf<Team, MutableMap<Location, BlockNode>>() // チーム -> (座標 -> ノード)
    
    // タスク管理（一元化）
    val taskScheduler = GameTaskScheduler(plugin)

    // UI管理（スコアボード、BossBar、ActionBar）
    val scoreboardManager = GameScoreboardManager(this, plugin)

    // ブロック接続システム
    val blockSystem = BlockConnectionSystem(this, plugin)

    // 旗システム
    val damageHealingSystem = DamageHealingSystem(this, plugin)
    val flagSystem = FlagSystem(this, plugin)

    // 通貨管理
    val currencyManager = CurrencyManager(this, plugin)
    
    // イベントチェスト関連
    private val eventChestLocations = mutableListOf<Location>()
    // イベントチェスト関連の位置データ（タスクは taskScheduler で管理）
    private var eventChestSpawnCount = 0
    
    // ゲッター
    val name: String get() = gameName
    
    // チームカラーのブロック定義
    companion object {
        val TEAM_BLOCKS = Team.playableTeams().associateWith { setOf(it.getConcreteMaterial()) }

        val NEUTRAL_BLOCK = Material.WHITE_CONCRETE // 接続が切れたブロック
    }
    
    fun setMatchContext(matchWrapper: MatchWrapper) {
        this.matchWrapper = matchWrapper
    }
    
    fun setGameEndCallback(callback: (Team?) -> Unit) {
        this.gameEndCallback = callback
    }
    
    fun getPlayers(): Set<Player> = getAllPlayers()
    fun getPlayerTeam(player: Player): Team? = getPlayerTeam(player.uniqueId)
    fun getTeamPlayers(team: Team): Set<Player> {
        val uuids = if (team == Team.SPECTATOR) spectators else teams[team] ?: emptySet()
        return uuids.mapNotNull { Bukkit.getPlayer(it) }.toSet()
    }

    fun getTeamSize(team: Team): Int = teams[team]?.size ?: 0

    fun getRedScore(): Int = score[Team.RED] ?: 0
    fun getBlueScore(): Int = score[Team.BLUE] ?: 0
    fun getTeamScore(team: Team): Int = score[team] ?: 0

    /** 全チームのスコアを "赤:2 青:1 緑:0" のような文字列で取得 */
    fun getScoreDisplayText(): String {
        return getActiveTeams().joinToString(" ") { team ->
            "${plugin.languageManager.getMessage("teams.${team.name.lowercase()}")}:${score[team] ?: 0}"
        }
    }

    fun getWinner(): Team? {
        val activeTeams = getActiveTeams()
        val maxScore = activeTeams.maxOfOrNull { score[it] ?: 0 } ?: return null
        val winners = activeTeams.filter { (score[it] ?: 0) == maxScore }
        return if (winners.size == 1) winners.first() else null // 同点なら引き分け
    }

    /** 汎用ゲッター */
    fun getFlagLocation(team: Team): Location? = flagLocations[team]
    fun getSpawnLocation(team: Team): Location? = spawnLocations[team]
    
    fun getRandomSpawnLocation(team: Team): Location? {
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(gameName)

        return when (team) {
            Team.RED -> {
                val locations = config?.getAllRedSpawnLocations() ?: listOfNotNull(spawnLocations[Team.RED])
                if (locations.isNotEmpty()) locations.random() else null
            }
            Team.BLUE -> {
                val locations = config?.getAllBlueSpawnLocations() ?: listOfNotNull(spawnLocations[Team.BLUE])
                if (locations.isNotEmpty()) locations.random() else null
            }
            Team.SPECTATOR -> null
            else -> {
                val locations = listOfNotNull(spawnLocations[team])
                if (locations.isNotEmpty()) locations.random() else null
            }
        }
    }
    fun getCenterLocation(): Location? = mapCenterLocation
    
    /** 汎用セッター */
    fun setFlagLocation(team: Team, location: Location) { flagLocations[team] = location }
    fun setSpawnLocation(team: Team, location: Location) { spawnLocations[team] = location }
    
    fun updateFromConfig(config: com.hacklab.ctf.config.GameConfig) {
        // 位置情報は後でワールド変換が必要なので、座標のみ保持
        redFlagLocation = config.redFlagLocation?.let { loc ->
            Location(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        blueFlagLocation = config.blueFlagLocation?.let { loc ->
            Location(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        redSpawnLocation = config.redSpawnLocation?.let { loc ->
            Location(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        blueSpawnLocation = config.blueSpawnLocation?.let { loc ->
            Location(world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        }
        buildDuration = config.buildDuration
        combatDuration = config.combatDuration
        resultDuration = config.resultDuration
        intermediateDuration = config.intermediateDuration
        buildPhaseGameMode = config.buildPhaseGameMode
        buildPhaseBlocks = config.buildPhaseBlocks
        combatPhaseBlocks = config.combatPhaseBlocks
        minPlayers = config.minPlayers
        maxPlayersPerTeam = config.maxPlayersPerTeam
        autoStartEnabled = config.autoStartEnabled
        
        // マップ中央位置を計算
        calculateMapCenter()
    }
    
    fun addPlayer(player: Player, team: Team? = null, isAdminAction: Boolean = false): Boolean {
        // ENDING状態では参加不可
        if (state == GameState.ENDING) {
            player.sendMessage(plugin.languageManager.getMessageAsComponent("game-states.already-ending"))
            return false
        }
        
        val selectedTeam = team ?: selectTeamForPlayer(player)
        
        val teamMembers = teams.getOrPut(selectedTeam) { mutableSetOf() }
        if (teamMembers.size >= maxPlayersPerTeam) {
            player.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.team-full",
                "team" to plugin.languageManager.getMessage("teams.${selectedTeam.name.lowercase()}")
            ))
            return false
        }
        teamMembers.add(player.uniqueId)
        
        // スコアボード表示
        setupScoreboard(player)
        
        // タブリストの色を更新（チーム参加時）
        updatePlayerTabColor(player)
        
        // メッセージを構築（レガシーカラーコードを適切に処理）
        val teamName = plugin.languageManager.getMessage("teams.${selectedTeam.name.lowercase()}")
        // カラーコードは&形式で渡す
        val colorCode = when (selectedTeam) {
            Team.RED -> "&c"
            Team.BLUE -> "&9"
            Team.SPECTATOR -> "&7"
            else -> selectedTeam.colorCode
        }
        player.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.game-joined", 
            "game" to gameName,
            "color" to colorCode,
            "team" to teamName
        ))
        
        // マッチに追加
        matchWrapper?.players?.put(player.uniqueId, player)
        
        // ゲーム実行中の場合の処理（途中参加）
        if (state == GameState.RUNNING || state == GameState.STARTING) {
            // インベントリをクリア（重要：途中参加時のアイテム持ち込みを防ぐ）
            player.inventory.clear()
            
            // 現在のフェーズに応じて装備を付与
            if (phase == GamePhase.BUILD) {
                plugin.equipmentManager.giveBuildPhaseEquipment(player)
            } else {
                plugin.equipmentManager.giveCombatPhaseEquipment(player, selectedTeam)
            }
            
            // スポーン地点にテレポート
            when (selectedTeam) {
                Team.RED -> {
                    redSpawnLocation?.let { spawn ->
                        player.teleport(spawn)
                    }
                }
                Team.BLUE -> {
                    blueSpawnLocation?.let { spawn ->
                        player.teleport(spawn)
                    }
                }
                else -> {}
            }
            
            // ゲームモードと飛行設定
            if (phase == GamePhase.BUILD) {
                player.gameMode = GameMode.valueOf(buildPhaseGameMode)
                player.allowFlight = true
            } else {
                player.gameMode = org.bukkit.GameMode.SURVIVAL
                player.allowFlight = false
                player.isFlying = false
            }

            // 途中参加を全プレイヤーに通知
            getAllPlayers().filter { it.uniqueId != player.uniqueId }.forEach { p ->
                p.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.mid-game-join",
                    "player" to player.name,
                    "color" to colorCode,
                    "team" to teamName))
            }
        }

        // 自動開始チェック（ゲーム実行中ではない場合のみ）
        if (state == GameState.WAITING) {
            checkAutoStart()
        }
        
        // スコアボードを更新（人数表示のため）
        updateScoreboard()
        
        return true
    }

    
    /**
     * プレイヤーのチームを変更する（管理者用）
     */
    fun changePlayerTeam(player: Player, newTeam: Team, isAdminAction: Boolean = false): Boolean {
        // プレイヤーが現在ゲームに参加しているか確認
        val currentTeam = getPlayerTeam(player.uniqueId)
        if (currentTeam == null) {
            return false
        }
        
        // 同じチームへの変更は無視
        if (currentTeam == newTeam) {
            return false
        }
        
        // 管理者権限でない場合、ゲーム実行中はチーム変更不可
        if (!isAdminAction && state != GameState.WAITING) {
            return false
        }
        
        // 新チームの人数チェック
        val targetTeamSize = if (newTeam == Team.SPECTATOR) 0 else getTeamSize(newTeam)

        if (newTeam != Team.SPECTATOR && targetTeamSize >= maxPlayersPerTeam) {
            return false
        }

        // 旗を持っている場合はドロップ
        val carriedFlagTeam = flagCarriers.entries.firstOrNull { it.value == player.uniqueId }?.key
        if (carriedFlagTeam != null) {
            dropFlag(player, carriedFlagTeam, player.location)
            flagCarriers[carriedFlagTeam] = null

            // 旗ドロップメッセージ
            getAllPlayers().forEach { p ->
                p.sendMessage(plugin.languageManager.getMessageAsComponent("flags.dropped-admin-team-change",
                    "player" to player.name,
                    "flag" to plugin.languageManager.getMessage("teams.${carriedFlagTeam.name.lowercase()}")
                ))
            }
        }

        // 現在のチームから削除
        if (currentTeam == Team.SPECTATOR) {
            spectators.remove(player.uniqueId)
        } else {
            teams[currentTeam]?.remove(player.uniqueId)
        }

        // 新しいチームに追加
        if (newTeam == Team.SPECTATOR) {
            spectators.add(player.uniqueId)
        } else {
            teams.getOrPut(newTeam) { mutableSetOf() }.add(player.uniqueId)
        }
        
        // ゲーム実行中の場合の処理
        if (state == GameState.RUNNING || state == GameState.STARTING) {
            // 死亡時と同様の処理（インベントリクリア、新装備付与）
            player.inventory.clear()
            
            // 新チームの装備を付与
            if (newTeam != Team.SPECTATOR) {
                if (phase == GamePhase.BUILD) {
                    plugin.equipmentManager.giveBuildPhaseEquipment(player)
                } else {
                    plugin.equipmentManager.giveCombatPhaseEquipment(player, newTeam)
                }
            } else {
                // 観戦者モードの設定
                player.gameMode = org.bukkit.GameMode.SPECTATOR
            }
            
            // 新チームのスポーン地点にテレポート
            when (newTeam) {
                Team.RED -> {
                    redSpawnLocation?.let { spawn ->
                        player.teleport(spawn)
                    }
                }
                Team.BLUE -> {
                    blueSpawnLocation?.let { spawn ->
                        player.teleport(spawn)
                    }
                }
                Team.SPECTATOR -> {
                    // 観戦者はマップの中心へ
                    val spawnLoc = redSpawnLocation ?: blueSpawnLocation ?: mapCenterLocation
                    spawnLoc?.let { loc ->
                        player.teleport(findSafeSpectatorLocation(loc))
                    }
                }
                else -> {
                    spawnLocations[newTeam]?.let { spawn ->
                        player.teleport(spawn)
                    }
                }
            }
            
            // ゲームモードを設定
            if (newTeam != Team.SPECTATOR) {
                player.gameMode = if (phase == GamePhase.BUILD) {
                    GameMode.valueOf(buildPhaseGameMode)
                } else {
                    org.bukkit.GameMode.SURVIVAL
                }
            }
            
            // ショップ購入履歴をリセット（オプション）
            // shopManager.resetPlayerPurchases(player)
        }
        
        // タブリストの色を更新
        updatePlayerTabColor(player)
        
        // スコアボードを更新
        updateScoreboard()
        
        return true
    }
    
    fun addSpectator(player: Player): Boolean {
        spectators.add(player.uniqueId)
        
        // 観戦者モードの設定
        player.gameMode = GameMode.SPECTATOR
        
        // スコアボード表示
        setupScoreboard(player)
        
        // 観戦者用アイテムを配布
        giveSpectatorItems(player)
        
        player.sendMessage(plugin.languageManager.getMessageAsComponent("spectator.joined"))
        
        // マッチに追加
        matchWrapper?.players?.put(player.uniqueId, player)
        
        // 中央にテレポート（ゲーム実行中の場合）
        if (state == GameState.RUNNING || state == GameState.STARTING) {
            getCenterLocation()?.let { center ->
                player.teleport(center)
            }
        }
        
        // スコアボードを更新
        updateScoreboard()
        
        return true
    }
    
    fun switchToSpectator(player: Player) {
        // 既存のチームから削除
        val team = getPlayerTeam(player.uniqueId)
        teams[team]?.remove(player.uniqueId)

        // 旗を持っていた場合
        val carriedFlagTeam = flagCarriers.entries.firstOrNull { it.value == player.uniqueId }?.key
        if (carriedFlagTeam != null) {
            dropFlag(player, carriedFlagTeam)
        }
        
        // 観戦者として追加
        spectators.add(player.uniqueId)
        player.gameMode = GameMode.SPECTATOR
        
        // 観戦者用アイテムを配布
        giveSpectatorItems(player)
        
        player.sendMessage(plugin.languageManager.getMessageAsComponent("spectator.switched"))
        
        // タブリストの色を更新
        scoreboardManager.updatePlayerTeamColor(player, player.scoreboard)
        
        // スコアボードを更新
        updateScoreboard()
    }
    
    fun removePlayer(player: Player) {
        // 全チームから削除
        teams.values.forEach { it.remove(player.uniqueId) }
        spectators.remove(player.uniqueId)
        disconnectedPlayers.remove(player.uniqueId)

        // 他のプレイヤーのスコアボードからこのプレイヤーを削除
        getAllPlayers().forEach { otherPlayer ->
            val scoreboard = otherPlayer.scoreboard
            if (scoreboard != Bukkit.getScoreboardManager().mainScoreboard) {
                scoreboard.teams.forEach { team ->
                    if (team.hasEntry(player.name)) {
                        team.removeEntry(player.name)
                    }
                }
            }
        }

        // UI削除
        scoreboardManager.resetScoreboard(player)
        scoreboardManager.removePlayerFromBossBar(player)

        // 旗を持っていた場合
        val carriedFlagTeam = flagCarriers.entries.firstOrNull { it.value == player.uniqueId }?.key
        if (carriedFlagTeam != null) {
            dropFlag(player, carriedFlagTeam)
        }
        
        player.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.game-left", "game" to gameName))

        // ゲーム中に離脱した場合、テンポラリワールドから元のワールドに戻してCREATIVEに復帰
        if (state == GameState.RUNNING && tempWorld != null && player.world == tempWorld) {
            player.inventory.clear()
            player.gameMode = GameMode.CREATIVE
            player.teleport(originalWorld.spawnLocation)
        } else if (state == GameState.RUNNING) {
            // テンポラリワールド外でもゲーム中ならCREATIVEに設定
            player.gameMode = GameMode.CREATIVE
        }

        // マッチから削除
        matchWrapper?.players?.remove(player.uniqueId)
        
        // スコアボードを更新（人数表示のため）
        updateScoreboard()
        
        // 自動開始のチェック（プレイヤー数が減った場合、カウントダウンをキャンセル）
        if (state == GameState.WAITING && autoStartEnabled) {
            val totalPlayers = getActiveTeams().sumOf { getTeamSize(it) }
            if (totalPlayers < minPlayers && autoStartCountdown > 0) {
                autoStartCountdown = -1
                taskScheduler.cancelNamed(GameTaskScheduler.AUTO_START)
                getAllPlayers().forEach {
                    it.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.auto-start-cancelled"))
                }
            }
        }
    }

    fun handleDisconnect(player: Player) {
        val team = getPlayerTeam(player.uniqueId) ?: return

        // 切断中のプレイヤーとして記録（チーム情報を保持）
        disconnectedPlayers[player.uniqueId] = team

        // チームから一時的に除外（スコアボードの人数に含まれないように）
        teams[team]?.remove(player.uniqueId)

        // 旗を持っていた場合
        val carriedFlagTeam = flagCarriers.entries.firstOrNull { it.value == player.uniqueId }?.key
        if (carriedFlagTeam != null) {
            dropFlag(player, carriedFlagTeam)
        }

        // スコアボードを更新
        updateScoreboard()

        // 自動開始のチェック（プレイヤー数が減った場合、カウントダウンをキャンセル）
        if (state == GameState.WAITING && autoStartEnabled) {
            val totalPlayers = getActiveTeams().sumOf { getTeamSize(it) }
            if (totalPlayers < minPlayers && autoStartCountdown > 0) {
                autoStartCountdown = -1
                taskScheduler.cancelNamed(GameTaskScheduler.AUTO_START)
                getAllPlayers().forEach {
                    it.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.auto-start-cancelled"))
                }
            }
        }
    }
    
    fun handleReconnect(player: Player) {
        val team = disconnectedPlayers.remove(player.uniqueId) ?: return
        
        // プレイヤーを再度チームに追加
        when (team) {
            Team.RED -> redTeam.add(player.uniqueId)
            Team.BLUE -> blueTeam.add(player.uniqueId)
            Team.SPECTATOR -> spectators.add(player.uniqueId)
            else -> {} // GREEN/YELLOW: 未実装
        }
        
        // スコアボードを更新
        updateScoreboard()
        
        // UIの再設定
        setupScoreboard(player)
        scoreboardManager.addPlayerToBossBar(player)
        
        // 現在のフェーズに応じた装備・ゲームモード・飛行設定
        when (phase) {
            GamePhase.BUILD -> {
                giveBuildPhaseItems(player, team)
                player.gameMode = GameMode.valueOf(buildPhaseGameMode)
                player.allowFlight = true
            }
            GamePhase.COMBAT -> {
                giveCombatPhaseItems(player, team)
                player.gameMode = GameMode.SURVIVAL
                player.allowFlight = false
                player.isFlying = false
            }
        }

        // スポーン地点に転送
        teleportToSpawn(player, team)
    }
    
    private fun selectTeamForPlayer(player: Player): Team {
        val teamPlayers = getActiveTeams().associateWith { team ->
            (teams[team] ?: emptySet()).mapNotNull { plugin.server.getPlayer(it) }
        }
        return plugin.teamBalancer.selectTeamForPlayer(player, teamPlayers)
    }
    
    fun getPlayerTeam(uuid: UUID): Team? {
        for ((team, members) in teams) {
            if (members.contains(uuid)) return team
        }
        if (spectators.contains(uuid)) return Team.SPECTATOR
        return null
    }

    fun getAllPlayers(): Set<Player> {
        val allUuids = teams.values.flatten().toMutableSet()
        allUuids.addAll(spectators)
        return allUuids.mapNotNull { Bukkit.getPlayer(it) }.toSet()
    }
    
    fun getTeamPlayers(): Set<Player> {
        // チームプレイヤーのみ（観戦者を除外）
        return (redTeam + blueTeam).mapNotNull { Bukkit.getPlayer(it) }.toSet()
    }
    
    private fun checkAutoStart() {
        // 自動開始が無効な場合は何もしない
        if (!autoStartEnabled) return

        if (state != GameState.WAITING) return
        if (getActiveTeams().sumOf { getTeamSize(it) } < minPlayers) {
            if (autoStartCountdown > 0) {
                autoStartCountdown = -1
                taskScheduler.cancelNamed(GameTaskScheduler.AUTO_START)
                getAllPlayers().forEach {
                    it.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.auto-start-cancelled"))
                }
            }
            return
        }

        if (autoStartCountdown < 0 && taskScheduler.getNamed(GameTaskScheduler.AUTO_START) == null) {
            autoStartCountdown = 30
            val task = object : BukkitRunnable() {
                override fun run() {
                    if (autoStartCountdown <= 0 || state != GameState.WAITING) {
                        cancel()
                        taskScheduler.cancelNamed(GameTaskScheduler.AUTO_START)
                        // プレイヤー数を再チェックしてから開始
                        if (autoStartCountdown == 0 && getActiveTeams().sumOf { getTeamSize(it) } >= minPlayers) {
                            start()
                        }
                        return
                    }

                    if (getActiveTeams().sumOf { getTeamSize(it) } < minPlayers) {
                        autoStartCountdown = -1
                        cancel()
                        taskScheduler.cancelNamed(GameTaskScheduler.AUTO_START)
                        getAllPlayers().forEach {
                            it.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.auto-start-cancelled"))
                        }
                        return
                    }

                    if (autoStartCountdown % 10 == 0 || autoStartCountdown <= 5) {
                        getAllPlayers().forEach {
                            it.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.countdown-message", "seconds" to autoStartCountdown.toString()))
                        }
                    }

                    autoStartCountdown--

                    // スコアボード更新（カウントダウン表示のため）
                    updateScoreboard()
                }
            }
            taskScheduler.scheduleRepeating(GameTaskScheduler.AUTO_START, 0L, 20L, task)
        }
    }
    
    fun start(): Boolean {
        plugin.logger.info(plugin.languageManager.getMessage("log.game-starting", "game" to name, "state" to state.toString(), "phase" to phase.toString()))

        // チーム関連マップを動的に初期化
        initTeams()

        // 自動開始タスクをキャンセル（手動開始の場合も含む）
        taskScheduler.cancelNamed(GameTaskScheduler.AUTO_START)
        autoStartCountdown = -1
        
        if (state != GameState.WAITING) {
            plugin.logger.warning(plugin.languageManager.getMessage("log.game-cannot-start", "game" to name, "state" to state.toString()))
            getAllPlayers().forEach {
                it.sendMessage(plugin.languageManager.getMessageAsComponent("game-states.already-state", "state" to state.toString()))
            }
            return false
        }
        
        // 最小人数チェック（マッチの場合は最小人数チェックをスキップ）
        if (matchWrapper == null && getActiveTeams().sumOf { getTeamSize(it) } < minPlayers) {
            getAllPlayers().forEach {
                it.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.min-players-required", "min" to minPlayers.toString()))
            }
            return false
        }
        
        // 必須設定チェック
        if (redFlagLocation == null || blueFlagLocation == null) {
            getAllPlayers().forEach {
                it.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.flags-not-set"))
            }
            return false
        }
        
        state = GameState.STARTING
        phase = GamePhase.BUILD
        currentPhaseTime = buildDuration

        // 非参加プレイヤーへのゲーム開始通知（クリック可能な参加ボタン付き）
        val gamePlayers = (redTeam + blueTeam + spectators)
        Bukkit.getOnlinePlayers().filter { it.uniqueId !in gamePlayers }.forEach { onlinePlayer ->
            val announcement = plugin.languageManager.getMessageAsComponent("notification.game-starting", "game" to gameName)
            val gameInfo = plugin.languageManager.getMessageAsComponent("notification.game-info",
                "game" to gameName, "build" to buildDuration.toString(), "combat" to combatDuration.toString())
            val joinButton = plugin.languageManager.getMessageAsComponent("notification.join-button")
                .clickEvent(ClickEvent.runCommand("/ctf join $gameName"))
                .hoverEvent(HoverEvent.showText(
                    plugin.languageManager.getMessageAsComponent("notification.join-hover", "game" to gameName)
                ))

            onlinePlayer.sendMessage(announcement)
            onlinePlayer.sendMessage(gameInfo.append(Component.text(" ")).append(joinButton))
        }

        // 初回のゲーム開始時、またはマッチの最初のゲーム、またはテンポラリワールドが無効な場合は新規作成
        plugin.logger.info("[Game] Checking if new world needed: tempWorld=${tempWorld?.name}, matchWrapper=${matchWrapper != null}, gameNumber=${matchWrapper?.currentGameNumber}")
        val needNewWorld = tempWorld == null || 
                          (matchWrapper == null && tempWorld != null) || // 通常ゲームの再開始時
                          (matchWrapper != null && matchWrapper!!.currentGameNumber == 1) // マッチの最初のゲーム
        plugin.logger.info("[Game] needNewWorld=$needNewWorld")
        
        if (needNewWorld) {
            // 既存のテンポラリワールドがあれば削除
            if (tempWorld != null) {
                val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
                plugin.logger.info("[Game] Deleting existing temporary world before creating new one")
                worldManager.deleteWorld(tempWorld!!.name)
                tempWorld = null
            }
            
            // テンポラリワールドを作成
            val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
            tempWorld = worldManager.createTempWorld(gameName)
            
            if (tempWorld == null) {
                plugin.logger.warning(plugin.languageManager.getMessage("log.temp-world-failed"))
                getAllPlayers().forEach {
                    it.sendMessage(plugin.languageManager.getMessageAsComponent("join-leave.temp-world-failed"))
                }
                state = GameState.WAITING
                return false
            }
            plugin.logger.info("[Game] Created new temporary world: ${tempWorld!!.name}")
            
        } else {
            plugin.logger.info("[Game] Reusing existing temporary world for match game ${matchWrapper?.currentGameNumber}")
        }
        
        // ワールドを切り替え（nullチェック付き）
        if (tempWorld == null) {
            plugin.logger.severe("[Game] Critical error: tempWorld is null after creation/reuse attempt")
            state = GameState.WAITING
            return false
        }
        world = tempWorld!!
        
        // マップを復元（マッチの最初のゲームのみ）
        if (matchWrapper == null || matchWrapper!!.currentGameNumber == 1) {
            val gameManager = plugin.gameManager
            val mapManager = com.hacklab.ctf.map.CompressedMapManager(plugin)
            
            // 保存されたマップがある場合は復元
            if (mapManager.hasMap(gameName)) {
                if (!gameManager.resetGameMap(gameName, tempWorld, this)) {
                    plugin.logger.warning(plugin.languageManager.getMessage("log.map-restore-failed"))
                } else {
                    plugin.logger.info(plugin.languageManager.getMessage("log.map-restored"))
                }
            } else {
                // マップが保存されていない場合、元のワールドから範囲をコピー
                plugin.logger.info(plugin.languageManager.getMessage("log.no-saved-map"))
                plugin.logger.info("Copying game area from original world to temporary world...")
                
                // 旗の位置から適切な範囲を決定（両チームの旗を含む範囲）
                if (redFlagLocation != null && blueFlagLocation != null) {
                    val minX = kotlin.math.min(redFlagLocation!!.blockX, blueFlagLocation!!.blockX) - 50
                    val maxX = kotlin.math.max(redFlagLocation!!.blockX, blueFlagLocation!!.blockX) + 50
                    val minZ = kotlin.math.min(redFlagLocation!!.blockZ, blueFlagLocation!!.blockZ) - 50
                    val maxZ = kotlin.math.max(redFlagLocation!!.blockZ, blueFlagLocation!!.blockZ) + 50
                    // Y座標は旗の位置を基準に上下30ブロック程度に限定
                    val flagY = (redFlagLocation!!.blockY + blueFlagLocation!!.blockY) / 2
                    val minY = kotlin.math.max(flagY - 30, -64)  // 1.18以降の最小Y座標
                    val maxY = kotlin.math.min(flagY + 30, 319)  // 1.18以降の最大Y座標
                    
                    // 元のワールドからテンポラリワールドにブロックをコピー
                    plugin.logger.info("Starting block copy from original world to temporary world...")
                    var copiedBlocks = 0
                    val startTime = System.currentTimeMillis()
                    
                    for (x in minX..maxX) {
                        for (z in minZ..maxZ) {
                            for (y in minY..maxY) {
                                val sourceBlock = originalWorld.getBlockAt(x, y, z)
                                // AIRブロックはスキップして高速化
                                if (sourceBlock.type != Material.AIR) {
                                    val targetBlock = tempWorld!!.getBlockAt(x, y, z)
                                    targetBlock.type = sourceBlock.type
                                    targetBlock.blockData = sourceBlock.blockData.clone()
                                }
                                copiedBlocks++
                            }
                        }
                        // 進捗をログ出力（X座標ごと）
                        if ((x - minX) % 10 == 0) {
                            val progress = ((x - minX).toFloat() / (maxX - minX) * 100).toInt()
                            plugin.logger.info("Block copy progress: $progress%")
                        }
                    }
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    plugin.logger.info("Block copy completed: $copiedBlocks blocks in ${elapsed}ms")
                    plugin.logger.info("Copied area from ($minX, $minY, $minZ) to ($maxX, $maxY, $maxZ)")
                } else {
                    plugin.logger.warning("Flag locations not set, cannot determine area to copy")
                }
            }
        } else {
            plugin.logger.info(plugin.languageManager.getMessage("log.skip-map-restore"))
        }
        
        // 位置情報をテンポラリワールドに更新
        plugin.logger.info("[Game] Updating locations to temporary world: ${tempWorld!!.name}")
        plugin.logger.info("[Game] Before update - Red flag world: ${redFlagLocation?.world?.name}")
        updateLocationsToWorld(tempWorld!!)
        plugin.logger.info("[Game] After update - Red flag world: ${redFlagLocation?.world?.name}")
        
        // 通貨を初期化
        initializeCurrency()
        
        // BossBar作成
        scoreboardManager.createBossBar()
        scoreboardManager.bossBar?.setTitle(plugin.languageManager.getMessage("phase-extended.build-time-format", "time" to formatTime(currentPhaseTime)))
        scoreboardManager.bossBar?.color = BarColor.GREEN
        
        // プレイヤー統計をリセット
        playerDeaths.clear()
        playerKills.clear()
        killStreaks.clear()
        playerCaptures.clear()
        playerFlagPickups.clear()
        playerFlagDefends.clear()
        playerMoneySpent.clear()
        playerBlocksPlaced.clear()
        playerAssists.clear()
        
        // チャンクの事前読み込み
        preloadSpawnChunks()
        
        // プレイヤーの準備（バッチ処理で安全にテレポート）
        val allPlayers = getAllPlayers().toList()

        // 全プレイヤーのインベントリをクリア（アイテム持ち込みを防ぐ）
        allPlayers.forEach { player ->
            player.inventory.clear()
            // エフェクトもクリア
            player.activePotionEffects.forEach { effect ->
                player.removePotionEffect(effect.type)
            }
            // 体力と満腹度をリセット
            player.health = player.maxHealth
            player.foodLevel = 20
            player.saturation = 5.0f
        }
        
        val redPlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.RED }
        val bluePlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.BLUE }
        val spectatorPlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.SPECTATOR }
        
        // チームごとに即座にテレポート実行
        // まず、スポーン地点に確実に地面があることを保証
        ensureSpawnPlatforms()
        
        plugin.logger.info("[Game] Starting immediate teleportation for ${redPlayers.size} red players")
        redPlayers.forEach { player ->
            val safeLocation = getSafeSpawnLocation(Team.RED)
            plugin.logger.info("[Game] Teleporting ${player.name} immediately to ${safeLocation}")
            player.teleport(safeLocation)
            player.gameMode = GameMode.valueOf(buildPhaseGameMode)
            player.allowFlight = true
            player.isFlying = false
            giveBuildPhaseItems(player, Team.RED)
            scoreboardManager.addPlayerToBossBar(player)
        }
        
        plugin.logger.info("[Game] Starting immediate teleportation for ${bluePlayers.size} blue players")
        bluePlayers.forEach { player ->
            val safeLocation = getSafeSpawnLocation(Team.BLUE)
            plugin.logger.info("[Game] Teleporting ${player.name} immediately to ${safeLocation}")
            player.teleport(safeLocation)
            player.gameMode = GameMode.valueOf(buildPhaseGameMode)
            player.allowFlight = true
            player.isFlying = false
            giveBuildPhaseItems(player, Team.BLUE)
            scoreboardManager.addPlayerToBossBar(player)
        }
        
        plugin.logger.info("[Game] Starting immediate teleportation for ${spectatorPlayers.size} spectator players")
        spectatorPlayers.forEach { player ->
            val centerLocation = getCenterLocation() ?: getSafeSpawnLocation(Team.RED)
            plugin.logger.info("[Game] Teleporting spectator ${player.name} immediately to ${centerLocation}")
            player.teleport(centerLocation)
            player.gameMode = GameMode.SPECTATOR
            scoreboardManager.addPlayerToBossBar(player)
        }
        
        // 全プレイヤーの初期化処理（テレポート後に実行）
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            getAllPlayers().forEach { player ->
                // タイトル表示
                player.showTitle(Title.title(
                plugin.languageManager.getMessageAsComponent("phase-extended.game-start-title"),
                plugin.languageManager.getMessageAsComponent("phase-extended.game-start-subtitle"),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(500)
                )
            ))
            }
        }, 10L) // 0.5秒後に実行
        
        // マッチモードでない場合、またはマッチの最初のゲームの場合のみブロック記録をクリア
        if (matchWrapper == null || matchWrapper!!.currentGameNumber == 1) {
            // ブロック設置記録をクリア
            teamPlacedBlocks.clear()
            getActiveTeams().forEach { teamPlacedBlocks[it] = mutableSetOf() }

            // ブロックツリーをクリア
            teamBlockTrees.clear()
            getActiveTeams().forEach { teamBlockTrees[it] = mutableMapOf() }
        }
        
        // 旗とスポーン地点の設置（ブロック記録クリア後に実行）
        plugin.logger.info("[Game] Setting up flags and spawns in world: ${world.name}")
        plugin.logger.info("[Game] Current world variable: ${world.name}, tempWorld: ${tempWorld?.name}")
        setupFlags()
        setupSpawnAreas()
        
        // ショップの購入履歴をリセット
        plugin.shopManager.clearPurchaseHistory(gameName)
        
        state = GameState.RUNNING
        
        plugin.logger.info(plugin.languageManager.getMessage("log.game-loop-started", "game" to name))
        
        // ゲームループ開始
        startGameLoop()
        
        return true
    }
    
    private fun startGameLoop() {
        // メインゲームループ
        val gameLoopTask = object : BukkitRunnable() {
            override fun run() {
                if (state != GameState.RUNNING) {
                    cancel()
                    return
                }

                // 時間更新
                currentPhaseTime--

                // ドロップした旗のチェック
                checkDroppedFlags()

                // ドロップした旗への接触チェック（戦闘フェーズのみ）
                if (phase == GamePhase.COMBAT) {
                    checkDroppedFlagTouch()
                }

                // フェーズ遷移チェック
                if (currentPhaseTime <= 0) {
                    when (phase) {
                        GamePhase.BUILD -> transitionToCombatPhase()
                        GamePhase.COMBAT -> handleCombatPhaseEnd()
                    }
                }

                // UI更新
                updateBossBar()
                updateScoreboard()
            }
        }
        taskScheduler.scheduleRepeating(GameTaskScheduler.GAME_LOOP, 0L, 20L, gameLoopTask)

        // アクションバー更新タスク（1秒ごと）
        val actionBarLoopTask = object : BukkitRunnable() {
            override fun run() {
                if (state != GameState.RUNNING) {
                    cancel()
                    taskScheduler.cancelNamed(GameTaskScheduler.ACTION_BAR)
                    return
                }
                updateActionBarGuides()
            }
        }
        taskScheduler.scheduleRepeating(GameTaskScheduler.ACTION_BAR, 0L, 20L, actionBarLoopTask)
    }
    
    private fun transitionToCombatPhase() {
        phase = GamePhase.COMBAT
        currentPhaseTime = combatDuration
        
        // リスポーンタスクをクリア（建築フェーズからの移行時）
        taskScheduler.getPlayerTasks(GameTaskScheduler.RESPAWN).forEach { (playerUuid, task) ->
            plugin.logger.info(plugin.languageManager.getMessage("log.respawn-task-cancel-combat"))
            task.cancel()

            // リスポーン待ちのプレイヤーを即座に復活させる
            Bukkit.getPlayer(playerUuid)?.let { player ->
                if (player.gameMode == GameMode.SPECTATOR) {
                    val team = getPlayerTeam(player.uniqueId) ?: return@let

                    // スポーン地点に転送
                    teleportToSpawn(player, team)

                    // ゲームモードを設定
                    player.gameMode = GameMode.SURVIVAL

                    // 保持アイテムがあれば復元
                    val keptItems = player.getMetadata("ctf_items_to_keep")
                        .firstOrNull()?.value() as? List<ItemStack> ?: emptyList()
                    for (item in keptItems) {
                        player.inventory.addItem(item)
                    }
                    player.removeMetadata("ctf_items_to_keep", plugin)

                    // 建築フェーズアイテムを削除
                    removeTeamColoredBlocks(player)

                    // 戦闘フェーズアイテムを配布
                    giveCombatPhaseItems(player, team)
                }
            }
        }
        taskScheduler.cancelAllPlayerTasks(GameTaskScheduler.RESPAWN)
        
        scoreboardManager.bossBar?.setTitle(plugin.languageManager.getMessage("phase-extended.combat-time-format", "time" to formatTime(currentPhaseTime)))
        scoreboardManager.bossBar?.color = BarColor.RED
        
        // フェーズ変更通知
        sendEventNotification(
            plugin.languageManager.getMessageAsComponent("phase-extended.combat-start-title").color(NamedTextColor.RED),
            plugin.languageManager.getMessageAsComponent("phase-extended.combat-start-subtitle").color(NamedTextColor.YELLOW),
            sound = org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL,
            soundPitch = 0.8f
        )
        
        // 窒息チェックタスクを開始
        startSuffocationTask()
        
        // イベントチェストのタイマーを開始
        startEventChestTimer()
        
        // プレイヤーの準備（バッチ処理で安全にテレポート）
        val allPlayers = getAllPlayers().toList()
        val redPlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.RED }
        val bluePlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.BLUE }
        val spectatorPlayers = allPlayers.filter { getPlayerTeam(it.uniqueId) == Team.SPECTATOR }
        
        // チームごとにバッチ処理
        processCombatTeleportBatch(redPlayers, Team.RED, 0L)
        processCombatTeleportBatch(bluePlayers, Team.BLUE, 2L)
        processCombatTeleportBatch(spectatorPlayers, Team.SPECTATOR, 4L)
    }
    
    /**
     * 戦闘フェーズ終了処理
     */
    private fun handleCombatPhaseEnd() {
        // ゲームタスクを一時停止
        taskScheduler.cancelNamed(GameTaskScheduler.GAME_LOOP)

        // リスポーンタスクをキャンセル
        taskScheduler.getPlayerTasks(GameTaskScheduler.RESPAWN).forEach { (playerUuid, _) ->
            plugin.logger.info(plugin.languageManager.getMessage("log.respawn-task-cancel-phase"))

            // リスポーン待ちのプレイヤーを即座に復活させる
            Bukkit.getPlayer(playerUuid)?.let { player ->
                if (player.gameMode == GameMode.SPECTATOR) {
                    handleRespawn(player)
                    player.gameMode = GameMode.ADVENTURE

                    // 保持アイテムがあれば復元
                    val keptItems = player.getMetadata("ctf_items_to_keep")
                        .firstOrNull()?.value() as? List<ItemStack> ?: emptyList()
                    for (item in keptItems) {
                        player.inventory.addItem(item)
                    }
                    player.removeMetadata("ctf_items_to_keep", plugin)
                }
            }
        }
        taskScheduler.cancelAllPlayerTasks(GameTaskScheduler.RESPAWN)
        
        // 戦闘フェーズ終了ボーナス
        val phaseEndBonus = plugin.config.getInt("currency.phase-end-bonus", 100)
        val bonusReason = plugin.languageManager.getMessage("phase-extended.phase-end-bonus")
        getActiveTeams().forEach { addTeamCurrency(it, phaseEndBonus, bonusReason) }
        
        val winner = getWinner()
        
        // ゲームレポートを表示（テキストログのみ）
        displayGameReport(winner)
        
        // 結果をチャットメッセージで簡潔に表示
        val resultMessage = if (winner != null) {
            plugin.languageManager.getMessage("phase-extended.round-result-winner",
                "team" to plugin.languageManager.getMessage("teams.${winner.name.lowercase()}"),
                "scores" to getScoreDisplayText()
            )
        } else {
            plugin.languageManager.getMessage("phase-extended.round-result-draw",
                "scores" to getScoreDisplayText()
            )
        }
        
        getAllPlayers().forEach { player ->
            player.sendMessage(Component.text(resultMessage))
        }
        
        // イベントチェストをクリーンアップ
        cleanupEventChest()
        
        // マッチモードの場合はゲーム終了処理を呼ぶ
        if (matchWrapper != null) {
            // マッチモードではendGameを呼んでコールバックを実行
            endGame()
        } else {
            // 通常モードでは即座に次ラウンドを開始
            startNextRound()
        }
    }
    
    /**
     * 次のラウンドを開始
     */
    private fun startNextRound() {
        // ゲームをリセットして再開始
        resetForNextRound()
    }
    
    /**
     * 次のラウンドのためのリセット処理
     */
    private fun resetForNextRound() {
        // スコアをリセット
        score.clear()

        // 旗の状態をリセット
        flagCarriers.clear()
        droppedFlags.clear()

        // チームブロックをクリア
        teamPlacedBlocks.clear()

        // 通貨をリセット
        teamCurrency.clear()

        // チーム関連マップを動的に再初期化
        initTeams()
        
        // 建築フェーズを開始
        phase = GamePhase.BUILD
        currentPhaseTime = buildDuration
        
        // プレイヤーをリセット
        getAllPlayers().forEach { player ->
            // インベントリはクリアしない（購入したアイテムを保持）
            
            // 建築フェーズのゲームモードを設定
            val team = getPlayerTeam(player.uniqueId)
            if (team != null && team != Team.SPECTATOR) {
                // 建築フェーズのゲームモードを設定
                player.gameMode = when(buildPhaseGameMode) {
                    "CREATIVE" -> GameMode.CREATIVE
                    "ADVENTURE" -> GameMode.ADVENTURE
                    else -> GameMode.SURVIVAL  // デフォルトはSURVIVAL
                }
                
                // 建築フェーズアイテムを付与（既存のアイテムは保持）
                giveBuildPhaseItems(player, team)
                
                // 通貨を初期化
                val initialCurrency = plugin.config.getInt("currency.initial", 50)
                addTeamCurrency(team, initialCurrency, plugin.languageManager.getMessage("currency.initial-currency"))
            } else if (team == Team.SPECTATOR) {
                player.gameMode = GameMode.SPECTATOR
            }
            
            // スポーン地点にテレポート
            handleRespawn(player)
        }
        
        // 旗を復元
        redFlagLocation?.let { loc ->
            loc.block.type = Material.BEACON
        }
        blueFlagLocation?.let { loc ->
            loc.block.type = Material.BEACON
        }
        
        // ゲームループを再開
        startGameLoop()
        
        // 開始メッセージ
        getAllPlayers().forEach { player ->
            player.clearTitle()
            player.showTitle(Title.title(
                plugin.languageManager.getMessageAsComponent("phase-extended.round-start"),
                plugin.languageManager.getMessageAsComponent("phase-extended.build-phase-start"),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(2),
                    Duration.ofMillis(500)
                )
            ))
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        }
    }


    
    /**
     * BossBarを作成（マッチインターバル用）
     */
    fun createBossBar() = scoreboardManager.createBossBar()
    
    /**
     * マッチの次のゲームのために状態をリセット
     */
    fun resetForNextMatchGame() {
        plugin.logger.info("[Game] Resetting for next match game")
        
        // 全てのタスクをキャンセル
        plugin.logger.info(plugin.languageManager.getMessage("log.respawn-task-cancel-match"))
        taskScheduler.cancelAll()

        // ボスバーをリセット
        scoreboardManager.destroyBossBar()
        
        // 状態をリセット
        state = GameState.WAITING
        phase = GamePhase.BUILD
        currentPhaseTime = 0
        autoStartCountdown = -1
        
        // ゲーム状態データはリセット
        score.clear()
        score[Team.RED] = 0
        score[Team.BLUE] = 0
        droppedFlags.clear()
        spawnProtection.clear()
        scoreboardManager.clearState()
        redFlagCarrier = null
        blueFlagCarrier = null

        // マッチモードの場合は旗とスポーン装飾の再設置をスキップ（start()で行う）
        // 通常モードの場合のみ再設置
        if (matchWrapper == null || !matchWrapper!!.isActive) {
            setupFlags()
            setupSpawnAreas()
        }
        
        plugin.logger.info("[Game] Reset complete, state is now: $state")
    }
    
    fun stop(forceStop: Boolean = false) {
        plugin.logger.info("Stopping game: $gameName, current state: $state, forceStop: $forceStop, matchWrapper: ${matchWrapper != null}")
        state = GameState.ENDING
        
        // 全てのタスクをキャンセル
        plugin.logger.info(plugin.languageManager.getMessage("log.respawn-task-cancel-stop"))
        taskScheduler.cancelAll()
        
        // マッチモードかどうかチェック（強制終了の場合はマッチモードを無視）
        val isMatchMode = !forceStop && matchWrapper != null && matchWrapper!!.isActive && !matchWrapper!!.isMatchComplete()
        
        // プレイヤーのUUIDリストをコピー（forEach中の変更を避けるため）観戦者も含む
        val playerUUIDs = (redTeam + blueTeam + spectators).toList()
        
        // プレイヤー処理
        playerUUIDs.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
            // 発光効果を解除
            player.isGlowing = false
            
            // 敵陣メタデータを削除
            player.removeMetadata("on_enemy_block", plugin)
            
            // UIを必ず削除（強制終了時も含む）
            scoreboardManager.resetScoreboard(player)
            scoreboardManager.removePlayerFromBossBar(player)
            
            if (!isMatchMode || forceStop) {
                // マッチモードでない場合、または強制終了の場合
                player.inventory.clear()
                player.gameMode = GameMode.CREATIVE
                
                // 元のワールドに確実に転送
                val mainWorld = originalWorld
                if (mainWorld != null && mainWorld != tempWorld) {
                    player.teleport(mainWorld.spawnLocation)
                    plugin.logger.info("[Game] Teleporting ${player.name} back to original world: ${mainWorld.name}")
                } else {
                    // フォールバック：メインワールドに転送
                    val fallbackWorld = Bukkit.getWorlds()[0]
                    player.teleport(fallbackWorld.spawnLocation)
                    plugin.logger.warning("[Game] Original world not available, teleporting ${player.name} to main world: ${fallbackWorld.name}")
                }
                
                player.sendMessage(plugin.languageManager.getMessageAsComponent("phase-extended.game-ended"))
                
                // GameManagerからプレイヤーを削除
                val gameManager = plugin.gameManager
                gameManager.removePlayerFromGame(player)
            } else {
                // マッチモードの場合は、テンポラリワールド内でスポーン地点に戻してアドベンチャーモードに
                val team = getPlayerTeam(player.uniqueId)
                if (team != null && tempWorld != null) {
                    val spawnLocation = when (team) {
                        Team.RED -> redSpawnLocation ?: redFlagLocation
                        Team.BLUE -> blueSpawnLocation ?: blueFlagLocation
                        Team.SPECTATOR -> mapCenterLocation ?: redFlagLocation
                        else -> spawnLocations[team] ?: flagLocations[team]
                    }
                    if (spawnLocation != null && spawnLocation.world == tempWorld) {
                        // テンポラリワールド内でテレポート
                        player.teleport(spawnLocation)
                        plugin.logger.info("[Game] Teleporting ${player.name} to spawn in temp world: ${tempWorld!!.name}")
                    } else {
                        // スポーン地点が正しく設定されていない場合は、ワールドの中心にテレポート
                        val centerLocation = Location(tempWorld, 0.0, 64.0, 0.0)
                        player.teleport(centerLocation)
                        plugin.logger.warning("[Game] Spawn location not in temp world, teleporting ${player.name} to center")
                    }
                }
                player.gameMode = GameMode.ADVENTURE
                player.sendMessage(plugin.languageManager.getMessageAsComponent("phase-extended.wait-next-game"))
            }
        }
        
        // BossBar削除
        scoreboardManager.destroyBossBar()
        
        // スコアボード状態クリア
        scoreboardManager.clearState()
        
        // 旗とスポーン装飾を削除（マッチモードでもクリアする）
        // ただし、マッチ継続中はプレイヤーが設置したブロックは保持
        cleanupGameBlocks()
        
        // データクリアの前に状態をリセット
        state = GameState.WAITING
        phase = GamePhase.BUILD
        currentPhaseTime = 0
        autoStartCountdown = -1
        
        // マッチモードの場合、プレイヤーデータは保持
        if (!isMatchMode) {
            // データクリア
            redTeam.clear()
            blueTeam.clear()
            disconnectedPlayers.clear()
        }
        
        // イベントチェストをクリーンアップ
        cleanupEventChest()
        
        // ゲーム状態データはリセット
        score.clear()
        score[Team.RED] = 0
        score[Team.BLUE] = 0
        droppedFlags.clear()
        spawnProtection.clear()
        scoreboardManager.clearState()
        redFlagCarrier = null
        blueFlagCarrier = null

        // テンポラリワールドをクリーンアップ（マッチモードでは削除しない、ただし強制終了時は削除）
        if ((!isMatchMode || forceStop) && tempWorld != null) {
            val worldManager = com.hacklab.ctf.world.WorldManager(plugin)
            worldManager.cleanupTempWorld(gameName)
            tempWorld = null
            
            // ワールドを元に戻す
            world = originalWorld
        }
        
        // マッチモードでもワールド参照は保持する（強制終了でない場合）
        if (isMatchMode && tempWorld != null && !forceStop) {
            plugin.logger.info("[Game] Keeping temp world for match continuation: ${tempWorld!!.name}")
        }
    }
    
    private fun endGame() {
        // 既に終了処理中の場合は何もしない
        if (state == GameState.ENDING) {
            plugin.logger.info("Game $gameName is already ending, skipping duplicate endGame call")
            return
        }
        
        plugin.logger.info("Game $gameName ended naturally, state: $state, matchWrapper: ${matchWrapper != null}")
        
        // 状態を終了中に設定（重複防止）
        state = GameState.ENDING
        
        // 勝者を決定
        val winner = getWinner()
        
        // 最終ゲーム終了時は結果を表示
        if (gameEndCallback == null || (matchWrapper != null && matchWrapper!!.isMatchComplete())) {
            // 最終結果をタイトルで表示（最終ゲームのみ）
            getAllPlayers().forEach { player ->
                if (winner != null) {
                    player.clearTitle()
                    player.showTitle(Title.title(
                        plugin.languageManager.getMessageAsComponent("phase-extended.winner-title", 
                            "color" to winner.getChatColor(),
                            "team" to plugin.languageManager.getMessage("teams.${winner.name.lowercase()}")
                        ),
                        plugin.languageManager.getMessageAsComponent("phase-extended.score-display",
                            "scores" to getScoreDisplayText()
                        ),
                        Title.Times.times(
                            Duration.ofMillis(500),
                            Duration.ofSeconds(5),
                            Duration.ofMillis(1000)
                        )
                    ))
                } else {
                    player.clearTitle()
                    player.showTitle(Title.title(
                        plugin.languageManager.getMessageAsComponent("phase-extended.draw-title"),
                        plugin.languageManager.getMessageAsComponent("phase-extended.score-display",
                            "scores" to getScoreDisplayText()
                        ),
                        Title.Times.times(
                            Duration.ofMillis(500),
                            Duration.ofSeconds(5),
                            Duration.ofMillis(1000)
                        )
                    ))
                }
            }
        }
        
        plugin.logger.info("Game $gameName winner: $winner, has callback: ${gameEndCallback != null}")
        
        // コールバックを実行（マッチがある場合は、マッチが次のゲームを管理）
        if (gameEndCallback != null) {
            plugin.logger.info("Invoking game end callback for match")
            gameEndCallback?.invoke(winner)
            // マッチモードの場合、stop()を呼ばない（次のゲームのために状態をリセット）
            if (matchWrapper != null && matchWrapper!!.isActive && !matchWrapper!!.isMatchComplete()) {
                plugin.logger.info("Match continues, not stopping game")
                // 状態はhandleMatchGameEndで適切にリセットされる
            } else {
                plugin.logger.info("Match complete or no match, stopping game")
                stop()
            }
        } else {
            // マッチがない場合のみ、ゲームを停止
            plugin.logger.info("No match callback, stopping game")
            stop()
        }
        
        // プレイヤー統計を更新
        updatePlayerStatistics()
        
        // GameManagerから削除はしない（再利用可能）
    }
    
    // 以下、ヘルパーメソッド
    
    private fun updateLocationsToWorld(newWorld: World) {
        // 位置情報を新しいワールドに更新
        // 注意: 元の位置を保持してから新しいLocationを作成する
        val oldRedFlag = redFlagLocation
        val oldBlueFlag = blueFlagLocation
        val oldRedSpawn = redSpawnLocation
        val oldBlueSpawn = blueSpawnLocation
        
        oldRedFlag?.let {
            redFlagLocation = Location(newWorld, it.x, it.y, it.z, it.yaw, it.pitch)
        }
        oldBlueFlag?.let {
            blueFlagLocation = Location(newWorld, it.x, it.y, it.z, it.yaw, it.pitch)
        }
        oldRedSpawn?.let {
            redSpawnLocation = Location(newWorld, it.x, it.y, it.z, it.yaw, it.pitch)
        }
        oldBlueSpawn?.let {
            blueSpawnLocation = Location(newWorld, it.x, it.y, it.z, it.yaw, it.pitch)
        }
        
        // マップ中央位置も更新
        mapCenterLocation?.let { old ->
            mapCenterLocation = Location(newWorld, old.x, old.y, old.z, old.yaw, old.pitch)
        }
    }
    
    /**
     * スポーン地点周辺のチャンクを事前読み込み
     */
    private fun preloadSpawnChunks() {
        val locations = listOfNotNull(redSpawnLocation, blueSpawnLocation, redFlagLocation, blueFlagLocation)
        locations.forEach { location ->
            val chunkX = location.blockX shr 4
            val chunkZ = location.blockZ shr 4
            
            // 3x3チャンクを事前読み込み
            for (x in -1..1) {
                for (z in -1..1) {
                    location.world.loadChunk(chunkX + x, chunkZ + z, true)
                }
            }
        }
    }
    
    /**
     * プレイヤーをバッチ処理で安全にテレポート
     */
    private fun processTeleportBatch(players: List<Player>, team: Team, initialDelay: Long) {
        if (players.isEmpty()) return
        
        val batchSize = 5 // 一度にテレポートする人数
        var currentDelay = initialDelay
        
        players.chunked(batchSize).forEachIndexed { _, batch ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                batch.forEachIndexed { playerIndex, player ->
                    // 各プレイヤーに少しずつ遅延を追加
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        // 観戦者の場合は特別な処理
                        if (team == Team.SPECTATOR) {
                            val centerLocation = getCenterLocation() ?: getSafeSpawnLocation(Team.RED)
                            player.teleport(centerLocation)
                            player.gameMode = GameMode.SPECTATOR
                            // 観戦者にはアイテムを配布しない
                        } else {
                            // 安全なスポーン位置を計算
                            val safeLocation = getSafeSpawnLocation(team)
                            plugin.logger.info("[Game] Teleporting ${player.name} to world: ${safeLocation.world?.name}")
                            
                            // フェーズ間の移行ではインベントリをクリアしない
                            // （ゲーム開始時のstart()で既にクリアされている）
                            
                            // アクションバーをクリア
                            player.sendActionBar(Component.empty())
                            
                            // ゲームモード設定
                            val targetGameMode = GameMode.valueOf(buildPhaseGameMode)
                            player.gameMode = targetGameMode
                            
                            // 建築フェーズでは飛行を有効化
                            player.allowFlight = true
                            player.isFlying = false  // 初期状態は飛行していない
                            
                            // 安全な位置にテレポート
                            plugin.logger.info("[Game] Executing teleport for ${player.name} to ${safeLocation}")
                            val teleportSuccess = player.teleport(safeLocation)
                            plugin.logger.info("[Game] Teleport result for ${player.name}: $teleportSuccess, now at ${player.location.world?.name}")
                            
                            // 建築フェーズアイテム配布
                            giveBuildPhaseItems(player, team)
                        }
                        
                        // BossBar追加
                        scoreboardManager.addPlayerToBossBar(player)
                    }, playerIndex.toLong()) // 各プレイヤーに0.05秒の遅延
                }
            }, currentDelay)
            currentDelay += 5L // バッチ間は0.25秒の遅延
        }
    }
    
    /**
     * 戦闘フェーズ用のプレイヤーバッチテレポート処理
     */
    private fun processCombatTeleportBatch(players: List<Player>, team: Team, initialDelay: Long) {
        if (players.isEmpty()) return
        
        val batchSize = 5
        var currentDelay = initialDelay
        
        players.chunked(batchSize).forEachIndexed { _, batch ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                batch.forEachIndexed { playerIndex, player ->
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        // 観戦者の場合は特別な処理
                        if (team == Team.SPECTATOR) {
                            val centerLocation = getCenterLocation() ?: getSafeSpawnLocation(Team.RED)
                            player.teleport(centerLocation)
                            player.gameMode = GameMode.SPECTATOR
                            // 観戦者にはアイテムを配布しない
                        } else {
                            val safeLocation = getSafeSpawnLocation(team)
                            
                            // フェーズ間の移行ではインベントリをクリアしない
                            // （ゲーム開始時のstart()で既にクリアされている）
                            
                            // 建築フェーズ専用アイテム（色付きコンクリート・ガラス）を削除
                            removeTeamColoredBlocks(player)
                            
                            // ゲームモード変更
                            player.gameMode = GameMode.SURVIVAL
                            
                            // 戦闘フェーズでは飛行を無効化
                            player.allowFlight = false
                            player.isFlying = false
                            
                            // 安全な位置にテレポート
                            player.teleport(safeLocation)
                            
                            // 戦闘フェーズアイテム配布
                            giveCombatPhaseItems(player, team)
                        }
                        
                        // タイトル表示
                        player.showTitle(Title.title(
                            plugin.languageManager.getMessageAsComponent("phase-extended.combat-start-title"),
                            plugin.languageManager.getMessageAsComponent("phase-extended.combat-start-subtitle"),
                            Title.Times.times(
                                Duration.ofMillis(500),
                                Duration.ofSeconds(3),
                                Duration.ofMillis(500)
                            )
                        ))
                    }, playerIndex.toLong())
                }
            }, currentDelay)
            currentDelay += 5L
        }
    }
    
    /**
     * スポーン地点にプラットフォームが存在することを保証する
     * VoidWorldでは地面がないため、確実にブロックを配置する
     */
    private fun ensureSpawnPlatforms() {
        val world = tempWorld ?: this.world
        plugin.logger.info("[Game] Ensuring spawn platforms in world: ${world.name}")
        
        // GameConfigから複数スポーン地点を取得
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(gameName)
        
        // 赤チームのすべてのスポーン地点
        val baseRedSpawns = config?.getAllRedSpawnLocations() ?: listOfNotNull(redSpawnLocation ?: redFlagLocation)
        val redSpawns = if (tempWorld != null) {
            baseRedSpawns.map { spawn ->
                Location(world, spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch)
            }
        } else {
            baseRedSpawns
        }
        
        redSpawns.forEachIndexed { index, spawn ->
            createPlatformAt(world, spawn.blockX, spawn.blockY - 1, spawn.blockZ, Material.RED_CONCRETE)
            plugin.logger.info("[Game] Created red spawn platform #${index + 1} at: ${spawn.blockX}, ${spawn.blockY - 1}, ${spawn.blockZ}")
        }
        
        // 青チームのすべてのスポーン地点
        val baseBlueSpawns = config?.getAllBlueSpawnLocations() ?: listOfNotNull(blueSpawnLocation ?: blueFlagLocation)
        val blueSpawns = if (tempWorld != null) {
            baseBlueSpawns.map { spawn ->
                Location(world, spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch)
            }
        } else {
            baseBlueSpawns
        }
        
        blueSpawns.forEachIndexed { index, spawn ->
            createPlatformAt(world, spawn.blockX, spawn.blockY - 1, spawn.blockZ, Material.BLUE_CONCRETE)
            plugin.logger.info("[Game] Created blue spawn platform #${index + 1} at: ${spawn.blockX}, ${spawn.blockY - 1}, ${spawn.blockZ}")
        }
        
        // 観戦者用の中央地点（無効化）
        // 白いコンクリートのプラットフォームは生成しない
        /*
        val centerSpawn = mapCenterLocation
        if (centerSpawn != null) {
            createPlatformAt(world, centerSpawn.blockX, centerSpawn.blockY - 1, centerSpawn.blockZ, Material.WHITE_CONCRETE)
            plugin.logger.info("[Game] Created center platform at: ${centerSpawn.blockX}, ${centerSpawn.blockY - 1}, ${centerSpawn.blockZ}")
        }
        */
    }
    
    private fun createPlatformAt(world: World, centerX: Int, y: Int, centerZ: Int, material: Material) {
        // チームを判定
        val team = when (material) {
            Material.RED_CONCRETE -> Team.RED
            Material.BLUE_CONCRETE -> Team.BLUE
            else -> null
        }
        
        // 3x3のプラットフォームを作成
        for (x in -1..1) {
            for (z in -1..1) {
                val block = world.getBlockAt(centerX + x, y, centerZ + z)
                // 既存のブロックがない場合のみ配置
                if (block.type == Material.AIR || !block.type.isSolid) {
                    block.type = material
                    
                    // チームブロックの場合、teamPlacedBlocksとteamBlockTreesに登録
                    if (team != null) {
                        val location = block.location
                        
                        // teamPlacedBlocksに追加
                        val placedBlocks = teamPlacedBlocks.getOrPut(team) { mutableSetOf() }
                        placedBlocks.add(location)
                        
                        // teamBlockTreesに追加（ルートノードとして）
                        val trees = teamBlockTrees.getOrPut(team) { mutableMapOf() }
                        trees[location] = BlockNode(location, null) // ルートノードなので親はnull
                        
                        plugin.logger.info("[Game] Registered spawn platform block for $team at $location")
                    }
                }
            }
        }
        
        // 上の空間をクリア（3ブロック分）
        for (clearY in 1..3) {
            for (x in -1..1) {
                for (z in -1..1) {
                    val block = world.getBlockAt(centerX + x, y + clearY, centerZ + z)
                    if (block.type.isSolid) {
                        block.type = Material.AIR
                    }
                }
            }
        }
    }

    private fun getSafeSpawnLocation(team: Team): Location {
        // 一時的世界では常にGame.ktの更新済み位置を使用（configではなく）
        val baseLocation = when (team) {
            Team.RED -> redSpawnLocation ?: redFlagLocation
            Team.BLUE -> blueSpawnLocation ?: blueFlagLocation
            Team.SPECTATOR -> mapCenterLocation ?: redFlagLocation
            else -> spawnLocations[team] ?: flagLocations[team]
        } ?: throw IllegalStateException("No spawn location for team $team")
        
        plugin.logger.info("[SafeSpawn] Base location for $team: $baseLocation (world: ${baseLocation.world?.name})")
        
        val world = baseLocation.world
        val baseX = baseLocation.blockX
        val baseY = baseLocation.blockY
        val baseZ = baseLocation.blockZ
        
        // 3x3の範囲内でランダムに位置を選択
        val maxAttempts = 20
        var attempts = 0
        
        while (attempts < maxAttempts) {
            attempts++
            
            // -1～+1の範囲でランダムな位置を選択（3x3の範囲）
            val offsetX = (Math.random() * 3 - 1).toInt()
            val offsetZ = (Math.random() * 3 - 1).toInt()
            
            val checkX = baseX + offsetX
            val checkZ = baseZ + offsetZ
            
            // その位置で安全な高さを探す
            for (checkY in (baseY - 2)..(baseY + 2)) {
                // ワールドの高さ制限チェック（ネガティブY座標も許可）
                if (checkY < world.minHeight || checkY > world.maxHeight - 3) continue
                
                val floor = world.getBlockAt(checkX, checkY, checkZ)
                val space1 = world.getBlockAt(checkX, checkY + 1, checkZ)
                val space2 = world.getBlockAt(checkX, checkY + 2, checkZ)
                
                // 床が固体で、上2ブロックが空気の場合
                if (floor.type.isSolid && !floor.type.name.contains("SLAB") && !floor.type.name.contains("STAIRS") &&
                    !space1.type.isSolid && space1.type != Material.WATER && space1.type != Material.LAVA &&
                    !space2.type.isSolid && space2.type != Material.WATER && space2.type != Material.LAVA) {
                    
                    val location = Location(world, checkX + 0.5, checkY + 1.0, checkZ + 0.5)
                    
                    // 敵陣のビーコンを向くように設定
                    val enemyFlagLocation = when (team) {
                        Team.RED -> blueFlagLocation
                        Team.BLUE -> redFlagLocation
                        Team.SPECTATOR -> null
                        else -> null
                    }
                    
                    if (enemyFlagLocation != null) {
                        // 敵陣ビーコンへの方向を計算
                        val dx = enemyFlagLocation.x - location.x
                        val dy = enemyFlagLocation.y - location.y
                        val dz = enemyFlagLocation.z - location.z
                        
                        // YawとPitchを計算
                        val horizontalDistance = Math.sqrt(dx * dx + dz * dz)
                        location.yaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
                        location.pitch = Math.toDegrees(Math.atan2(-dy, horizontalDistance)).toFloat()
                    } else {
                        // 観戦者の場合はマップ中央を向く
                        location.yaw = 0f
                        location.pitch = 0f
                    }
                    
                    plugin.logger.info("[SafeSpawn] Found safe location for $team at: $location")
                    return location
                }
            }
        }
        
        // 安全な場所が見つからない場合は、確実にプラットフォームがある中央位置を返す
        val fallbackLocation = Location(world, baseX + 0.5, baseY.toDouble(), baseZ + 0.5)
        
        // 敵陣のビーコンを向くように設定
        val enemyFlagLocation = when (team) {
            Team.RED -> blueFlagLocation
            Team.BLUE -> redFlagLocation
            Team.SPECTATOR -> null
            else -> null
        }
        
        if (enemyFlagLocation != null) {
            // 敵陣ビーコンへの方向を計算
            val dx = enemyFlagLocation.x - fallbackLocation.x
            val dy = enemyFlagLocation.y - fallbackLocation.y
            val dz = enemyFlagLocation.z - fallbackLocation.z
            
            // YawとPitchを計算
            val horizontalDistance = Math.sqrt(dx * dx + dz * dz)
            fallbackLocation.yaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
            fallbackLocation.pitch = Math.toDegrees(Math.atan2(-dy, horizontalDistance)).toFloat()
        } else {
            // 観戦者の場合はマップ中央を向く
            fallbackLocation.yaw = 0f
            fallbackLocation.pitch = 0f
        }
        
        plugin.logger.info("[SafeSpawn] Using fallback location for $team at: $fallbackLocation")
        return fallbackLocation
    }
    
    private fun teleportToSpawn(player: Player, team: Team) {
        try {
            // 既にテレポート済みのプレイヤー数を考慮して安全な位置を計算
            val safeLocation = getSafeSpawnLocation(team)
            player.teleport(safeLocation)
        } catch (e: IllegalStateException) {
            player.sendMessage(plugin.languageManager.getMessageAsComponent("spawn.not-set"))
            plugin.logger.warning("Game $name: No spawn location for team $team")
        }
    }
    
    internal fun giveBuildPhaseItems(player: Player, team: Team) {
        // 観戦者には建築フェーズアイテムを配布しない
        if (team == Team.SPECTATOR) return
        
        val inv = player.inventory
        
        // 革の防具（チームカラー）
        giveColoredLeatherArmor(player, team)
        
        // ダイヤピッケル（効率エンチャント付き）を配布（重複チェック）
        if (!hasInitialItem(player, InitialItemType.PICKAXE, Material.DIAMOND_PICKAXE)) {
            val pickaxe = ItemStack(Material.DIAMOND_PICKAXE).apply {
                itemMeta = itemMeta?.apply {
                    displayName(plugin.languageManager.getMessageAsComponent("items.efficient-diamond-pickaxe"))
                    lore(listOf(
                        plugin.languageManager.getMessageAsComponent("items.no-drop-on-death"),
                        plugin.languageManager.getMessageAsComponent("items.initial-equipment")
                    ))
                    addEnchant(Enchantment.EFFICIENCY, 3, false)
                    isUnbreakable = true
                }
            }
            markAsInitialItem(pickaxe, InitialItemType.PICKAXE)
            inv.addItem(pickaxe)
        }
        
        // ダイヤシャベル（効率エンチャント付き）を配布（重複チェック）
        if (!hasInitialItem(player, InitialItemType.SHOVEL, Material.DIAMOND_SHOVEL)) {
            val shovel = ItemStack(Material.DIAMOND_SHOVEL).apply {
                itemMeta = itemMeta?.apply {
                    displayName(plugin.languageManager.getMessageAsComponent("items.efficient-diamond-shovel"))
                    lore(listOf(
                        plugin.languageManager.getMessageAsComponent("items.no-drop-on-death"),
                        plugin.languageManager.getMessageAsComponent("items.initial-equipment")
                    ))
                    addEnchant(Enchantment.EFFICIENCY, 3, false)
                    isUnbreakable = true
                }
            }
            markAsInitialItem(shovel, InitialItemType.SHOVEL)
            inv.addItem(shovel)
        }
        
        // チームカラーブロックを配布（フェーズごとの個数制限）
        val blocksToGive = if (phase == GamePhase.BUILD) buildPhaseBlocks else combatPhaseBlocks
        
        val teamConcrete = ItemStack(
            when (team) {
                Team.RED -> Material.RED_CONCRETE
                Team.BLUE -> Material.BLUE_CONCRETE
                Team.SPECTATOR -> Material.WHITE_CONCRETE // 観戦者用（到達しないはず）
                else -> team.getConcreteMaterial()
            },
            blocksToGive
        ).apply {
            itemMeta = itemMeta?.apply {
                displayName(plugin.languageManager.getMessageAsComponent("flag.concrete-name",
                    "color" to team.getChatColor(),
                    "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")
                ))
                lore(listOf(plugin.languageManager.getMessageAsComponent("items.building-block")))
            }
        }
        
        player.inventory.addItem(teamConcrete)
        
        // 建築用ブロックをランダムに2種類配布（各64個）
        // 基本的な素材ブロックのみ（加工品は除外）
        val buildingBlockCandidates = listOf(
            // 石系
            Material.STONE,
            Material.COBBLESTONE,
            Material.ANDESITE,
            Material.DIORITE,
            Material.GRANITE,
            Material.DEEPSLATE,
            Material.TUFF,
            Material.CALCITE,
            Material.SANDSTONE,
            Material.RED_SANDSTONE,
            
            // 木材系
            Material.OAK_PLANKS,
            Material.SPRUCE_PLANKS,
            Material.BIRCH_PLANKS,
            Material.JUNGLE_PLANKS,
            Material.ACACIA_PLANKS,
            Material.DARK_OAK_PLANKS,
            Material.MANGROVE_PLANKS,
            Material.CHERRY_PLANKS,
            
            // 自然ブロック
            Material.DIRT,
            Material.GRASS_BLOCK,
            Material.SAND,
            Material.RED_SAND,
            Material.GRAVEL,
            Material.CLAY,
            Material.MOSS_BLOCK,
            Material.MUD,
            
            // ガラス系
            Material.GLASS,
            Material.WHITE_STAINED_GLASS,
            Material.ORANGE_STAINED_GLASS,
            Material.MAGENTA_STAINED_GLASS,
            Material.LIGHT_BLUE_STAINED_GLASS,
            Material.YELLOW_STAINED_GLASS,
            Material.LIME_STAINED_GLASS,
            Material.PINK_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS,
            Material.LIGHT_GRAY_STAINED_GLASS,
            Material.CYAN_STAINED_GLASS,
            Material.PURPLE_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS,
            Material.BROWN_STAINED_GLASS,
            Material.GREEN_STAINED_GLASS,
            Material.RED_STAINED_GLASS,
            Material.BLACK_STAINED_GLASS,
            
            // 羊毛系
            Material.WHITE_WOOL,
            Material.ORANGE_WOOL,
            Material.MAGENTA_WOOL,
            Material.LIGHT_BLUE_WOOL,
            Material.YELLOW_WOOL,
            Material.LIME_WOOL,
            Material.PINK_WOOL,
            Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL,
            Material.CYAN_WOOL,
            Material.PURPLE_WOOL,
            Material.BLUE_WOOL,
            Material.BROWN_WOOL,
            Material.GREEN_WOOL,
            Material.RED_WOOL,
            Material.BLACK_WOOL,
            
            // テラコッタ系
            Material.TERRACOTTA,
            Material.WHITE_TERRACOTTA,
            Material.ORANGE_TERRACOTTA,
            Material.MAGENTA_TERRACOTTA,
            Material.LIGHT_BLUE_TERRACOTTA,
            Material.YELLOW_TERRACOTTA,
            Material.LIME_TERRACOTTA,
            Material.PINK_TERRACOTTA,
            Material.GRAY_TERRACOTTA,
            Material.LIGHT_GRAY_TERRACOTTA,
            Material.CYAN_TERRACOTTA,
            Material.PURPLE_TERRACOTTA,
            Material.BLUE_TERRACOTTA,
            Material.BROWN_TERRACOTTA,
            Material.GREEN_TERRACOTTA,
            Material.RED_TERRACOTTA,
            Material.BLACK_TERRACOTTA,

            // その他建築ブロック
            Material.BRICKS,
            Material.STONE_BRICKS,
            Material.PRISMARINE,
            Material.PRISMARINE_BRICKS,
            Material.DARK_PRISMARINE,
            Material.NETHERRACK,
            Material.NETHER_BRICKS,
            Material.RED_NETHER_BRICKS,
            Material.BASALT,
            Material.BLACKSTONE,
            Material.END_STONE,
            Material.END_STONE_BRICKS,
            Material.PURPUR_BLOCK,
            Material.OBSIDIAN,
            Material.CRYING_OBSIDIAN,
            Material.QUARTZ_BLOCK,
            Material.GLOWSTONE,
            Material.SEA_LANTERN,
            Material.SPONGE,
            Material.SNOW_BLOCK,
            Material.ICE,
            Material.PACKED_ICE,
            Material.BLUE_ICE,
            Material.SLIME_BLOCK,
            Material.HONEY_BLOCK,
            Material.HAY_BLOCK,
            Material.DRIED_KELP_BLOCK,
            Material.BONE_BLOCK
        )
        
        // ランダムに2種類を選択（重複なし）
        val selectedBlocks = buildingBlockCandidates.shuffled().take(2)
        
        selectedBlocks.forEach { material ->
            val blockStack = ItemStack(material, 64).apply {
                itemMeta = itemMeta?.apply {
                    val blockName = material.name.lowercase()
                        .replace('_', ' ')
                        .split(' ')
                        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

                    displayName(Component.text("$blockName (無料配布)", NamedTextColor.GREEN))
                    lore(listOf(
                        Component.text("建築用ブロック", NamedTextColor.GRAY),
                        Component.text("創造的な建築を楽しもう！", NamedTextColor.YELLOW),
                        Component.text("死亡時もドロップしません", NamedTextColor.DARK_GRAY)
                    ))

                    // ドロップ不可フラグを設定（死亡時に保持される）
                    persistentDataContainer.set(
                        NamespacedKey(plugin, "no_drop"),
                        PersistentDataType.BOOLEAN,
                        true
                    )
                }
            }
            inv.addItem(blockStack)
        }
        
        // ショップアイテムをホットバー9番目に配置（既にない場合のみ）
        if (!hasInitialItem(player, InitialItemType.SHOP_EMERALD, Material.EMERALD)) {
            val shopItem = createShopItem()
            markAsInitialItem(shopItem, InitialItemType.SHOP_EMERALD)
            player.inventory.setItem(8, shopItem)
        }
    }
    
    
    private fun giveCombatPhaseItems(player: Player, team: Team) {
        val inv = player.inventory
        
        // 革の防具（チームカラー）を再装備（防具をリセットして確実に着用）
        giveColoredLeatherArmor(player, team)
        
        // チームカラーブロックを配布（戦闘フェーズ用個数）
        if (combatPhaseBlocks > 0) {
            val teamConcrete = ItemStack(
                when (team) {
                    Team.RED -> Material.RED_CONCRETE
                    Team.BLUE -> Material.BLUE_CONCRETE
                    Team.SPECTATOR -> Material.WHITE_CONCRETE
                    else -> team.getConcreteMaterial()
                },
                combatPhaseBlocks
            ).apply {
                itemMeta = itemMeta?.apply {
                    displayName(plugin.languageManager.getMessageAsComponent("flag.concrete-name",
                        "color" to team.getChatColor(),
                        "team" to plugin.languageManager.getMessage("teams.${team.name.lowercase()}")
                    ))
                    lore(listOf(plugin.languageManager.getMessageAsComponent("items.building-block")))
                }
            }
            
            inv.addItem(teamConcrete)
        }
        
        // ダイヤピッケル（効率エンチャント付き）を配布（重複チェック）
        if (!hasInitialItem(player, InitialItemType.PICKAXE, Material.DIAMOND_PICKAXE)) {
            val pickaxe = ItemStack(Material.DIAMOND_PICKAXE).apply {
                itemMeta = itemMeta?.apply {
                    displayName(plugin.languageManager.getMessageAsComponent("items.efficient-diamond-pickaxe"))
                    lore(listOf(
                        plugin.languageManager.getMessageAsComponent("items.no-drop-on-death"),
                        plugin.languageManager.getMessageAsComponent("items.initial-equipment")
                    ))
                    addEnchant(Enchantment.EFFICIENCY, 3, false)
                    isUnbreakable = true
                }
            }
            markAsInitialItem(pickaxe, InitialItemType.PICKAXE)
            inv.addItem(pickaxe)
        }
        
        // ダイヤシャベル（効率エンチャント付き）を配布（重複チェック）
        if (!hasInitialItem(player, InitialItemType.SHOVEL, Material.DIAMOND_SHOVEL)) {
            val shovel = ItemStack(Material.DIAMOND_SHOVEL).apply {
                itemMeta = itemMeta?.apply {
                    displayName(plugin.languageManager.getMessageAsComponent("items.efficient-diamond-shovel"))
                    lore(listOf(
                        plugin.languageManager.getMessageAsComponent("items.no-drop-on-death"),
                        plugin.languageManager.getMessageAsComponent("items.initial-equipment")
                    ))
                    addEnchant(Enchantment.EFFICIENCY, 3, false)
                    isUnbreakable = true
                }
            }
            markAsInitialItem(shovel, InitialItemType.SHOVEL)
            inv.addItem(shovel)
        }
        
        // チーム識別用にプレイヤー名を色付け
        // チーム色はスコアボードチームの設定で表示（displayNameは変更しない）
        // displayNameを変更するとチャット署名エラーが発生するため削除
        
        // 戦闘フェーズではチームカラーブロックは配布しない
        
        // ショップアイテムをホットバー9番目に配置（既にない場合のみ）
        if (!hasInitialItem(player, InitialItemType.SHOP_EMERALD, Material.EMERALD)) {
            val shopItem = createShopItem()
            markAsInitialItem(shopItem, InitialItemType.SHOP_EMERALD)
            player.inventory.setItem(8, shopItem)
        }
    }
    
    private fun giveDefaultCombatItems(player: Player, team: Team) {
        @Suppress("UNUSED_PARAMETER")
        // 初期装備なし - ショップで購入する必要がある
        
        // 戦闘フェーズではチームカラーブロックは配布しない
        
        // ショップアイテムをホットバー9番目に配置（既にない場合のみ）
        if (!hasInitialItem(player, InitialItemType.SHOP_EMERALD, Material.EMERALD)) {
            val shopItem = createShopItem()
            markAsInitialItem(shopItem, InitialItemType.SHOP_EMERALD)
            player.inventory.setItem(8, shopItem)
        }
    }
    
    private fun isLeatherArmor(material: Material): Boolean {
        return material in listOf(
            Material.LEATHER_HELMET,
            Material.LEATHER_CHESTPLATE,
            Material.LEATHER_LEGGINGS,
            Material.LEATHER_BOOTS
        )
    }
    
    /**
     * 初期配布アイテムのタイプを定義
     */
    private enum class InitialItemType(val key: String) {
        PICKAXE("initial_pickaxe"),
        SHOVEL("initial_shovel"),
        LEATHER_ARMOR("initial_armor"),
        SHOP_EMERALD("initial_shop")
    }
    
    /**
     * プレイヤーが特定の初期アイテムを既に持っているかチェック
     */
    private fun hasInitialItem(player: Player, itemType: InitialItemType, material: Material? = null): Boolean {
        val key = NamespacedKey(plugin, itemType.key)
        
        return when (itemType) {
            InitialItemType.LEATHER_ARMOR -> {
                // 革防具の場合は装備スロットもチェック
                val equipment = player.equipment ?: return false
                listOf(equipment.helmet, equipment.chestplate, equipment.leggings, equipment.boots).any { item ->
                    item != null && isLeatherArmor(item.type) &&
                    item.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BOOLEAN) == true
                }
            }
            else -> {
                // その他のアイテムはインベントリをチェック
                player.inventory.contents.any { item ->
                    item != null &&
                    (material == null || item.type == material) &&
                    item.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BOOLEAN) == true
                }
            }
        }
    }
    
    /**
     * アイテムに初期配布マークを付ける
     */
    private fun markAsInitialItem(item: ItemStack, itemType: InitialItemType): ItemStack {
        item.itemMeta = item.itemMeta?.apply {
            val key = NamespacedKey(plugin, itemType.key)
            persistentDataContainer.set(key, PersistentDataType.BOOLEAN, true)
            
            // ドロップ不可フラグも同時に設定
            persistentDataContainer.set(
                NamespacedKey(plugin, "no_drop"),
                PersistentDataType.BOOLEAN,
                true
            )
        }
        return item
    }
    
    private fun giveColoredLeatherArmor(player: Player, team: Team) {
        // 観戦者には防具を配布しない
        if (team == Team.SPECTATOR) return
        
        // 既に初期配布の革防具を持っている場合はスキップ
        if (hasInitialItem(player, InitialItemType.LEATHER_ARMOR)) return
        
        val color = when (team) {
            Team.RED -> org.bukkit.Color.RED
            Team.BLUE -> org.bukkit.Color.BLUE
            Team.SPECTATOR -> org.bukkit.Color.GRAY // 観戦者用（到達しないはず）
            else -> team.getArmorColor()
        }
        
        // 革のヘルメット
        val helmet = ItemStack(Material.LEATHER_HELMET)
        val helmetMeta = helmet.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        helmetMeta?.setColor(color)
        helmetMeta?.isUnbreakable = true
        helmet.itemMeta = helmetMeta
        markAsInitialItem(helmet, InitialItemType.LEATHER_ARMOR)
        
        // 革のチェストプレート
        val chestplate = ItemStack(Material.LEATHER_CHESTPLATE)
        val chestMeta = chestplate.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        chestMeta?.setColor(color)
        chestMeta?.isUnbreakable = true
        chestplate.itemMeta = chestMeta
        markAsInitialItem(chestplate, InitialItemType.LEATHER_ARMOR)
        
        // 革のレギンス
        val leggings = ItemStack(Material.LEATHER_LEGGINGS)
        val legMeta = leggings.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        legMeta?.setColor(color)
        legMeta?.isUnbreakable = true
        leggings.itemMeta = legMeta
        markAsInitialItem(leggings, InitialItemType.LEATHER_ARMOR)
        
        // 革のブーツ
        val boots = ItemStack(Material.LEATHER_BOOTS)
        val bootMeta = boots.itemMeta as? org.bukkit.inventory.meta.LeatherArmorMeta
        bootMeta?.setColor(color)
        bootMeta?.isUnbreakable = true
        boots.itemMeta = bootMeta
        markAsInitialItem(boots, InitialItemType.LEATHER_ARMOR)
        
        // 装備
        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots
    }
    
    private fun setupFlags() = flagSystem.setupFlags()
    
    fun setupFlagBeacon(location: Location, team: Team) = flagSystem.setupFlagBeacon(location, team)
    
    private fun setupSpawnAreas() {
        // スポーンエリアの装飾（スポーン地点が設定されている場合のみ）
        // 注意：テンポラリワールドでは更新済みの位置を使用
        val currentWorld = tempWorld ?: world
        
        Team.values().filter { it != Team.SPECTATOR }.forEach { team ->
            // GameConfigから複数スポーン地点を取得
            val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
            val config = gameManager?.getGameConfig(gameName)
            
            val baseSpawnLocations = when (team) {
                Team.RED -> config?.getAllRedSpawnLocations() ?: listOfNotNull(redSpawnLocation)
                Team.BLUE -> config?.getAllBlueSpawnLocations() ?: listOfNotNull(blueSpawnLocation)
                Team.SPECTATOR -> emptyList()
                else -> listOfNotNull(spawnLocations[team])
            }
            
            // テンポラリワールドの場合は、位置をそのワールドに更新
            val spawnLocations = if (tempWorld != null) {
                baseSpawnLocations.map { loc ->
                    Location(currentWorld, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
                }
            } else {
                baseSpawnLocations
            }
            
            // すべてのスポーン地点に対して装飾を設置
            spawnLocations.forEach { spawnLocation ->
                setupSpawnDecoration(spawnLocation, team)
            }
        }
    }
    
    private fun setupSpawnDecoration(location: Location, team: Team) {
        // 観戦者にはスポーン装飾を設置しない
        if (team == Team.SPECTATOR) return
        
        // チーム色のコンクリートで3x3の床のみ
        val concreteType = when (team) {
            Team.RED -> Material.RED_CONCRETE
            Team.BLUE -> Material.BLUE_CONCRETE
            Team.SPECTATOR -> Material.WHITE_CONCRETE // 観戦者用（到達しないはず）
            else -> team.getConcreteMaterial()
        }
        
        // スポーン地点のコンクリートを設置し、チームの配置ブロックとして記録
        val trees = teamBlockTrees.getOrPut(team) { mutableMapOf() }
        val blocks = teamPlacedBlocks.getOrPut(team) { mutableSetOf() }
        
        plugin.logger.info("[SetupSpawn] Setting up spawn decoration for $team at $location")
        
        for (x in -1..1) {
            for (z in -1..1) {
                val blockLocation = location.clone().add(x.toDouble(), -1.0, z.toDouble())
                blockLocation.block.type = concreteType
                
                // チームの配置ブロックとして記録
                blocks.add(blockLocation)
                
                // ツリー構造にも追加（スポーン地点を起点として接続可能にする）
                // スポーン地点のブロックは親なし（ルートノード）として登録
                val node = BlockNode(blockLocation, null)
                trees[blockLocation] = node
                plugin.logger.info("[SetupSpawn] Added spawn block to tree: $blockLocation")
            }
        }
        
        plugin.logger.info("[SetupSpawn] Total blocks in tree for $team: ${trees.size}")
        plugin.logger.info("[SetupSpawn] Total blocks in placedBlocks for $team: ${blocks.size}")
    }
    
    private fun cleanupGameBlocks() {
        // 旗の削除
        Team.values().filter { it != Team.SPECTATOR }.forEach { team ->
            val flagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
                Team.SPECTATOR -> null // 観戦者用（到達しないはず）
                else -> flagLocations[team]
            } ?: return@forEach
            
            // ビーコンと色付きガラスを削除
            flagLocation.block.type = Material.AIR
            flagLocation.clone().add(0.0, 1.0, 0.0).block.type = Material.AIR
            
            // ベースブロックを削除
            for (x in -1..1) {
                for (z in -1..1) {
                    flagLocation.clone().add(x.toDouble(), -1.0, z.toDouble()).block.type = Material.AIR
                }
            }
        }
        
        // スポーン装飾の削除（スポーン地点が設定されている場合のみ）
        val gameManager = plugin.gameManager as? com.hacklab.ctf.managers.GameManager
        val config = gameManager?.getGameConfig(gameName)
        
        Team.values().filter { it != Team.SPECTATOR }.forEach { team ->
            val spawnLocations = when (team) {
                Team.RED -> config?.getAllRedSpawnLocations() ?: listOfNotNull(redSpawnLocation)
                Team.BLUE -> config?.getAllBlueSpawnLocations() ?: listOfNotNull(blueSpawnLocation)
                Team.SPECTATOR -> emptyList()
                else -> listOfNotNull(this.spawnLocations[team])
            }
            
            // すべてのスポーン地点のコンクリート床を削除
            spawnLocations.forEach { spawnLocation ->
                for (x in -1..1) {
                    for (z in -1..1) {
                        val blockLocation = spawnLocation.clone().add(x.toDouble(), -1.0, z.toDouble())
                        blockLocation.block.type = Material.AIR
                        
                        // チームの配置ブロックリストからも削除
                        teamPlacedBlocks[team]?.remove(blockLocation)
                    }
                }
            }
        }
    }
    
    // --- UI 委譲メソッド（scoreboardManager に委譲） ---

    private fun setupScoreboard(player: Player) = scoreboardManager.setupScoreboard(player)
    fun updateScoreboard() = scoreboardManager.updateScoreboard()
    fun forceUpdateScoreboard() = scoreboardManager.forceUpdateScoreboard()
    fun updatePlayerTabColor(player: Player) = scoreboardManager.updatePlayerTabColor(player)

    fun sendEventNotification(
        title: Component,
        subtitle: Component? = null,
        targetPlayers: List<Player> = getAllPlayers().toList(),
        sound: org.bukkit.Sound? = null,
        soundPitch: Float = 1.0f
    ) = scoreboardManager.sendEventNotification(title, subtitle, targetPlayers, sound, soundPitch)
    
    // updatePlayerScoreboard, updateBossBar は scoreboardManager に移行済み
    private fun updateBossBar() = scoreboardManager.updateBossBar()
    
    fun dropFlag(player: Player, team: Team, deathLocation: Location? = null) = flagSystem.dropFlag(player, team, deathLocation)
    
    private fun checkDroppedFlags() = flagSystem.checkDroppedFlags()
    
    fun pickupFlag(player: Player, item: org.bukkit.entity.Item): Boolean = flagSystem.pickupFlag(player, item)
    
    private fun checkDroppedFlagTouch() = flagSystem.checkDroppedFlagTouch()
    
    fun captureFlag(player: Player): Boolean = flagSystem.captureFlag(player)
    
    fun handleRespawn(player: Player) {
        val team = getPlayerTeam(player.uniqueId) ?: return
        
        // スポーン保護を付与
        val protectionMillis = plugin.config.getInt("mechanics.spawn-protection-duration", 3) * 1000
        spawnProtection[player.uniqueId] = System.currentTimeMillis() + protectionMillis
        
        // 保護中は光る
        player.isGlowing = true
        
        // 3秒後に保護を解除
        val protectionDuration = plugin.config.getInt("mechanics.spawn-protection-duration", 3) * 20L
        val protectionTask = object : BukkitRunnable() {
            override fun run() {
                if (spawnProtection.remove(player.uniqueId) != null) {
                    player.isGlowing = false
                    player.sendMessage(plugin.languageManager.getMessageAsComponent("spawn.protection-ended"))
                }
                taskScheduler.removePlayerTask(GameTaskScheduler.SPAWN_PROTECTION, player.uniqueId)
            }
        }
        taskScheduler.schedulePlayerTask(GameTaskScheduler.SPAWN_PROTECTION, player.uniqueId, protectionDuration, protectionTask)
        
        // スポーン地点に転送
        teleportToSpawn(player, team)
        
        // 戦闘フェーズではリスポーン時に装備を再配布しない（革防具は死亡時処理で保持される）
        // 建築フェーズでは、リスポーン時に基本アイテムを復元する
        when (phase) {
            GamePhase.BUILD -> {
                // 観戦者には建築フェーズアイテムを配布しない
                if (team != Team.SPECTATOR) {
                    // リスポーン時はブロックを再配布しない（消費型のため）
                    // ピッケルやシャベルなどの初期装備のみチェック
                
                // ショップアイテムの重複チェック - スロット8にのみ配置
                // まず全インベントリをチェックして既存のショップアイテムを削除
                for (i in 0 until player.inventory.size) {
                    val item = player.inventory.getItem(i)
                    if (item != null && isShopItem(item)) {
                        if (i != 8) {
                            // スロット8以外にある場合は削除
                            player.inventory.setItem(i, null)
                        }
                    }
                }
                
                // スロット8を確認して、ショップアイテムがなければ配置
                val slot8Item = player.inventory.getItem(8)
                if (slot8Item == null || !isShopItem(slot8Item)) {
                    val shopItem = createShopItem()
                    player.inventory.setItem(8, shopItem)
                }
                
                // 革防具がない場合は再配布（初回死亡時など）
                if (player.equipment?.helmet?.type != Material.LEATHER_HELMET) {
                    plugin.equipmentManager.giveArmor(player, team)
                }
                } // 観戦者チェックの終了
            }
            GamePhase.COMBAT -> {
                // 戦闘フェーズでは革防具の確認のみ（死亡時処理で保持されているはず）
                // 万が一ない場合は再配布
                if (player.equipment?.helmet?.type != Material.LEATHER_HELMET) {
                    plugin.equipmentManager.giveArmor(player, team)
                }
            }

        }
        
        player.sendMessage(plugin.languageManager.getMessageAsComponent("spawn.protection-active"))
    }
    
    fun isUnderSpawnProtection(player: Player): Boolean {
        val protectionEnd = spawnProtection[player.uniqueId] ?: return false
        return System.currentTimeMillis() < protectionEnd
    }
    
    /**
     * スポーン保護を即座に解除
     * 攻撃や旗取得時に呼び出される
     */
    fun removeSpawnProtection(player: Player) {
        if (spawnProtection.remove(player.uniqueId) != null) {
            player.isGlowing = false
            taskScheduler.cancelPlayerTask(GameTaskScheduler.SPAWN_PROTECTION, player.uniqueId)
            player.sendMessage(plugin.languageManager.getMessageAsComponent("spawn.protection-cancelled"))
        }
    }
    
    fun formatTime(seconds: Int): String = scoreboardManager.formatTime(seconds)

    fun showActionBarError(player: Player, message: String, duration: Long = 3000L) =
        scoreboardManager.showActionBarError(player, message, duration)
    
    private fun updateActionBarGuides() = scoreboardManager.updateActionBarGuides()
    
    // --- 通貨管理メソッド（currencyManager に委譲） ---
    fun getTeamCurrency(team: Team): Int = currencyManager.getTeamCurrency(team)
    fun removeTeamCurrency(team: Team, amount: Int) = currencyManager.removeTeamCurrency(team, amount)
    fun getDiscountRate(team: Team): Double = currencyManager.getDiscountRate(team)
    fun createShopItem(): ItemStack = currencyManager.createShopItem()
    fun isShopItem(item: ItemStack?): Boolean = currencyManager.isShopItem(item)
    fun resetGamePurchases() = currencyManager.resetGamePurchases()
    fun addTeamCurrency(team: Team, amount: Int, reason: String = "") = currencyManager.addTeamCurrency(team, amount, reason)
    fun spendTeamCurrency(team: Team, amount: Int, player: Player, itemName: String): Boolean = currencyManager.spendTeamCurrency(team, amount, player, itemName)
    fun initializeCurrency() = currencyManager.initializeCurrency()
    
    private fun displayGameReport(winner: Team?) {
        // ゲームの詳細レポート
        getAllPlayers().forEach { player ->
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(plugin.languageManager.getMessageAsComponent("report.header"))
            
            // マッチ情報（マッチモードの場合）
            matchWrapper?.let { m ->
                player.sendMessage(Component.text(m.getMatchStatus()).color(NamedTextColor.YELLOW))
                val wins = m.matchWins
                player.sendMessage(plugin.languageManager.getMessageAsComponent("report.match-score-header")
                    .append(plugin.languageManager.getMessageAsComponent("report.match-score-format",
                        "red" to wins[Team.RED].toString(),
                        "blue" to wins[Team.BLUE].toString()
                    )))
                player.sendMessage(Component.text("", NamedTextColor.WHITE))
            }
            
            // 今回のゲーム
            val gameNumber = matchWrapper?.currentGameNumber ?: 1
            player.sendMessage(plugin.languageManager.getMessageAsComponent("report.game-header", "number" to gameNumber.toString()))
            if (winner != null) {
                player.sendMessage(plugin.languageManager.getMessageAsComponent("report.winner-format",
                    "color" to winner.getChatColor(),
                    "team" to plugin.languageManager.getMessage("teams.${winner.name.lowercase()}")
                ))
            } else {
                player.sendMessage(plugin.languageManager.getMessageAsComponent("report.draw-result"))
            }
            player.sendMessage(plugin.languageManager.getMessageAsComponent("report.score-header")
                .append(plugin.languageManager.getMessageAsComponent("report.match-score-format",
                    "scores" to getScoreDisplayText()
                )))
            
            // チーム統計
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(plugin.languageManager.getMessageAsComponent("report.team-stats-header"))
            
            // 赤チーム
            player.sendMessage(plugin.languageManager.getMessageAsComponent("report.red-team-header"))
            val redPlayers = getTeamPlayers(Team.RED)
            redPlayers.forEach { p ->
                player.sendMessage(plugin.languageManager.getMessageAsComponent("game.player-list-item", 
                    "player" to p.name,
                    "team" to plugin.languageManager.getMessage("teams.red")))
            }
            
            // 青チーム
            player.sendMessage(plugin.languageManager.getMessageAsComponent("report.blue-team-header"))
            val bluePlayers = getTeamPlayers(Team.BLUE)
            bluePlayers.forEach { p ->
                player.sendMessage(plugin.languageManager.getMessageAsComponent("game.player-list-item", 
                    "player" to p.name,
                    "team" to plugin.languageManager.getMessage("teams.blue")))
            }
            
            // 通貨情報
            if (matchWrapper != null) {
                player.sendMessage(Component.text("", NamedTextColor.WHITE))
                player.sendMessage(plugin.languageManager.getMessageAsComponent("report.team-funds-header"))
                player.sendMessage(plugin.languageManager.getMessageAsComponent("report.team-funds-format",
                    "color" to Team.RED.getChatColor(),
                    "team" to plugin.languageManager.getMessage("teams.red"),
                    "amount" to matchWrapper!!.getTeamCurrency(Team.RED).toString()
                ))
                player.sendMessage(plugin.languageManager.getMessageAsComponent("report.team-funds-format",
                    "color" to Team.BLUE.getChatColor(),
                    "team" to plugin.languageManager.getMessage("teams.blue"),
                    "amount" to matchWrapper!!.getTeamCurrency(Team.BLUE).toString()
                ))
            }
            
            player.sendMessage(plugin.languageManager.getMessageAsComponent("ui.separator-long").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
        }
        
        // MVP発表（マッチの最終ゲームまたは単独ゲームの場合のみ）
        if (matchWrapper == null || (matchWrapper != null && matchWrapper!!.isMatchComplete())) {
            displayMVP()
        }
    }
    
    private fun displayMVP() {
        // MVP計算
        val mvpScores = mutableMapOf<UUID, Double>()
        val allPlayers = getAllPlayers()
        
        allPlayers.forEach { player ->
            val uuid = player.uniqueId
            var score = 0.0
            
            // キル数 (1キル = 10ポイント)
            score += (playerKills[uuid] ?: 0) * 10.0
            
            // アシスト (1アシスト = 5ポイント)
            score += (playerAssists[uuid] ?: 0) * 5.0
            
            // 旗キャプチャー (1キャプチャー = 30ポイント)
            score += (playerCaptures[uuid] ?: 0) * 30.0
            
            // 旗取得 (1取得 = 15ポイント)
            score += (playerFlagPickups[uuid] ?: 0) * 15.0
            
            // 旗防衛 (1防衛 = 20ポイント)
            score += (playerFlagDefends[uuid] ?: 0) * 20.0
            
            // お金使用 (100G使用 = 10ポイント)
            score += (playerMoneySpent[uuid] ?: 0) * 0.1
            
            // ブロック設置 (100ブロック = 5ポイント)
            score += (playerBlocksPlaced[uuid] ?: 0) * 0.05
            
            // デス数ペナルティ (1デス = -5ポイント)
            score -= (playerDeaths[uuid] ?: 0) * 5.0
            
            mvpScores[uuid] = score
        }
        
        // MVP決定
        val mvp = mvpScores.maxByOrNull { it.value }
        if (mvp != null && mvp.value > 0) {
            val mvpPlayer = Bukkit.getPlayer(mvp.key)
            if (mvpPlayer != null) {
                // MVP発表
                getAllPlayers().forEach { player ->
                    player.sendMessage(Component.text("", NamedTextColor.WHITE))
                    player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.announcement-title").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.player-label").color(NamedTextColor.YELLOW)
                        .append(Component.text(mvpPlayer.name).color(NamedTextColor.AQUA).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                        .append(plugin.languageManager.getMessageAsComponent("mvp.score-display", "score" to String.format("%.1f", mvp.value)).color(NamedTextColor.WHITE)))
                    
                    // MVP統計詳細
                    player.sendMessage(Component.text("", NamedTextColor.WHITE))
                    player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.stats-header").color(NamedTextColor.YELLOW))
                    
                    val uuid = mvpPlayer.uniqueId
                    val kills = playerKills[uuid] ?: 0
                    val assists = playerAssists[uuid] ?: 0
                    val captures = playerCaptures[uuid] ?: 0
                    val flagPickups = playerFlagPickups[uuid] ?: 0
                    val flagDefends = playerFlagDefends[uuid] ?: 0
                    val moneySpent = playerMoneySpent[uuid] ?: 0
                    val blocks = playerBlocksPlaced[uuid] ?: 0
                    val deaths = playerDeaths[uuid] ?: 0
                    
                    if (kills > 0) player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.kills-display", "count" to kills.toString()).color(NamedTextColor.GREEN))
                    if (assists > 0) player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.assists-display", "count" to assists.toString()).color(NamedTextColor.GREEN))
                    if (captures > 0) player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.captures-display", "count" to captures.toString()).color(NamedTextColor.GOLD))
                    if (flagPickups > 0) player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.flag-pickups-display", "count" to flagPickups.toString()).color(NamedTextColor.YELLOW))
                    if (flagDefends > 0) player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.flag-defends-display", "count" to flagDefends.toString()).color(NamedTextColor.AQUA))
                    if (moneySpent > 0) player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.money-spent-display", "amount" to moneySpent.toString()).color(NamedTextColor.YELLOW))
                    if (blocks > 0) player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.blocks-placed-display", "count" to blocks.toString()).color(NamedTextColor.WHITE))
                    if (deaths > 0) player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.deaths-display", "count" to deaths.toString()).color(NamedTextColor.RED))
                    
                    player.sendMessage(plugin.languageManager.getMessageAsComponent("mvp.star-decoration").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                    
                    // MVPにタイトル表示
                    if (player == mvpPlayer) {
                        player.showTitle(Title.title(
                            plugin.languageManager.getMessageAsComponent("mvp.mvp-title").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                            plugin.languageManager.getMessageAsComponent("mvp.mvp-subtitle", "player" to mvpPlayer.name).color(NamedTextColor.YELLOW),
                            Title.Times.times(
                                Duration.ofMillis(500),
                                Duration.ofSeconds(3),
                                Duration.ofMillis(500)
                            )
                        ))
                    }
                }
                
                // 効果音
                getAllPlayers().forEach { p ->
                    p.playSound(p.location, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
                }
            }
        }
        
        // 各項目のトップを表示
        displayTopPlayers()
    }
    
    private fun displayTopPlayers() {
        val allPlayers = getAllPlayers()
        if (allPlayers.isEmpty()) return
        
        getAllPlayers().forEach { player ->
            player.sendMessage(Component.text("", NamedTextColor.WHITE))
            player.sendMessage(plugin.languageManager.getMessageAsComponent("stats.top-players-header").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
            
            // キル数トップ
            val topKiller = playerKills.maxByOrNull { it.value }
            if (topKiller != null && topKiller.value > 0) {
                val killerName = Bukkit.getPlayer(topKiller.key)?.name ?: "不明"
                player.sendMessage(plugin.languageManager.getMessageAsComponent("stats.top-kills-label").color(NamedTextColor.RED)
                    .append(plugin.languageManager.getMessageAsComponent("stats.top-kills-value", "player" to killerName, "count" to topKiller.value.toString()).color(NamedTextColor.WHITE)))
            }
            
            // 旗キャプチャートップ
            val topCapturer = playerCaptures.maxByOrNull { it.value }
            if (topCapturer != null && topCapturer.value > 0) {
                val capturerName = Bukkit.getPlayer(topCapturer.key)?.name ?: "不明"
                player.sendMessage(plugin.languageManager.getMessageAsComponent("stats.top-captures-label").color(NamedTextColor.GOLD)
                    .append(plugin.languageManager.getMessageAsComponent("stats.top-captures-value", "player" to capturerName, "count" to topCapturer.value.toString()).color(NamedTextColor.WHITE)))
            }
            
            // 旗防衛トップ
            val topDefender = playerFlagDefends.maxByOrNull { it.value }
            if (topDefender != null && topDefender.value > 0) {
                val defenderName = Bukkit.getPlayer(topDefender.key)?.name ?: "不明"
                player.sendMessage(plugin.languageManager.getMessageAsComponent("stats.top-defends-label").color(NamedTextColor.AQUA)
                    .append(plugin.languageManager.getMessageAsComponent("stats.top-defends-value", "player" to defenderName, "count" to topDefender.value.toString()).color(NamedTextColor.WHITE)))
            }
            
            // アシストトップ
            val topAssister = playerAssists.maxByOrNull { it.value }
            if (topAssister != null && topAssister.value > 0) {
                val assisterName = Bukkit.getPlayer(topAssister.key)?.name ?: "不明"
                player.sendMessage(plugin.languageManager.getMessageAsComponent("stats.top-assists-label").color(NamedTextColor.GREEN)
                    .append(plugin.languageManager.getMessageAsComponent("stats.top-assists-value", "player" to assisterName, "count" to topAssister.value.toString()).color(NamedTextColor.WHITE)))
            }
            
            // 建築トップ
            val topBuilder = playerBlocksPlaced.maxByOrNull { it.value }
            if (topBuilder != null && topBuilder.value > 0) {
                val builderName = Bukkit.getPlayer(topBuilder.key)?.name ?: "不明"
                player.sendMessage(plugin.languageManager.getMessageAsComponent("stats.top-blocks-label").color(NamedTextColor.YELLOW)
                    .append(plugin.languageManager.getMessageAsComponent("stats.top-blocks-value", "player" to builderName, "count" to topBuilder.value.toString()).color(NamedTextColor.WHITE)))
            }
            
            // 最多消費
            val topSpender = playerMoneySpent.maxByOrNull { it.value }
            if (topSpender != null && topSpender.value > 0) {
                val spenderName = Bukkit.getPlayer(topSpender.key)?.name ?: "不明"
                player.sendMessage(plugin.languageManager.getMessageAsComponent("stats.top-spent-label").color(NamedTextColor.LIGHT_PURPLE)
                    .append(plugin.languageManager.getMessageAsComponent("stats.top-spent-value", "player" to spenderName, "amount" to topSpender.value.toString()).color(NamedTextColor.WHITE)))
            }
            
            // 最少デス（1人以上いる場合のみ）
            if (playerDeaths.isNotEmpty()) {
                val leastDeaths = playerDeaths.filter { 
                    val p = Bukkit.getPlayer(it.key)
                    p != null && getAllPlayers().contains(p)
                }.minByOrNull { it.value }
                
                if (leastDeaths != null) {
                    val survivorName = Bukkit.getPlayer(leastDeaths.key)?.name ?: "不明"
                    player.sendMessage(plugin.languageManager.getMessageAsComponent("stats.least-deaths-label").color(NamedTextColor.DARK_GREEN)
                        .append(plugin.languageManager.getMessageAsComponent("stats.least-deaths-value", "player" to survivorName, "count" to leastDeaths.value.toString()).color(NamedTextColor.WHITE)))
                }
            }
            
            player.sendMessage(plugin.languageManager.getMessageAsComponent("ui.separator-short").color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
        }
    }
    
    // --- 旗ヘルパー委譲メソッド（flagSystem に委譲） ---

    private fun isPlayerFalling(player: Player): Boolean = flagSystem.isPlayerFalling(player)
    private fun isLocationFalling(location: Location): Boolean = flagSystem.isLocationFalling(location)
    private fun findSafeDropLocation(originalLocation: Location): Location? = flagSystem.findSafeDropLocation(originalLocation)
    private fun isLocationSurroundedByDanger(location: Location): Boolean = flagSystem.isLocationSurroundedByDanger(location)

    /**
     * 安全な観戦位置を探す（落下死用）
     */
    fun findSafeSpectatorLocation(deathLocation: Location): Location {
        // まず安全なドロップ位置を探す
        val safeLocation = flagSystem.findSafeDropLocation(deathLocation)
        if (safeLocation != null) {
            // 見つかった場合は少し上空から観戦
            return safeLocation.clone().add(0.0, 5.0, 0.0)
        }

        // 見つからない場合は、マップ中央を使用
        if (mapCenterLocation != null) {
            return mapCenterLocation!!.clone().add(0.0, 20.0, 0.0)
        }

        // マップ中央が設定されていない場合は、スポーン地点から計算
        // ゲームワールドを使用（テンポラリワールドの場合もある）
        val world = this.world
        val redSpawn = redSpawnLocation
        val blueSpawn = blueSpawnLocation

        if (redSpawn != null && blueSpawn != null) {
            // 両スポーン地点の中間点を計算
            val centerX = (redSpawn.x + blueSpawn.x) / 2.0
            val centerZ = (redSpawn.z + blueSpawn.z) / 2.0
            val centerY = kotlin.math.max(redSpawn.y, blueSpawn.y) + 20.0
            return Location(world, centerX, centerY, centerZ)
        }

        // 最後の手段として、赤スポーンまたは青スポーンを使用
        return redSpawn?.clone()?.add(0.0, 10.0, 0.0)
            ?: blueSpawn?.clone()?.add(0.0, 10.0, 0.0)
            ?: Location(world, deathLocation.x, deathLocation.y + 10.0, deathLocation.z)
    }

    private fun isSafeLocation(location: Location): Boolean = flagSystem.isSafeLocation(location)
    
    fun canPlaceBlock(player: Player, block: org.bukkit.block.Block): Location? =
        blockSystem.canPlaceBlock(player, block)

    fun hasAdjacentTeamBlock(location: Location, team: Team): Boolean =
        blockSystem.hasAdjacentTeamBlock(location, team)
    
    // isDeviceOrFence and isAdjacent moved to BlockConnectionSystem
    
    fun recordBlockPlacement(team: Team, location: Location, parent: Location) =
        blockSystem.recordBlockPlacement(team, location, parent)
    
    // reconnectWhiteBlocks moved to BlockConnectionSystem
    
    // collectConnectedWhiteBlocks moved to BlockConnectionSystem
    
    fun recordBlockBreak(location: Location) =
        blockSystem.recordBlockBreak(location)
    
    /**
     * 敵陣シールドシステムタスクを開始
     */
    private fun startSuffocationTask() {
        damageHealingSystem.startSuffocationTask()
    }

    // findReachableBlocks moved to BlockConnectionSystem
    
    /**
     * マップ中央位置を計算（各種位置から推定）
     */
    private fun calculateMapCenter() {
        val locations = mutableListOf<Location>()
        
        // 旗位置を追加
        redFlagLocation?.let { locations.add(it) }
        blueFlagLocation?.let { locations.add(it) }
        
        // スポーン位置を追加
        redSpawnLocation?.let { locations.add(it) }
        blueSpawnLocation?.let { locations.add(it) }
        
        if (locations.isEmpty()) {
            mapCenterLocation = null
            return
        }
        
        // すべての位置の中心を計算
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        
        locations.forEach { loc ->
            sumX += loc.x
            sumY += loc.y
            sumZ += loc.z
        }
        
        val avgX = sumX / locations.size
        val avgY = sumY / locations.size
        val avgZ = sumZ / locations.size
        
        mapCenterLocation = Location(world, avgX, avgY, avgZ)
    }

    
    /**
     * イベントチェストのタイマーを開始
     */
    private fun startEventChestTimer() {
        // 既存のタスクをキャンセル
        taskScheduler.cancelAllListTasks(GameTaskScheduler.EVENT_CHEST)
        eventChestLocations.clear()
        eventChestSpawnCount = 0

        // 設定から有効かチェック
        val enabled = plugin.config.getBoolean("event-chest.enabled", true)
        if (!enabled) return

        // スポーン回数を取得（デフォルト: 1回）
        val spawnCount = plugin.config.getInt("event-chest.spawn-count", 1)
        if (spawnCount <= 0) return

        // 戦闘フェーズを等分してスポーンタイミングを計算
        val segments = spawnCount + 1  // n回スポーンする場合、フェーズをn+1等分
        val segmentDuration = combatDuration / segments

        // 各スポーンタイミングでタスクを作成
        for (i in 1..spawnCount) {
            val spawnDelay = segmentDuration * i  // i番目のセグメントの終わりでスポーン

            val task = object : BukkitRunnable() {
                override fun run() {
                    if (phase != GamePhase.COMBAT || state != GameState.RUNNING) {
                        cancel()
                        return
                    }
                    spawnEventChest(i)
                }
            }
            taskScheduler.addListTask(GameTaskScheduler.EVENT_CHEST, spawnDelay * 20L, task)

            plugin.logger.info("Event chest #$i scheduled at $spawnDelay seconds")
        }
    }
    
    /**
     * イベントチェストをスポーン
     */
    private fun spawnEventChest(chestNumber: Int) {
        
        // チェスト番号に応じて異なる場所を探す
        val centerLoc = findEventChestLocation(chestNumber) ?: run {
            plugin.logger.warning("Could not find suitable location for event chest #$chestNumber")
            return
        }
        
        // 既に同じ位置にチェストがある場合はスキップ
        if (eventChestLocations.any { it.block == centerLoc.block }) {
            plugin.logger.info("Event chest already exists at this location")
            return
        }
        
        eventChestLocations.add(centerLoc)
        eventChestSpawnCount++
        
        // チェストを設置
        val block = centerLoc.block
        block.type = Material.CHEST
        
        // チェストにアイテムを配置
        val chest = block.state as? org.bukkit.block.Chest ?: return
        val inventory = chest.inventory
        
        // 高価なショップアイテムからランダムに選択
        val rareItems = selectRareItems()
        rareItems.forEach { item ->
            inventory.addItem(item)
        }
        
        // 全プレイヤーに通知
        sendEventNotification(
            Component.text(plugin.languageManager.getMessage("event-chest.spawn-title")).color(NamedTextColor.GOLD).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
            Component.text(plugin.languageManager.getMessage("event-chest.spawn-subtitle"), NamedTextColor.YELLOW),
            sound = org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL,
            soundPitch = 1.2f
        )
        
        // チャットメッセージも送信
        getAllPlayers().forEach { player ->
            player.sendMessage(Component.text(plugin.languageManager.getMessage("event-chest.spawn-message"), NamedTextColor.GOLD))
        }
        
        // チェストの位置に光るエフェクト（固定で有効）
        centerLoc.world?.spawnParticle(
            org.bukkit.Particle.END_ROD,
            centerLoc.clone().add(0.5, 1.0, 0.5),
            100,
            0.5,
            1.0,
            0.5,
            0.1
        )
    }
    
    /**
     * マップ中央の陸地を探す
     */
    private fun findCenterLandLocation(): Location? {
        val world = tempWorld ?: originalWorld ?: return null
        
        // 旗の位置から中央を計算
        val redFlag = redFlagLocation ?: return null
        val blueFlag = blueFlagLocation ?: return null
        
        val centerX = (redFlag.blockX + blueFlag.blockX) / 2
        val centerZ = (redFlag.blockZ + blueFlag.blockZ) / 2
        
        // 中央付近で陸地を探す（半径10ブロック内）
        for (radius in 0..10) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (kotlin.math.abs(dx) != radius && kotlin.math.abs(dz) != radius) continue
                    
                    val checkX = centerX + dx
                    val checkZ = centerZ + dz
                    
                    // 最も高い固体ブロックを探す
                    for (y in world.maxHeight downTo world.minHeight) {
                        val loc = Location(world, checkX.toDouble(), y.toDouble(), checkZ.toDouble())
                        val block = loc.block
                        
                        if (block.type.isSolid && block.getRelative(0, 1, 0).type == Material.AIR) {
                            // 上が空気で、固体ブロックの上なら陸地
                            return loc.clone().add(0.0, 1.0, 0.0)
                        }
                    }
                }
            }
        }
        
        // 見つからない場合は中央の最高地点
        val centerLoc = Location(world, centerX.toDouble(), 64.0, centerZ.toDouble())
        return world.getHighestBlockAt(centerLoc).location.clone().add(0.0, 1.0, 0.0)
    }

    
    /**
     * イベントチェストの位置を探す（チェスト番号に応じて異なる場所）
     */
    private fun findEventChestLocation(chestNumber: Int): Location? {
        val world = tempWorld ?: originalWorld ?: return null
        
        // 旗の位置から中央を計算
        val redFlag = redFlagLocation ?: return null
        val blueFlag = blueFlagLocation ?: return null
        
        // 総スポーン数を取得
        val totalCount = plugin.config.getInt("event-chest.spawn-count", 1)
        
        // スポーン位置を計算
        val location = when {
            totalCount == 1 -> {
                // 1個の場合: 中央
                Location(
                    world,
                    (redFlag.blockX + blueFlag.blockX) / 2.0,
                    64.0,
                    (redFlag.blockZ + blueFlag.blockZ) / 2.0
                )
            }
            totalCount == 2 -> {
                // 2個の場合: 旗を結ぶ線を3等分した1/3と2/3の位置
                when (chestNumber) {
                    1 -> Location(
                        world,
                        redFlag.blockX * 2.0/3.0 + blueFlag.blockX * 1.0/3.0,
                        64.0,
                        redFlag.blockZ * 2.0/3.0 + blueFlag.blockZ * 1.0/3.0
                    )
                    2 -> Location(
                        world,
                        redFlag.blockX * 1.0/3.0 + blueFlag.blockX * 2.0/3.0,
                        64.0,
                        redFlag.blockZ * 1.0/3.0 + blueFlag.blockZ * 2.0/3.0
                    )
                    else -> null
                }
            }
            totalCount >= 3 -> {
                // 3個以上の場合: 中央を中心に円状に配置
                val centerX = (redFlag.blockX + blueFlag.blockX) / 2.0
                val centerZ = (redFlag.blockZ + blueFlag.blockZ) / 2.0
                val radius = kotlin.math.min(
                    kotlin.math.abs(redFlag.blockX - blueFlag.blockX),
                    kotlin.math.abs(redFlag.blockZ - blueFlag.blockZ)
                ) / 4.0  // 旗間距離の1/4を半径とする
                
                val angle = 2.0 * Math.PI * (chestNumber - 1) / totalCount
                Location(
                    world,
                    centerX + radius * kotlin.math.cos(angle),
                    64.0,
                    centerZ + radius * kotlin.math.sin(angle)
                )
            }
            else -> null
        } ?: return null
        
        // その位置付近で陸地を探す
        return findNearestLandLocation(location)
    }
    
    /**
     * 指定位置付近の陸地を探す
     */
    private fun findNearestLandLocation(targetLoc: Location): Location? {
        val world = targetLoc.world ?: return null
        
        // ターゲット位置から半径10ブロック内で陸地を探す
        for (radius in 0..10) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (kotlin.math.abs(dx) != radius && kotlin.math.abs(dz) != radius) continue
                    
                    val checkX = targetLoc.blockX + dx
                    val checkZ = targetLoc.blockZ + dz
                    
                    // 最も高い固体ブロックを探す
                    for (y in world.maxHeight downTo world.minHeight) {
                        val loc = Location(world, checkX.toDouble(), y.toDouble(), checkZ.toDouble())
                        val block = loc.block
                        
                        if (block.type.isSolid && block.getRelative(0, 1, 0).type == Material.AIR) {
                            // 上が空気で、固体ブロックの上なら陸地
                            return loc.clone().add(0.0, 1.0, 0.0)
                        }
                    }
                }
            }
        }
        
        // 見つからない場合はターゲット位置の最高地点
        return world.getHighestBlockAt(targetLoc).location.clone().add(0.0, 1.0, 0.0)
    }
    
    /**
     * レアアイテムを選択（ショップアイテムから5〜10個ランダムに）
     */
    private fun selectRareItems(): List<ItemStack> {
        val items = mutableListOf<ItemStack>()
        
        // ショップマネージャーからアイテムを取得
        val shopManager = plugin.shopManager
        val allShopItems = shopManager.getShopItems()
        
        if (allShopItems.isEmpty()) {
            plugin.logger.warning("No shop items available for event chest")
            return items
        }
        
        // 高価なアイテムを優先的に選択（価格が高い順にソート）
        val sortedByPrice = allShopItems.sortedByDescending { it.basePrice }
        
        // 上位の高価なアイテムから選択
        val expensiveItems = sortedByPrice.take(kotlin.math.max(10, sortedByPrice.size / 3)) // 上位1/3または最低10個
        
        // カテゴリごとに分類
        val weaponItems = expensiveItems.filter { it.category == ShopCategory.WEAPONS }
        val armorItems = expensiveItems.filter { it.category == ShopCategory.ARMOR }
        val consumableItems = expensiveItems.filter { it.category == ShopCategory.CONSUMABLES }
        val blockItems = expensiveItems.filter { it.category == ShopCategory.BLOCKS }
        
        // 5〜10個のランダムな個数を決定
        val itemCount = Random().nextInt(6) + 5 // 5〜10個
        
        // アイテムを選択
        repeat(itemCount) {
            // 重み付きでカテゴリを選択
            val categoryWeights = mutableListOf<Pair<List<ShopItem>, Int>>()
            if (weaponItems.isNotEmpty()) categoryWeights.add(weaponItems to 30)  // 30%
            if (armorItems.isNotEmpty()) categoryWeights.add(armorItems to 25)    // 25%
            if (consumableItems.isNotEmpty()) categoryWeights.add(consumableItems to 30) // 30%
            if (blockItems.isNotEmpty()) categoryWeights.add(blockItems to 15)    // 15%
            
            // 重み付き選択
            var selectedItem: ShopItem? = null
            if (categoryWeights.isNotEmpty()) {
                val totalWeight = categoryWeights.sumOf { it.second }
                var randomValue = Random().nextInt(totalWeight)
                
                for ((categoryItems, weight) in categoryWeights) {
                    randomValue -= weight
                    if (randomValue < 0) {
                        selectedItem = categoryItems.random()
                        break
                    }
                }
            }
            
            // 選択されたアイテムがない場合は、全体からランダムに選択
            if (selectedItem == null && expensiveItems.isNotEmpty()) {
                selectedItem = expensiveItems.random()
            }
            
            // それでもない場合は、全アイテムからランダムに選択
            if (selectedItem == null && allShopItems.isNotEmpty()) {
                selectedItem = allShopItems.random()
            }
            
            // ShopItemをItemStackに変換
            if (selectedItem != null) {
                val itemStack = ItemStack(selectedItem.material, selectedItem.amount)
                
                // エンチャントを追加
                for ((enchantment, level) in selectedItem.enchantments) {
                    itemStack.addUnsafeEnchantment(enchantment, level)
                }
                
                // アイテムメタを設定
                val meta = itemStack.itemMeta
                if (meta != null) {
                    // 表示名を設定（イベントチェスト特別仕様）
                    meta.displayName(Component.text("★ ", NamedTextColor.GOLD)
                        .append(Component.text(selectedItem.displayName, NamedTextColor.YELLOW)))
                    
                    // 説明文を設定
                    val loreList = mutableListOf<Component>()
                    loreList.add(Component.text("Event Chest Item", NamedTextColor.LIGHT_PURPLE))
                    if (selectedItem.lore.isNotEmpty()) {
                        loreList.addAll(selectedItem.lore.map { Component.text(it, NamedTextColor.GRAY) })
                    }
                    meta.lore(loreList)
                    
                    // 耐久無限を設定
                    if (selectedItem.unbreakable) {
                        meta.isUnbreakable = true
                    }
                    
                    itemStack.itemMeta = meta
                }
                
                items.add(itemStack)
            }
        }
        
        return items
    }
    
    /**
     * イベントチェストをクリーンアップ
     */
    private fun cleanupEventChest() {
        // 全てのタスクをキャンセル
        taskScheduler.cancelAllListTasks(GameTaskScheduler.EVENT_CHEST)
        
        // 全てのチェストを削除
        eventChestLocations.forEach { loc ->
            if (loc.block.type == Material.CHEST) {
                // チェストの中身をクリア
                val chest = loc.block.state as? org.bukkit.block.Chest
                chest?.inventory?.clear()
                // チェストを削除
                loc.block.type = Material.AIR
            }
        }
        
        eventChestLocations.clear()
        eventChestSpawnCount = 0
    }
    
    /**
     * マップ範囲を設定（POS1/POS2から）
     */
    fun setMapBounds(pos1: Location, pos2: Location) {
        val centerX = (pos1.x + pos2.x) / 2.0
        val centerY = (pos1.y + pos2.y) / 2.0
        val centerZ = (pos1.z + pos2.z) / 2.0
        
        mapCenterLocation = Location(world, centerX, centerY, centerZ)
    }
    
    /**
     * プレイヤーのインベントリから色付きコンクリート・ガラスを削除
     */
    private fun removeTeamColoredBlocks(player: Player) {
        val inventory = player.inventory
        val itemsToRemove = mutableListOf<Material>()
        
        // 削除対象のマテリアル
        itemsToRemove.addAll(listOf(
            Material.RED_CONCRETE,
            Material.BLUE_CONCRETE
        ))
        
        // インベントリから削除（全てのチームカラーブロックを削除）
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i)
            if (item != null && itemsToRemove.contains(item.type)) {
                inventory.setItem(i, null)
            }
        }
    }
    
    private fun giveSpectatorItems(player: Player) {
        player.inventory.clear()
        
        // 統計表示アイテム
        val statsItem = ItemStack(Material.PAPER).apply {
            itemMeta = itemMeta?.apply {
                displayName(plugin.languageManager.getMessageAsComponent("spectator.stats-item"))
                lore(listOf(
                    plugin.languageManager.getMessageAsComponent("spectator.right-click-display")
                ))
                persistentDataContainer.set(
                    NamespacedKey(plugin, "spectator_item"),
                    PersistentDataType.STRING,
                    "stats"
                )
            }
        }
        
        // プレイヤー追跡アイテム
        val trackItem = ItemStack(Material.COMPASS).apply {
            itemMeta = itemMeta?.apply {
                displayName(plugin.languageManager.getMessageAsComponent("spectator.player-tracker"))
                lore(listOf(
                    plugin.languageManager.getMessageAsComponent("spectator.right-click-menu")
                ))
                persistentDataContainer.set(
                    NamespacedKey(plugin, "spectator_item"),
                    PersistentDataType.STRING,
                    "track"
                )
            }
        }
        
        // 赤チームスポーンへのテレポート
        val redSpawnItem = ItemStack(Material.RED_BED).apply {
            itemMeta = itemMeta?.apply {
                displayName(plugin.languageManager.getMessageAsComponent("spectator.player-tracker"))
                lore(listOf(
                    plugin.languageManager.getMessageAsComponent("spectator.right-click-menu")
                ))
                persistentDataContainer.set(
                    NamespacedKey(plugin, "spectator_item"),
                    PersistentDataType.STRING,
                    "spawn_red"
                )
            }
        }
        
        // 青チームスポーンへのテレポート
        val blueSpawnItem = ItemStack(Material.BLUE_BED).apply {
            itemMeta = itemMeta?.apply {
                displayName(plugin.languageManager.getMessageAsComponent("spectator.player-tracker"))
                lore(listOf(
                    plugin.languageManager.getMessageAsComponent("spectator.right-click-menu")
                ))
                persistentDataContainer.set(
                    NamespacedKey(plugin, "spectator_item"),
                    PersistentDataType.STRING,
                    "spawn_blue"
                )
            }
        }
        
        // 旗の位置へのテレポート
        val flagItem = ItemStack(Material.BEACON).apply {
            itemMeta = itemMeta?.apply {
                displayName(plugin.languageManager.getMessageAsComponent("spectator.player-tracker"))
                lore(listOf(
                    plugin.languageManager.getMessageAsComponent("spectator.right-click-menu")
                ))
                persistentDataContainer.set(
                    NamespacedKey(plugin, "spectator_item"),
                    PersistentDataType.STRING,
                    "flags"
                )
            }
        }
        
        // アイテムをインベントリに配置
        player.inventory.setItem(0, statsItem)
        player.inventory.setItem(2, trackItem)
        player.inventory.setItem(4, flagItem)
        player.inventory.setItem(6, redSpawnItem)
        player.inventory.setItem(8, blueSpawnItem)
    }
    
    /**
     * ゲーム終了時にプレイヤー統計を更新
     */
    private fun updatePlayerStatistics() {
        val winner = getWinner()

        getAllPlayers().forEach { player ->
            val uuid = player.uniqueId
            val kills = playerKills[uuid] ?: 0
            val deaths = playerDeaths[uuid] ?: 0
            val captures = playerCaptures[uuid] ?: 0
            val playerTeam = getPlayerTeam(uuid)
            val gameWon = playerTeam == winner

            plugin.playerStatisticsManager.updatePlayerStats(
                uuid = uuid,
                kills = kills,
                deaths = deaths,
                captures = captures,
                gamePlayed = true,
                gameWon = gameWon
            )
        }

        // Vault経済報酬の付与
        distributeEconomyRewards(winner)

        // ランキング看板を更新
        plugin.signManager.updateAllSigns()
    }

    /**
     * 勝利チームにVault経済報酬を付与
     */
    private fun distributeEconomyRewards(winner: Team?) {
        if (!plugin.vaultIntegration.isAvailable()) return
        if (winner == null) return // 引き分けは報酬なし

        val rewardPerEnemy = plugin.config.getInt("economy.reward-per-enemy", 100).toDouble()
        if (rewardPerEnemy <= 0) return

        // 敵チームの合計人数を計算
        val enemyCount = getActiveTeams().filter { it != winner }.sumOf { teams[it]?.size ?: 0 }
        if (enemyCount == 0) return

        val totalReward = rewardPerEnemy * enemyCount

        // 勝利チームの全プレイヤーに報酬を付与
        val winnerPlayers = teams[winner] ?: return

        winnerPlayers.forEach { uuid ->
            val player = org.bukkit.Bukkit.getPlayer(uuid)
            if (player != null && player.isOnline) {
                plugin.vaultIntegration.deposit(player, totalReward)
                player.sendMessage(plugin.languageManager.getMessageAsComponent("economy.victory-reward",
                    "amount" to totalReward.toInt().toString()))
            }
        }

        plugin.logger.info("[EasyCTF] Victory rewards: ${totalReward.toInt()}G to each winner (${enemyCount} enemies × ${rewardPerEnemy.toInt()}G)")
    }
}