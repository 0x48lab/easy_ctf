package com.hacklab.ctf

import com.hacklab.ctf.utils.SnowballKnockback
import org.bukkit.util.Vector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SnowballKnockbackTest {

    @Test
    fun `uses projectile direction and ignores its vertical component`() {
        val kb = SnowballKnockback.compute(
            projectileVelocity = Vector(0.0, -0.5, 2.0),
            shooterPos = Vector(0.0, 64.0, 0.0),
            victimPos = Vector(10.0, 64.0, 0.0),
            horizontal = 1.2,
            vertical = 0.4
        )
        assertEquals(0.0, kb.x, 1e-9)
        assertEquals(0.4, kb.y, 1e-9)
        assertEquals(1.2, kb.z, 1e-9)
    }

    @Test
    fun `horizontal magnitude is normalized regardless of speed`() {
        val kb = SnowballKnockback.compute(
            projectileVelocity = Vector(3.0, 0.0, 4.0),
            shooterPos = Vector(0.0, 0.0, 0.0),
            victimPos = Vector(0.0, 0.0, 0.0),
            horizontal = 1.0,
            vertical = 0.0
        )
        assertEquals(1.0, Math.hypot(kb.x, kb.z), 1e-9)
        assertEquals(0.6, kb.x, 1e-9)
        assertEquals(0.8, kb.z, 1e-9)
    }

    @Test
    fun `falls back to shooter to victim direction when projectile drops straight down`() {
        val kb = SnowballKnockback.compute(
            projectileVelocity = Vector(0.0, -1.0, 0.0),
            shooterPos = Vector(0.0, 70.0, 0.0),
            victimPos = Vector(-5.0, 64.0, 0.0),
            horizontal = 2.0,
            vertical = 0.3
        )
        assertEquals(-2.0, kb.x, 1e-9)
        assertEquals(0.3, kb.y, 1e-9)
        assertEquals(0.0, kb.z, 1e-9)
    }

    @Test
    fun `only vertical when no direction can be determined`() {
        val kb = SnowballKnockback.compute(
            projectileVelocity = Vector(0.0, 0.0, 0.0),
            shooterPos = Vector(1.0, 64.0, 1.0),
            victimPos = Vector(1.0, 60.0, 1.0),
            horizontal = 1.5,
            vertical = 0.4
        )
        assertEquals(Vector(0.0, 0.4, 0.0), kb)
    }
}
