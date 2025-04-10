package top.fifthlight.armorstand

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.entity.EntityRenderDispatcher
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import top.fifthlight.armorstand.state.PlayerModelManager
import java.util.*

object PlayerRenderer {
    @JvmStatic
    fun render(
        uuid: UUID,
        vanillaState: PlayerEntityRenderState,
        matrixStack: MatrixStack,
        light: Int,
        vertexConsumerProvider: VertexConsumerProvider,
        textRenderer: TextRenderer,
        dispatcher: EntityRenderDispatcher,
    ): Boolean {
        val entry = PlayerModelManager[uuid]
        if (entry == null) {
            return false
        }

        val (instance, controller) = entry

        controller.update(vanillaState)
        controller.apply(instance)
        instance.update()

        val backupItem = matrixStack.peek().copy()
        matrixStack.pop()

        instance.render(matrixStack, light)
        if (ArmorStand.debug) {
            instance.renderDebug(matrixStack, vertexConsumerProvider, textRenderer, dispatcher, light)
        }

        matrixStack.push()
        matrixStack.peek().apply {
            positionMatrix.set(backupItem.positionMatrix)
            normalMatrix.set(backupItem.normalMatrix)
        }
        return true
    }
}