package com.hacklab.ctf.listeners

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManager
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ChatListener(private val plugin: Main) : Listener {
    
    private val gameManager = plugin.gameManager as GameManager
    
    @EventHandler(priority = EventPriority.LOWEST)
    fun onAsyncChat(event: AsyncChatEvent) {
        val player = event.player
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        
        // 対話形式のセッション中
        if (gameManager.isInSetupSession(player)) {
            event.isCancelled = true
            gameManager.handleChatInput(player, message)
            return
        }
    }
}