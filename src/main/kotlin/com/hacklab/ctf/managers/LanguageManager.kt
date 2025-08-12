package com.hacklab.ctf.managers

import com.hacklab.ctf.Main
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader

class LanguageManager(private val plugin: Main) {
    
    private lateinit var languageConfig: FileConfiguration
    private var currentLanguage: String = "en"
    
    init {
        loadLanguage()
    }
    
    private fun loadLanguage() {
        currentLanguage = plugin.config.getString("language", "en") ?: "en"
        
        val langFileName = "lang_${currentLanguage}.yml"
        val langFile = File(plugin.dataFolder, langFileName)
        
        // リソースから言語ファイルをコピー（存在しない場合）
        if (!langFile.exists()) {
            plugin.saveResource(langFileName, false)
        }
        
        languageConfig = if (langFile.exists()) {
            YamlConfiguration.loadConfiguration(langFile)
        } else {
            // フォールバック: リソースから直接読み込み
            plugin.getResource(langFileName)?.let { resource ->
                YamlConfiguration.loadConfiguration(InputStreamReader(resource))
            } ?: YamlConfiguration()
        }
    }
    
    fun getMessage(key: String, vararg replacements: Pair<String, String>): String {
        val value = languageConfig.get(key)
        
        // 値が文字列でない場合（ConfigurationSectionなど）は、そのまま文字列化せずにエラーメッセージを返す
        var message = when (value) {
            is String -> value
            null -> "MissingText: $key"
            else -> {
                // ConfigurationSectionなどのオブジェクトの場合
                plugin.logger.warning("Invalid language key: $key returned ${value.javaClass.simpleName}")
                "MissingText: $key"
            }
        }
        
        // プレースホルダーを置換
        for ((placeholder, value) in replacements) {
            message = message.replace("{$placeholder}", value)
        }
        
        // &形式のカラーコードを§形式に変換
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', message)
    }
    
    fun reloadLanguage() {
        loadLanguage()
    }
    
    fun getCurrentLanguage(): String = currentLanguage
    
    // よく使用されるメッセージのヘルパーメソッド
    fun getPhaseMessage(phase: String, action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("phase.$phase-$action", *replacements)
    }
    
    fun getTeamMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("team.$action", *replacements)
    }
    
    fun getCommandMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("command.$action", *replacements)
    }
    
    fun getGameMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("game.$action", *replacements)
    }
    
    fun getBlockMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("block.$action", *replacements)
    }
    
    fun getArmorMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("armor.$action", *replacements)
    }
    
    fun getTimeMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("time.$action", *replacements)
    }
    
    fun getGeneralMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("general.$action", *replacements)
    }
    
    fun getResultMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("result.$action", *replacements)
    }
    
    fun getUIMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("ui.$action", *replacements)
    }
    
    fun getTeamsMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("teams.$action", *replacements)
    }
    
    fun getCommandExtendedMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("command-extended.$action", *replacements)
    }
    
    fun getGameStateMessage(action: String, vararg replacements: Pair<String, String>): String {
        return getMessage("game-states.$action", *replacements)
    }
    
    /**
     * メッセージをComponentとして取得（レガシーカラーコードを自動変換）
     */
    fun getMessageAsComponent(key: String, vararg replacements: Pair<String, String>): Component {
        var message = languageConfig.getString(key, "Missing message: $key") ?: "Missing message: $key"
        
        // プレースホルダーを置換
        for ((placeholder, value) in replacements) {
            message = message.replace("{$placeholder}", value)
        }
        
        // §記号を&記号に変換してからデシリアライズ（既存の§と&の両方に対応）
        message = message.replace('§', '&')
        
        // &でエンコードされたレガシーカラーコードをComponentに変換
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message)
    }
}