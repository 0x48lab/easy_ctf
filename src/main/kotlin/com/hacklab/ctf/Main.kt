package com.hacklab.ctf

import com.hacklab.ctf.commands.CTFCommand
import com.hacklab.ctf.listeners.GameListener
import com.hacklab.ctf.managers.GameManager
import com.hacklab.ctf.managers.LanguageManager
import com.hacklab.ctf.managers.EquipmentManager
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

    override fun onEnable() {
        instance = this
        
        saveDefaultConfig()
        
        languageManager = LanguageManager(this)
        equipmentManager = EquipmentManager(this)
        gameManager = GameManager(this)
        
        getCommand("ctf")?.setExecutor(CTFCommand(this))
        
        server.pluginManager.registerEvents(GameListener(this), this)
        
        logger.info(languageManager.getGeneralMessage("enabled"))
    }

    override fun onDisable() {
        if (::gameManager.isInitialized) {
            gameManager.stopGame()
        }
        
        if (::languageManager.isInitialized) {
            logger.info(languageManager.getGeneralMessage("disabled"))
        } else {
            logger.info("EasyCTF has been disabled!")
        }
    }
}
