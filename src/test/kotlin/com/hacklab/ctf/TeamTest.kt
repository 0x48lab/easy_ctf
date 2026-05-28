package com.hacklab.ctf

import com.hacklab.ctf.utils.Team
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TeamTest {

    @Test
    fun `playableTeams excludes SPECTATOR`() {
        val playable = Team.playableTeams()
        assertFalse(playable.contains(Team.SPECTATOR))
        assertEquals(4, playable.size)
        assertTrue(playable.contains(Team.RED))
        assertTrue(playable.contains(Team.BLUE))
        assertTrue(playable.contains(Team.GREEN))
        assertTrue(playable.contains(Team.YELLOW))
    }

    @Test
    fun `getActiveTeams returns correct count for 2 teams`() {
        val teams = Team.getActiveTeams(2)
        assertEquals(2, teams.size)
        assertEquals(listOf(Team.RED, Team.BLUE), teams)
    }

    @Test
    fun `getActiveTeams returns correct count for 3 teams`() {
        val teams = Team.getActiveTeams(3)
        assertEquals(3, teams.size)
        assertEquals(listOf(Team.RED, Team.BLUE, Team.GREEN), teams)
    }

    @Test
    fun `getActiveTeams returns correct count for 4 teams`() {
        val teams = Team.getActiveTeams(4)
        assertEquals(4, teams.size)
        assertEquals(listOf(Team.RED, Team.BLUE, Team.GREEN, Team.YELLOW), teams)
    }

    @Test
    fun `getActiveTeams clamps minimum to 2`() {
        assertEquals(2, Team.getActiveTeams(1).size)
        assertEquals(2, Team.getActiveTeams(0).size)
        assertEquals(2, Team.getActiveTeams(-1).size)
    }

    @Test
    fun `getActiveTeams clamps maximum to 4`() {
        assertEquals(4, Team.getActiveTeams(5).size)
        assertEquals(4, Team.getActiveTeams(100).size)
    }

    @Test
    fun `each team has unique colorCode`() {
        val codes = Team.playableTeams().map { it.colorCode }
        assertEquals(codes.size, codes.toSet().size, "All teams should have unique color codes")
    }

    @Test
    fun `team display names are set`() {
        Team.entries.forEach { team ->
            assertTrue(team.displayName.isNotBlank(), "Team ${team.name} should have a display name")
        }
    }

    @Test
    fun `spectator has gray color`() {
        assertEquals("&7", Team.SPECTATOR.colorCode)
    }

    @Test
    fun `getActiveTeams order is always RED BLUE GREEN YELLOW`() {
        val teams4 = Team.getActiveTeams(4)
        assertEquals(Team.RED, teams4[0])
        assertEquals(Team.BLUE, teams4[1])
        assertEquals(Team.GREEN, teams4[2])
        assertEquals(Team.YELLOW, teams4[3])
    }

    @Test
    fun `getChatColor returns colorCode`() {
        Team.playableTeams().forEach { team ->
            assertEquals(team.colorCode, team.getChatColor())
        }
    }

    @Test
    fun `getColoredName includes colorCode and displayName`() {
        Team.playableTeams().forEach { team ->
            val colored = team.getColoredName()
            assertTrue(colored.startsWith(team.colorCode), "Colored name should start with color code")
            assertTrue(colored.contains(team.displayName), "Colored name should contain display name")
        }
    }
}
