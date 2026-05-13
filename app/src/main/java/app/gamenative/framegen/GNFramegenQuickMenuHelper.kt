package app.gamenative.framegen

import com.winlator.container.Container
import java.util.Locale

/** Mirrors LsfgQuickMenuHelper for GN Framegen in-game hot-reload. */
object GNFramegenQuickMenuHelper {
    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val model: Int,
    )

    fun isAvailable(container: Container): Boolean =
        GNFramegenManager.isEnabled(container)

    fun readSettings(container: Container): Settings = Settings(
        multiplier = GNFramegenManager.multiplier(container),
        flowScale  = GNFramegenManager.flowScale(container),
        model      = GNFramegenManager.model(container),
    )

    fun sanitizeMultiplier(mult: Int): Int = if (mult < 2) 0 else mult.coerceIn(2, 4)
    fun sanitizeFlowScale(scale: Float): Float = scale.coerceIn(0.2f, 1.0f)
    fun sanitizeModel(model: Int): Int = model.coerceIn(0, 1)

    fun applySettings(container: Container, settings: Settings) {
        val mult  = sanitizeMultiplier(settings.multiplier)
        val scale = sanitizeFlowScale(settings.flowScale)
        val model = sanitizeModel(settings.model)
        container.putExtra(GNFramegenManager.EXTRA_MULTIPLIER, mult.toString())
        container.putExtra(GNFramegenManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", scale))
        container.putExtra(GNFramegenManager.EXTRA_MODEL, model.toString())
        container.saveData()
        // Hot-reload the active context if one exists
        GNFramegenManager.updateConfig(container)
    }
}
