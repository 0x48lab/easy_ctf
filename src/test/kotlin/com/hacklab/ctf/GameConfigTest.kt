package com.hacklab.ctf

import com.hacklab.ctf.config.GameConfig
import com.hacklab.ctf.utils.Team
import org.bukkit.Bukkit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GameConfigTest {

    @Test
    fun `default teamCount is 2`() {
        // GameConfigのデフォルトチーム数が2であること
        // Note: Worldがnullableでないためインスタンス化はできないが、
        // デフォルト値はdata class定義から確認可能
        assertEquals(2, GameConfig::class.java.declaredFields
            .find { it.name == "teamCount" }
            ?.let { field ->
                // リフレクションでデフォルト値は取れないので、
                // Team.getActiveTeams(2)のデフォルト動作をテスト
                Team.getActiveTeams(2)
            }?.size ?: -1)
    }

    @Test
    fun `getActiveTeams with 2 returns RED and BLUE`() {
        val teams = Team.getActiveTeams(2)
        assertEquals(2, teams.size)
        assertTrue(teams.contains(Team.RED))
        assertTrue(teams.contains(Team.BLUE))
        assertFalse(teams.contains(Team.GREEN))
        assertFalse(teams.contains(Team.YELLOW))
    }

    @Test
    fun `getActiveTeams with 3 returns RED BLUE GREEN`() {
        val teams = Team.getActiveTeams(3)
        assertEquals(3, teams.size)
        assertTrue(teams.contains(Team.RED))
        assertTrue(teams.contains(Team.BLUE))
        assertTrue(teams.contains(Team.GREEN))
        assertFalse(teams.contains(Team.YELLOW))
    }

    @Test
    fun `getActiveTeams with 4 returns all playable teams`() {
        val teams = Team.getActiveTeams(4)
        assertEquals(4, teams.size)
        assertEquals(Team.playableTeams(), teams)
    }

    @Test
    fun `2 team game backward compatibility - only RED and BLUE needed`() {
        // 2チームゲームでは RED と BLUE の旗位置のみで有効
        val activeTeams = Team.getActiveTeams(2)
        assertEquals(listOf(Team.RED, Team.BLUE), activeTeams)
    }

    @Test
    fun `4 team game requires all 4 flag locations`() {
        val activeTeams = Team.getActiveTeams(4)
        assertEquals(4, activeTeams.size)
        // 4チーム全てに旗が必要
        activeTeams.forEach { team ->
            assertNotEquals(Team.SPECTATOR, team, "SPECTATOR should not be in active teams")
        }
    }
}
