package com.hacklab.ctf

import com.hacklab.ctf.utils.GamePhase
import com.hacklab.ctf.utils.GameState
import com.hacklab.ctf.utils.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.*
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import java.time.Duration
import java.util.*

class Game(
    val plugin: Main,
    val name: String,
    val world: World
) {
    var state = GameState.WAITING
    var phase = GamePhase.BUILD
    
    // チーム管理
    val redTeam = mutableSetOf<UUID>()
    val blueTeam = mutableSetOf<UUID>()
    val disconnectedPlayers = mutableMapOf<UUID, Team>() // 切断中のプレイヤー
    
    // 位置設定
    var redFlagLocation: Location? = null
    var blueSpawnLocation: Location? = null
    var redSpawnLocation: Location? = null
    var blueFlagLocation: Location? = null
    
    // 設定
    var minPlayers = plugin.config.getInt("default-game.min-players", 2)
    var maxPlayersPerTeam = plugin.config.getInt("default-game.max-players-per-team", 10)
    var respawnDelay = plugin.config.getInt("default-game.respawn-delay", 5)
    var buildDuration = plugin.config.getInt("default-phases.build-duration", 300)
    var combatDuration = plugin.config.getInt("default-phases.combat-duration", 600)
    var resultDuration = plugin.config.getInt("default-phases.result-duration", 60)
    var buildPhaseGameMode = GameMode.valueOf(
        plugin.config.getString("default-phases.build-phase-gamemode", "ADVENTURE")!!
    )
    
    // ゲーム状態
    var score = mutableMapOf(Team.RED to 0, Team.BLUE to 0)
    var currentPhaseTime = 0
    var autoStartCountdown = -1
    
    // 旗管理
    var redFlagCarrier: UUID? = null
    var blueFlagCarrier: UUID? = null
    val droppedFlags = mutableMapOf<Location, Pair<Team, Long>>() // 位置 -> (チーム, ドロップ時刻)
    
    // UI要素
    var scoreboard: Scoreboard? = null
    var objective: Objective? = null
    var bossBar: BossBar? = null
    
    // スポーン保護
    val spawnProtection = mutableMapOf<UUID, Long>() // プレイヤー -> 保護終了時刻
    
    // ActionBarメッセージのクールダウン管理
    private val actionBarCooldown = mutableMapOf<UUID, Long>() // プレイヤー -> 次回表示可能時刻
    private val actionBarErrorDisplay = mutableMapOf<UUID, Pair<String, Long>>() // プレイヤー -> (エラーメッセージ, 表示終了時刻)
    
    // タスク
    private var gameTask: BukkitRunnable? = null
    
    fun addPlayer(player: Player, team: Team? = null): Boolean {
        if (state != GameState.WAITING) {
            plugin.logger.warning("Player ${player.name} cannot join game $name: state is $state")
            when (state) {
                GameState.STARTING, GameState.RUNNING -> {
                    player.sendMessage(Component.text("このゲームは既に開始されています", NamedTextColor.RED))
                }
                GameState.ENDING -> {
                    player.sendMessage(Component.text("このゲームは終了中です", NamedTextColor.RED))
                }
                GameState.WAITING -> {
                    // このケースは発生しないはず
                }
            }
            return false
        }
        
        val selectedTeam = team ?: selectTeamForPlayer()
        
        if (selectedTeam == Team.RED) {
            if (redTeam.size >= maxPlayersPerTeam) {
                player.sendMessage(Component.text("赤チームが満員です", NamedTextColor.RED))
                return false
            }
            redTeam.add(player.uniqueId)
        } else {
            if (blueTeam.size >= maxPlayersPerTeam) {
                player.sendMessage(Component.text("青チームが満員です", NamedTextColor.RED))
                return false
            }
            blueTeam.add(player.uniqueId)
        }
        
        // スコアボード表示
        setupScoreboard(player)
        updateScoreboard()
        
        player.sendMessage(Component.text("ゲーム '$name' の${selectedTeam.displayName}に参加しました", selectedTeam.color))
        
        // 自動開始チェック
        checkAutoStart()
        
        return true
    }
    
    fun removePlayer(player: Player) {
        redTeam.remove(player.uniqueId)
        blueTeam.remove(player.uniqueId)
        disconnectedPlayers.remove(player.uniqueId)
        
        // UI削除
        player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        bossBar?.removePlayer(player)
        
        // 旗を持っていた場合
        if (redFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.RED)
        } else if (blueFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.BLUE)
        }
        
        player.sendMessage(Component.text("ゲーム '$name' から退出しました", NamedTextColor.YELLOW))
    }
    
    fun handleDisconnect(player: Player) {
        val team = getPlayerTeam(player.uniqueId) ?: return
        disconnectedPlayers[player.uniqueId] = team
        
        // 旗を持っていた場合
        if (redFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.RED)
        } else if (blueFlagCarrier == player.uniqueId) {
            dropFlag(player, Team.BLUE)
        }
    }
    
    fun handleReconnect(player: Player) {
        val team = disconnectedPlayers.remove(player.uniqueId) ?: return
        
        // UIの再設定
        setupScoreboard(player)
        bossBar?.addPlayer(player)
        
        // 現在のフェーズに応じた装備を配布
        when (phase) {
            GamePhase.BUILD -> giveBuildPhaseItems(player, team)
            GamePhase.COMBAT -> giveCombatPhaseItems(player, team)
            GamePhase.RESULT -> {}
        }
        
        // スポーン地点に転送
        teleportToSpawn(player, team)
    }
    
    private fun selectTeamForPlayer(): Team {
        return when {
            redTeam.size < blueTeam.size -> Team.RED
            blueTeam.size < redTeam.size -> Team.BLUE
            else -> if (Random().nextBoolean()) Team.RED else Team.BLUE
        }
    }
    
    fun getPlayerTeam(uuid: UUID): Team? {
        return when {
            redTeam.contains(uuid) -> Team.RED
            blueTeam.contains(uuid) -> Team.BLUE
            else -> null
        }
    }
    
    fun getAllPlayers(): Set<Player> {
        return (redTeam + blueTeam).mapNotNull { Bukkit.getPlayer(it) }.toSet()
    }
    
    private fun checkAutoStart() {
        if (state != GameState.WAITING) return
        if (redTeam.size + blueTeam.size < minPlayers) {
            if (autoStartCountdown > 0) {
                autoStartCountdown = -1
                getAllPlayers().forEach {
                    it.sendMessage(Component.text("人数不足のため自動開始がキャンセルされました", NamedTextColor.YELLOW))
                }
            }
            return
        }
        
        if (autoStartCountdown < 0) {
            autoStartCountdown = 30
            object : BukkitRunnable() {
                override fun run() {
                    if (autoStartCountdown <= 0 || state != GameState.WAITING) {
                        cancel()
                        if (autoStartCountdown == 0) {
                            start()
                        }
                        return
                    }
                    
                    if (redTeam.size + blueTeam.size < minPlayers) {
                        autoStartCountdown = -1
                        cancel()
                        getAllPlayers().forEach {
                            it.sendMessage(Component.text("人数不足のため自動開始がキャンセルされました", NamedTextColor.YELLOW))
                        }
                        return
                    }
                    
                    if (autoStartCountdown % 10 == 0 || autoStartCountdown <= 5) {
                        getAllPlayers().forEach {
                            it.sendMessage(Component.text("ゲーム開始まで: ${autoStartCountdown}秒", NamedTextColor.GREEN))
                        }
                    }
                    
                    autoStartCountdown--
                }
            }.runTaskTimer(plugin, 0L, 20L)
        }
    }
    
    fun start(): Boolean {
        if (state != GameState.WAITING) {
            plugin.logger.warning("Game $name cannot start: current state is $state")
            getAllPlayers().forEach {
                it.sendMessage(Component.text("ゲームは既に${state}状態です", NamedTextColor.RED))
            }
            return false
        }
        
        // 最小人数チェック
        if (redTeam.size + blueTeam.size < 2) {
            getAllPlayers().forEach {
                it.sendMessage(Component.text("ゲームを開始するには最低2名必要です", NamedTextColor.RED))
            }
            return false
        }
        
        // 必須設定チェック
        if (redFlagLocation == null || blueFlagLocation == null) {
            getAllPlayers().forEach {
                it.sendMessage(Component.text("両チームの旗位置を設定する必要があります", NamedTextColor.RED))
            }
            return false
        }
        
        state = GameState.STARTING
        phase = GamePhase.BUILD
        currentPhaseTime = buildDuration
        
        // BossBar作成
        bossBar = Bukkit.createBossBar(
            "建築フェーズ - 残り時間: ${formatTime(currentPhaseTime)}",
            BarColor.GREEN,
            BarStyle.SOLID
        )
        
        // プレイヤーの準備
        getAllPlayers().forEach { player ->
            val team = getPlayerTeam(player.uniqueId)!!
            
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード設定
            player.gameMode = buildPhaseGameMode
            
            // スポーン地点に転送
            teleportToSpawn(player, team)
            
            // 建築フェーズアイテム配布
            giveBuildPhaseItems(player, team)
            
            // BossBar追加
            bossBar?.addPlayer(player)
            
            // タイトル表示
            player.showTitle(Title.title(
                Component.text("ゲーム開始！", NamedTextColor.GREEN),
                Component.text("建築フェーズ - 防御を構築しよう", NamedTextColor.YELLOW),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(500)
                )
            ))
        }
        
        // 旗とスポーン地点の設置
        setupFlags()
        setupSpawnAreas()
        
        state = GameState.RUNNING
        
        // ゲームループ開始
        startGameLoop()
        
        return true
    }
    
    private fun startGameLoop() {
        gameTask = object : BukkitRunnable() {
            override fun run() {
                if (state != GameState.RUNNING) {
                    cancel()
                    return
                }
                
                // 時間更新
                currentPhaseTime--
                
                // ドロップした旗のチェック
                checkDroppedFlags()
                
                // フェーズ遷移チェック
                if (currentPhaseTime <= 0) {
                    when (phase) {
                        GamePhase.BUILD -> transitionToCombatPhase()
                        GamePhase.COMBAT -> transitionToResultPhase()
                        GamePhase.RESULT -> endGame()
                    }
                }
                
                // UI更新
                updateBossBar()
                updateScoreboard()
                updateActionBarGuides()
            }
        }
        gameTask?.runTaskTimer(plugin, 0L, 20L)
    }
    
    private fun transitionToCombatPhase() {
        phase = GamePhase.COMBAT
        currentPhaseTime = combatDuration
        
        bossBar?.setTitle("戦闘フェーズ - 残り時間: ${formatTime(currentPhaseTime)}")
        bossBar?.color = BarColor.RED
        
        getAllPlayers().forEach { player ->
            val team = getPlayerTeam(player.uniqueId)!!
            
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード変更
            player.gameMode = GameMode.SURVIVAL
            
            // スポーン地点に転送
            teleportToSpawn(player, team)
            
            // 戦闘フェーズアイテム配布
            giveCombatPhaseItems(player, team)
            
            // タイトル表示
            player.showTitle(Title.title(
                Component.text("戦闘フェーズ開始！", NamedTextColor.RED),
                Component.text("敵の旗を奪取せよ！", NamedTextColor.YELLOW),
                Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(500)
                )
            ))
        }
    }
    
    private fun transitionToResultPhase() {
        phase = GamePhase.RESULT
        currentPhaseTime = resultDuration
        
        val winner = when {
            score[Team.RED]!! > score[Team.BLUE]!! -> Team.RED
            score[Team.BLUE]!! > score[Team.RED]!! -> Team.BLUE
            else -> null
        }
        
        bossBar?.setTitle("試合終了！")
        bossBar?.color = BarColor.YELLOW
        
        getAllPlayers().forEach { player ->
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード変更
            player.gameMode = GameMode.SPECTATOR
            
            // 結果表示
            if (winner != null) {
                player.showTitle(Title.title(
                    Component.text("${winner.displayName}の勝利！", winner.color),
                    Component.text("スコア: 赤 ${score[Team.RED]} - ${score[Team.BLUE]} 青", NamedTextColor.WHITE),
                    Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500)
                    )
                ))
            } else {
                player.showTitle(Title.title(
                    Component.text("引き分け！", NamedTextColor.YELLOW),
                    Component.text("スコア: 赤 ${score[Team.RED]} - ${score[Team.BLUE]} 青", NamedTextColor.WHITE),
                    Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500)
                    )
                ))
            }
        }
    }
    
    fun stop() {
        plugin.logger.info("Stopping game: $name")
        state = GameState.ENDING
        
        // タスクキャンセル
        gameTask?.cancel()
        
        // プレイヤーのUUIDリストをコピー（forEach中の変更を避けるため）
        val playerUUIDs = (redTeam + blueTeam).toList()
        
        // プレイヤー処理
        playerUUIDs.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
            // インベントリクリア
            player.inventory.clear()
            
            // ゲームモード戻す
            player.gameMode = GameMode.SURVIVAL
            
            // 発光効果を解除
            player.isGlowing = false
            
            // UI削除
            player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
            bossBar?.removePlayer(player)
            
            // ワールドスポーンに転送
            player.teleport(world.spawnLocation)
            
            player.sendMessage(Component.text("ゲームが終了しました", NamedTextColor.YELLOW))
            
            // GameManagerからプレイヤーを削除
            val gameManager = plugin.gameManager as com.hacklab.ctf.managers.GameManagerNew
            gameManager.removePlayerFromGame(player)
        }
        
        // BossBar削除
        bossBar?.removeAll()
        bossBar = null
        
        // 旗とスポーン装飾を削除
        cleanupGameBlocks()
        
        // データクリアの前に状態をリセット
        state = GameState.WAITING
        phase = GamePhase.BUILD
        currentPhaseTime = 0
        autoStartCountdown = -1
        
        // データクリア
        redTeam.clear()
        blueTeam.clear()
        disconnectedPlayers.clear()
        score.clear()
        score[Team.RED] = 0
        score[Team.BLUE] = 0
        droppedFlags.clear()
        spawnProtection.clear()
        actionBarCooldown.clear()
        actionBarErrorDisplay.clear()
        redFlagCarrier = null
        blueFlagCarrier = null
    }
    
    private fun endGame() {
        plugin.logger.info("Game $name ended naturally")
        stop()
        
        // GameManagerから削除はしない（再利用可能）
    }
    
    // 以下、ヘルパーメソッド
    
    private fun teleportToSpawn(player: Player, team: Team) {
        val spawnLocation = when (team) {
            Team.RED -> redSpawnLocation ?: redFlagLocation
            Team.BLUE -> blueSpawnLocation ?: blueFlagLocation
        }
        
        if (spawnLocation != null) {
            player.teleport(spawnLocation)
        } else {
            player.sendMessage(Component.text("スポーン地点が設定されていません", NamedTextColor.RED))
            plugin.logger.warning("Game $name: No spawn location for team $team")
        }
    }
    
    private fun giveBuildPhaseItems(player: Player, team: Team) {
        val inv = player.inventory
        
        // 基本ツール
        inv.addItem(ItemStack(Material.WOODEN_PICKAXE))
        inv.addItem(ItemStack(Material.WOODEN_AXE))
        inv.addItem(ItemStack(Material.WOODEN_SHOVEL))
        
        // 建築ブロック
        inv.addItem(ItemStack(Material.OAK_PLANKS, 64))
        inv.addItem(ItemStack(Material.COBBLESTONE, 64))
        inv.addItem(ItemStack(Material.DIRT, 64))
        inv.addItem(ItemStack(Material.OAK_LOG, 32))
        
        // 食料
        inv.addItem(ItemStack(Material.BREAD, 16))
        
        // チームカラーのウール
        val woolType = when (team) {
            Team.RED -> Material.RED_WOOL
            Team.BLUE -> Material.BLUE_WOOL
        }
        inv.addItem(ItemStack(woolType, 32))
    }
    
    private fun giveCombatPhaseItems(player: Player, team: Team) {
        val inv = player.inventory
        
        // 武器
        inv.addItem(ItemStack(Material.IRON_SWORD))
        inv.addItem(ItemStack(Material.BOW))
        inv.addItem(ItemStack(Material.ARROW, 32))
        
        // 防具（チームカラーの革防具）
        val color = when (team) {
            Team.RED -> org.bukkit.Color.RED
            Team.BLUE -> org.bukkit.Color.BLUE
        }
        
        val helmet = ItemStack(Material.LEATHER_HELMET)
        val helmetMeta = helmet.itemMeta as org.bukkit.inventory.meta.LeatherArmorMeta
        helmetMeta.setColor(color)
        helmetMeta.isUnbreakable = true
        helmet.itemMeta = helmetMeta
        
        val chestplate = ItemStack(Material.LEATHER_CHESTPLATE)
        val chestMeta = chestplate.itemMeta as org.bukkit.inventory.meta.LeatherArmorMeta
        chestMeta.setColor(color)
        chestMeta.isUnbreakable = true
        chestplate.itemMeta = chestMeta
        
        val leggings = ItemStack(Material.LEATHER_LEGGINGS)
        val legMeta = leggings.itemMeta as org.bukkit.inventory.meta.LeatherArmorMeta
        legMeta.setColor(color)
        legMeta.isUnbreakable = true
        leggings.itemMeta = legMeta
        
        val boots = ItemStack(Material.LEATHER_BOOTS)
        val bootMeta = boots.itemMeta as org.bukkit.inventory.meta.LeatherArmorMeta
        bootMeta.setColor(color)
        bootMeta.isUnbreakable = true
        boots.itemMeta = bootMeta
        
        // 防具を装備
        player.inventory.helmet = helmet
        player.inventory.chestplate = chestplate
        player.inventory.leggings = leggings
        player.inventory.boots = boots
        
        // 食料
        inv.addItem(ItemStack(Material.GOLDEN_APPLE, 2))
        inv.addItem(ItemStack(Material.COOKED_BEEF, 16))
        
        // ツール
        inv.addItem(ItemStack(Material.IRON_PICKAXE))
        inv.addItem(ItemStack(Material.IRON_AXE))
        
        // 建築ブロック（少量）
        inv.addItem(ItemStack(Material.OAK_PLANKS, 32))
        inv.addItem(ItemStack(Material.COBBLESTONE, 32))
    }
    
    private fun setupFlags() {
        // 旗の設置（ビーコン）
        Team.values().forEach { team ->
            val flagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
            } ?: return@forEach
            
            setupFlagBeacon(flagLocation, team)
        }
    }
    
    private fun setupFlagBeacon(location: Location, team: Team) {
        val world = location.world
        
        // ビーコンを設置
        location.block.type = Material.BEACON
        
        // 3x3の鉄ブロックベースを設置
        for (x in -1..1) {
            for (z in -1..1) {
                location.clone().add(x.toDouble(), -1.0, z.toDouble()).block.type = Material.IRON_BLOCK
            }
        }
        
        // チーム色のステンドグラスを上に設置
        val glassType = when (team) {
            Team.RED -> Material.RED_STAINED_GLASS
            Team.BLUE -> Material.BLUE_STAINED_GLASS
        }
        location.clone().add(0.0, 1.0, 0.0).block.type = glassType
        
        // 旗位置を記録
        when (team) {
            Team.RED -> redFlagLocation = location
            Team.BLUE -> blueFlagLocation = location
        }
    }
    
    private fun setupSpawnAreas() {
        // スポーンエリアの装飾（スポーン地点が設定されている場合のみ）
        Team.values().forEach { team ->
            val spawnLocation = when (team) {
                Team.RED -> redSpawnLocation
                Team.BLUE -> blueSpawnLocation
            } ?: return@forEach  // スポーン地点が設定されていない場合はスキップ
            
            setupSpawnDecoration(spawnLocation, team)
        }
    }
    
    private fun setupSpawnDecoration(location: Location, team: Team) {
        // チーム色のコンクリートで3x3の床のみ
        val concreteType = when (team) {
            Team.RED -> Material.RED_CONCRETE
            Team.BLUE -> Material.BLUE_CONCRETE
        }
        
        for (x in -1..1) {
            for (z in -1..1) {
                location.clone().add(x.toDouble(), -1.0, z.toDouble()).block.type = concreteType
            }
        }
    }
    
    private fun cleanupGameBlocks() {
        // 旗の削除
        Team.values().forEach { team ->
            val flagLocation = when (team) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
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
        Team.values().forEach { team ->
            val spawnLocation = when (team) {
                Team.RED -> redSpawnLocation
                Team.BLUE -> blueSpawnLocation
            } ?: return@forEach  // スポーン地点が設定されていない場合はスキップ
            
            // コンクリート床を削除
            for (x in -1..1) {
                for (z in -1..1) {
                    spawnLocation.clone().add(x.toDouble(), -1.0, z.toDouble()).block.type = Material.AIR
                }
            }
        }
    }
    
    private fun setupScoreboard(player: Player) {
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().newScoreboard
            objective = scoreboard!!.registerNewObjective("ctf_$name", "dummy", 
                Component.text("CTF - $name", NamedTextColor.GOLD))
            objective!!.displaySlot = DisplaySlot.SIDEBAR
        }
        player.scoreboard = scoreboard!!
    }
    
    fun updateScoreboard() {
        val obj = objective ?: return
        
        // 既存のエントリをクリア
        scoreboard?.entries?.forEach { entry ->
            scoreboard?.resetScores(entry)
        }
        
        var line = 15
        
        // ゲーム情報
        obj.getScore("§e§l==============").score = line--
        obj.getScore("§fフェーズ: §a${getPhaseDisplayName()}").score = line--
        obj.getScore("§f残り時間: §e${formatTime(currentPhaseTime)}").score = line--
        obj.getScore("§e§l==============").score = line--
        obj.getScore(" ").score = line--
        
        // スコア
        if (phase == GamePhase.COMBAT || phase == GamePhase.RESULT) {
            obj.getScore("§c赤チーム: §f${score[Team.RED] ?: 0}").score = line--
            obj.getScore("§9青チーム: §f${score[Team.BLUE] ?: 0}").score = line--
            obj.getScore("  ").score = line--
        }
        
        // チーム人数
        obj.getScore("§c赤: §f${redTeam.size}名").score = line--
        obj.getScore("§9青: §f${blueTeam.size}名").score = line--
        obj.getScore("   ").score = line--
        
        // 旗の状態
        if (phase == GamePhase.COMBAT) {
            if (redFlagCarrier != null) {
                val carrier = Bukkit.getPlayer(redFlagCarrier!!)
                obj.getScore("§c赤旗: §e${carrier?.name ?: "不明"}").score = line--
            }
            if (blueFlagCarrier != null) {
                val carrier = Bukkit.getPlayer(blueFlagCarrier!!)
                obj.getScore("§9青旗: §e${carrier?.name ?: "不明"}").score = line--
            }
        }
    }
    
    private fun getPhaseDisplayName(): String {
        return when (phase) {
            GamePhase.BUILD -> "建築"
            GamePhase.COMBAT -> "戦闘"
            GamePhase.RESULT -> "結果"
        }
    }
    
    private fun updateBossBar() {
        val bar = bossBar ?: return
        
        val phaseText = when (phase) {
            GamePhase.BUILD -> "建築フェーズ"
            GamePhase.COMBAT -> "戦闘フェーズ"
            GamePhase.RESULT -> "リザルト"
        }
        
        val timeText = formatTime(currentPhaseTime)
        bar.setTitle("$phaseText - 残り時間: $timeText")
        
        // 進行度を更新
        val totalTime = when (phase) {
            GamePhase.BUILD -> buildDuration
            GamePhase.COMBAT -> combatDuration
            GamePhase.RESULT -> resultDuration
        }
        
        val progress = currentPhaseTime.toDouble() / totalTime.toDouble()
        bar.progress = progress.coerceIn(0.0, 1.0)
    }
    
    fun dropFlag(player: Player, team: Team) {
        val carrier = when (team) {
            Team.RED -> redFlagCarrier
            Team.BLUE -> blueFlagCarrier
        }
        
        if (carrier != player.uniqueId) return
        
        // キャリアをクリア
        when (team) {
            Team.RED -> redFlagCarrier = null
            Team.BLUE -> blueFlagCarrier = null
        }
        
        // グロー効果を解除
        player.isGlowing = false
        
        // 旗をドロップ
        val itemStack = ItemStack(Material.BEACON)
        val meta = itemStack.itemMeta
        meta.displayName(Component.text("${team.displayName}の旗", team.color))
        meta.isUnbreakable = true
        itemStack.itemMeta = meta
        
        val droppedItem = player.world.dropItem(player.location, itemStack)
        droppedItem.setGlowing(true)
        droppedItem.customName(Component.text("${team.displayName}の旗", team.color))
        droppedItem.isCustomNameVisible = true
        droppedItem.isInvulnerable = true
        
        // ドロップ情報を記録
        droppedFlags[player.location] = Pair(team, System.currentTimeMillis())
        
        // メッセージ
        getAllPlayers().forEach {
            it.sendMessage(Component.text("${team.displayName}の旗がドロップされました！", NamedTextColor.YELLOW))
        }
    }
    
    private fun checkDroppedFlags() {
        val iterator = droppedFlags.iterator()
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
                
                // 旗を元の位置に再設置
                val flagLocation = when (team) {
                    Team.RED -> redFlagLocation
                    Team.BLUE -> blueFlagLocation
                } ?: continue
                
                setupFlagBeacon(flagLocation, team)
                
                iterator.remove()
                
                getAllPlayers().forEach {
                    it.sendMessage(Component.text("${team.displayName}の旗が元の位置に戻りました", team.color))
                }
            }
        }
    }
    
    fun pickupFlag(player: Player, item: org.bukkit.entity.Item): Boolean {
        val team = getPlayerTeam(player.uniqueId) ?: return false
        
        // アイテムがビーコンかチェック
        if (item.itemStack.type != Material.BEACON) return false
        
        // どちらのチームの旗かを判定
        val flagTeam = when {
            item.customName()?.contains(Component.text("赤")) == true -> Team.RED
            item.customName()?.contains(Component.text("青")) == true -> Team.BLUE
            else -> return false
        }
        
        // 自分のチームの旗を拾った場合
        if (team == flagTeam) {
            // ドロップフラグから削除
            droppedFlags.entries.removeIf { (location, data) ->
                data.first == flagTeam && location.distance(item.location) < 2.0
            }
            
            // アイテムを削除
            item.remove()
            
            // 旗を元の位置に戻す
            val flagLocation = when (flagTeam) {
                Team.RED -> redFlagLocation
                Team.BLUE -> blueFlagLocation
            } ?: return false
            
            setupFlagBeacon(flagLocation, flagTeam)
            
            // メッセージ
            getAllPlayers().forEach {
                it.sendMessage(Component.text("${player.name}が${flagTeam.displayName}の旗を回収しました！", team.color))
            }
            
            return true
        }
        
        // 敵の旗を拾う場合
        // 既に旗を持っているかチェック
        if (player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier) {
            player.sendMessage(Component.text("既に旗を持っています", NamedTextColor.RED))
            return false
        }
        
        // 旗を拾う
        when (flagTeam) {
            Team.RED -> redFlagCarrier = player.uniqueId
            Team.BLUE -> blueFlagCarrier = player.uniqueId
        }
        
        // ドロップフラグから削除
        droppedFlags.entries.removeIf { (location, data) ->
            data.first == flagTeam && location.distance(item.location) < 2.0
        }
        
        // アイテムを削除
        item.remove()
        
        // プレイヤーに発光効果
        player.isGlowing = true
        
        // メッセージ
        getAllPlayers().forEach {
            it.sendMessage(Component.text("${player.name}が${flagTeam.displayName}の旗を取得しました！", NamedTextColor.YELLOW))
        }
        
        return true
    }
    
    fun captureFlag(player: Player): Boolean {
        val team = getPlayerTeam(player.uniqueId) ?: return false
        
        // 旗を持っているかチェック
        val carriedFlagTeam = when (player.uniqueId) {
            redFlagCarrier -> Team.RED
            blueFlagCarrier -> Team.BLUE
            else -> return false
        }
        
        // 自分のチームの旗拠点にいるかチェック
        val ownFlagLocation = when (team) {
            Team.RED -> redFlagLocation
            Team.BLUE -> blueFlagLocation
        } ?: return false
        
        if (player.location.distance(ownFlagLocation) > 3.0) {
            return false
        }
        
        // 自分のチームの旗が自陣にあるかチェック
        val ownFlagIsAtBase = when (team) {
            Team.RED -> redFlagCarrier == null && !droppedFlags.any { it.value.first == Team.RED }
            Team.BLUE -> blueFlagCarrier == null && !droppedFlags.any { it.value.first == Team.BLUE }
        }
        
        if (!ownFlagIsAtBase) {
            // エラーメッセージを5秒間表示するように設定
            val now = System.currentTimeMillis()
            val lastShown = actionBarCooldown[player.uniqueId] ?: 0
            
            if (now - lastShown > 10000) { // 10秒に1回新規エラーを設定可能
                val errorMessage = "自チームの旗が自陣にないため、キャプチャーできません"
                actionBarErrorDisplay[player.uniqueId] = Pair(errorMessage, now + 5000) // 5秒間表示
                actionBarCooldown[player.uniqueId] = now
            }
            
            return false
        }
        
        // スコア加算
        score[team] = (score[team] ?: 0) + 1
        
        // キャリアをクリア
        when (carriedFlagTeam) {
            Team.RED -> redFlagCarrier = null
            Team.BLUE -> blueFlagCarrier = null
        }
        
        // グロー効果を解除
        player.isGlowing = false
        
        // 敵の旗を元の位置に戻す
        val enemyFlagLocation = when (carriedFlagTeam) {
            Team.RED -> redFlagLocation
            Team.BLUE -> blueFlagLocation
        } ?: return false
        
        setupFlagBeacon(enemyFlagLocation, carriedFlagTeam)
        
        // 全プレイヤーに通知
        getAllPlayers().forEach { p ->
            p.sendMessage(Component.text("${team.displayName}が得点しました！ (${player.name})", team.color))
            p.sendMessage(Component.text("現在のスコア - 赤: ${score[Team.RED]} 青: ${score[Team.BLUE]}", NamedTextColor.WHITE))
            
            // 効果音を再生
            p.playSound(p.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        }
        
        // タイトル表示
        getAllPlayers().forEach { p ->
            p.showTitle(Title.title(
                Component.text("${team.displayName}が得点！", team.color),
                Component.text("赤: ${score[Team.RED]} - ${score[Team.BLUE]} :青", NamedTextColor.WHITE),
                Title.Times.times(
                    Duration.ofMillis(250),
                    Duration.ofSeconds(2),
                    Duration.ofMillis(250)
                )
            ))
        }
        
        return true
    }
    
    fun handleRespawn(player: Player) {
        val team = getPlayerTeam(player.uniqueId) ?: return
        
        // 3秒間のスポーン保護を付与
        spawnProtection[player.uniqueId] = System.currentTimeMillis() + 3000
        
        // 保護中は光る
        player.isGlowing = true
        
        // 3秒後に保護を解除
        object : BukkitRunnable() {
            override fun run() {
                if (spawnProtection.remove(player.uniqueId) != null) {
                    player.isGlowing = false
                    player.sendMessage(Component.text("スポーン保護が解除されました", NamedTextColor.YELLOW))
                }
            }
        }.runTaskLater(plugin, 60L) // 3秒後
        
        // スポーン地点に転送
        teleportToSpawn(player, team)
        
        // 現在のフェーズに応じた装備を再配布
        when (phase) {
            GamePhase.BUILD -> giveBuildPhaseItems(player, team)
            GamePhase.COMBAT -> giveCombatPhaseItems(player, team)
            GamePhase.RESULT -> {}
        }
        
        player.sendMessage(Component.text("3秒間スポーン保護が有効です", NamedTextColor.GREEN))
    }
    
    fun isUnderSpawnProtection(player: Player): Boolean {
        val protectionEnd = spawnProtection[player.uniqueId] ?: return false
        return System.currentTimeMillis() < protectionEnd
    }
    
    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return String.format("%d:%02d", min, sec)
    }
    
    private fun updateActionBarGuides() {
        getAllPlayers().forEach { player ->
            val team = getPlayerTeam(player.uniqueId) ?: return@forEach
            
            // エラーメッセージが表示期間中の場合は優先して表示
            val now = System.currentTimeMillis()
            val errorData = actionBarErrorDisplay[player.uniqueId]
            if (errorData != null && now < errorData.second) {
                player.sendActionBar(Component.text(errorData.first, NamedTextColor.RED))
                return@forEach
            } else if (errorData != null) {
                // 期限切れのエラーメッセージを削除
                actionBarErrorDisplay.remove(player.uniqueId)
            }
            
            val message = when (phase) {
                GamePhase.BUILD -> {
                    // 建築フェーズ
                    Component.text("建築して防御を固めよう！", NamedTextColor.GREEN)
                }
                
                GamePhase.COMBAT -> {
                    // 戦闘フェーズ
                    val enemyTeam = if (team == Team.RED) Team.BLUE else Team.RED
                    
                    when {
                        // 自分が旗を持っている
                        player.uniqueId == redFlagCarrier || player.uniqueId == blueFlagCarrier -> {
                            Component.text("自陣に戻れ！", NamedTextColor.GOLD)
                        }
                        
                        // 自チームの旗が敵に取られている
                        (team == Team.RED && redFlagCarrier != null) || 
                        (team == Team.BLUE && blueFlagCarrier != null) -> {
                            val carrierName = when (team) {
                                Team.RED -> redFlagCarrier?.let { Bukkit.getPlayer(it)?.name } ?: "不明"
                                Team.BLUE -> blueFlagCarrier?.let { Bukkit.getPlayer(it)?.name } ?: "不明"
                            }
                            Component.text("旗が敵に取られた！取り返せ！($carrierName)", NamedTextColor.RED)
                        }
                        
                        // 自チームの旗がドロップしている
                        (team == Team.RED && droppedFlags.any { it.value.first == Team.RED }) ||
                        (team == Team.BLUE && droppedFlags.any { it.value.first == Team.BLUE }) -> {
                            Component.text("自チームの旗を回収せよ！", NamedTextColor.YELLOW)
                        }
                        
                        // 通常状態（敵の旗を取りに行く）
                        else -> {
                            Component.text("${enemyTeam.displayName}の旗を奪取せよ！", enemyTeam.color)
                        }
                    }
                }
                
                GamePhase.RESULT -> {
                    // リザルトフェーズは何も表示しない
                    return@forEach
                }
            }
            
            player.sendActionBar(message)
        }
    }
}