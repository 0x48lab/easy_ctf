package com.hacklab.ctf.listeners

import com.hacklab.ctf.Main
import com.hacklab.ctf.managers.GameManagerNew
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ChatListener(private val plugin: Main) : Listener {
    
    private val gameManager = plugin.gameManager as GameManagerNew
    
    @EventHandler(priority = EventPriority.LOWEST)
    fun onAsyncChat(event: AsyncChatEvent) {
        val player = event.player
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        
        // 対話形式のゲーム作成中
        if (gameManager.isInCreationSession(player)) {
            event.isCancelled = true
            gameManager.handleCreationInput(player, message)
            return
        }
        
        // 対話形式のゲーム更新中
        if (gameManager.isInUpdateSession(player)) {
            event.isCancelled = true
            gameManager.handleUpdateInput(player, message)
            return
        }
    }
}