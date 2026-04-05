package app.gamenative.ui.util

import app.gamenative.PrefManager
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.effects.ColorEffect
import com.winlator.renderer.effects.CRTEffect
import com.winlator.renderer.effects.Effect
import com.winlator.renderer.effects.FXAAEffect
import com.winlator.renderer.effects.NTSCCombinedEffect
import com.winlator.renderer.effects.ToonEffect
import kotlin.math.abs

data class ScreenEffectsConfig(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1.0f,
    val enableToon: Boolean = false,
    val enableFXAA: Boolean = false,
    val enableCRT: Boolean = false,
    val enableNTSC: Boolean = false,
)

fun loadScreenEffectsConfig(): ScreenEffectsConfig {
    return ScreenEffectsConfig(
        brightness = PrefManager.screenEffectsBrightness,
        contrast = PrefManager.screenEffectsContrast,
        gamma = PrefManager.screenEffectsGamma,
        enableToon = PrefManager.screenEffectsEnableToon,
        enableFXAA = PrefManager.screenEffectsEnableFXAA,
        enableCRT = PrefManager.screenEffectsEnableCRT,
        enableNTSC = PrefManager.screenEffectsEnableNTSC,
    )
}

fun persistScreenEffectsConfig(config: ScreenEffectsConfig) {
    PrefManager.screenEffectsBrightness = config.brightness
    PrefManager.screenEffectsContrast = config.contrast
    PrefManager.screenEffectsGamma = config.gamma
    PrefManager.screenEffectsEnableToon = config.enableToon
    PrefManager.screenEffectsEnableFXAA = config.enableFXAA
    PrefManager.screenEffectsEnableCRT = config.enableCRT
    PrefManager.screenEffectsEnableNTSC = config.enableNTSC
}

fun applyScreenEffectsConfig(renderer: GLRenderer, config: ScreenEffectsConfig) {
    val composer = renderer.effectComposer
    val effects = mutableListOf<Effect>()

    if (abs(config.brightness) > 0.001f || abs(config.contrast) > 0.001f || abs(config.gamma - 1.0f) > 0.001f) {
        val colorEffect = ColorEffect().apply {
            brightness = config.brightness / 100f
            contrast = config.contrast / 100f
            gamma = config.gamma
        }
        effects += colorEffect
    }

    if (config.enableToon) {
        effects += composer.getEffect(ToonEffect::class.java) ?: ToonEffect()
    }
    if (config.enableFXAA) {
        effects += composer.getEffect(FXAAEffect::class.java) ?: FXAAEffect()
    }
    if (config.enableCRT) {
        effects += composer.getEffect(CRTEffect::class.java) ?: CRTEffect()
    }
    if (config.enableNTSC) {
        effects += composer.getEffect(NTSCCombinedEffect::class.java) ?: NTSCCombinedEffect()
    }

    composer.setEffects(effects)
}
