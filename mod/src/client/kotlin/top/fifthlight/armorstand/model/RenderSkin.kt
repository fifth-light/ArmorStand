package top.fifthlight.armorstand.model

import org.joml.Matrix4f

class RenderSkin(
    val name: String?,
    val inverseBindMatrices: List<Matrix4f>?,
    val jointSize: Int,
)
