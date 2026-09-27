package com.hacklab.ctf

import com.hacklab.ctf.utils.SlimeLaunch
import org.bukkit.util.Vector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SlimeLaunchTest {

    @Test
    fun `launches straight up when standing still`() {
        assertEquals(Vector(0.0, 1.4, 0.0), SlimeLaunch.compute(0.0, 0.0, 1.4, 0.6))
    }

    @Test
    fun `adds normalized horizontal push in movement direction`() {
        val v = SlimeLaunch.compute(0.03, 0.04, 1.4, 1.0)
        assertEquals(0.6, v.x, 1e-9)
        assertEquals(1.4, v.y, 1e-9)
        assertEquals(0.8, v.z, 1e-9)
    }

    @Test
    fun `horizontal push disabled with zero strength`() {
        assertEquals(Vector(0.0, 1.0, 0.0), SlimeLaunch.compute(1.0, 0.0, 1.0, 0.0))
    }
}
