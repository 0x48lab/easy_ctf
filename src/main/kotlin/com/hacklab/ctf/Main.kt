package com.hacklab.ctf

import com.hacklab.ctf.commands.CTFCommandNew
import com.hacklab.ctf.listeners.GameListenerNew
import com.hacklab.ctf.listeners.ChatListener
import com.hacklab.ctf.managers.GameManager
import com.hacklab.ctf.managers.LanguageManager
import com.hacklab.ctf.managers.EquipmentManager
import com.hacklab.ctf.shop.ShopManager
import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {
    
    companion object {
        lateinit var instance: Main
            private set
    }
    
    lateinit var gameManager: GameManager
        private set
    lateinit var languageManager: LanguageManager
        private set
    lateinit var equipmentManager: EquipmentManager
        private set
    lateinit var shopManager: ShopManager
        private set

    override fun onEnable() {
        instance = this
        
        saveDefaultConfig()
        
        // Config値の確認
        logger.info("[EasyCTF] Loading configuration...")
        logger.info("[EasyCTF] build-phase-gamemode: ${config.getString("default-phases.build-phase-gamemode")}")
        
        languageManager = LanguageManager(this)
        equipmentManager = EquipmentManager(this)
        shopManager = ShopManager(this)
        gameManager = GameManager(this)
        
        getCommand("ctf")?.setExecutor(CTFCommandNew(this))
        
        server.pluginManager.registerEvents(GameListenerNew(this), this)
        server.pluginManager.registerEvents(ChatListener(this), this)
        
        logger.info(languageManager.getGeneralMessage("enabled"))
    }

    override fun onDisable() {
        if (::gameManager.isInitialized) {
            // 全ゲームを停止
            gameManager.getAllGames().values.forEach { game ->
                if (game.state != com.hacklab.ctf.utils.GameState.WAITING) {
                    game.stop()
                }
            }
        }
        
        // 全てのテンポラリワールドをクリーンアップ
        val worldManager = com.hacklab.ctf.world.WorldManager(this)
        worldManager.cleanupAllTempWorlds()
        
        if (::languageManager.isInitialized) {
            logger.info(languageManager.getGeneralMessage("disabled"))
        } else {
            logger.info("EasyCTF has been disabled!")
        }
    }
}
