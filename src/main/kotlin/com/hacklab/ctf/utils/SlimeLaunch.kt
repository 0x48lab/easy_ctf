package com.hacklab.ctf.utils

import org.bukkit.util.Vector

/**
 * スライムブロック（ジャンプ台）の打ち上げベクトル計算
 *
 * 乗った瞬間に上向きへ打ち上げ、移動中ならその進行方向にも押し出す。
 * 純粋関数としてテスト可能にしている。
 */
object SlimeLaunch {
    private const val EPSILON = 1.0e-6

    /**
     * @param moveX 直前tickの水平移動量 X
     * @param moveZ 直前tickの水平移動量 Z
     * @param vertical 上向き速度（ブロック/tick）
     * @param horizontal 進行方向への押し出し速度（移動していないときは 0）
     */
    fun compute(moveX: Double, moveZ: Double, vertical: Double, horizontal: Double): Vector {
        val dir = Vector(moveX, 0.0, moveZ)
        if (dir.lengthSquared() < EPSILON || horizontal <= 0.0) {
            return Vector(0.0, vertical, 0.0)
        }
        dir.normalize()
        return Vector(dir.x * horizontal, vertical, dir.z * horizontal)
    }
}
