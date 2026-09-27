package com.hacklab.ctf.utils

import org.bukkit.util.Vector

/**
 * ノックバック雪玉の吹き飛ばしベクトル計算
 *
 * バニラの Knockback エンチャントは近接攻撃にしか乗らないため、
 * 投擲ヒット時に明示的に速度を与える。純粋関数としてテスト可能にしている。
 */
object SnowballKnockback {
    private const val EPSILON = 1.0e-6

    /**
     * @param projectileVelocity 雪玉のヒット時速度（進行方向）
     * @param shooterPos 投げた側の座標（進行方向が取れないときのフォールバック）
     * @param victimPos 当たった側の座標
     * @param horizontal 水平方向の強さ（ブロック/tick 相当）
     * @param vertical 垂直方向の強さ
     */
    fun compute(
        projectileVelocity: Vector,
        shooterPos: Vector,
        victimPos: Vector,
        horizontal: Double,
        vertical: Double
    ): Vector {
        var dir = Vector(projectileVelocity.x, 0.0, projectileVelocity.z)
        if (dir.lengthSquared() < EPSILON) {
            dir = Vector(victimPos.x - shooterPos.x, 0.0, victimPos.z - shooterPos.z)
        }
        if (dir.lengthSquared() < EPSILON) {
            return Vector(0.0, vertical, 0.0)
        }
        dir.normalize()
        return Vector(dir.x * horizontal, vertical, dir.z * horizontal)
    }
}
