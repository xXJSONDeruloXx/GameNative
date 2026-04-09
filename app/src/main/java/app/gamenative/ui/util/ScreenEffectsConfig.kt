package app.gamenative.ui.util

import app.gamenative.PrefManager
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.effects.ColorEffect
import com.winlator.renderer.effects.CRTEffect
import com.winlator.renderer.effects.Effect
import com.winlator.renderer.effects.FSR1EasuEffect
import com.winlator.renderer.effects.FSR1RcasEffect
import com.winlator.renderer.effects.FXAAEffect
import com.winlator.renderer.effects.NTSCCombinedEffect
import com.winlator.renderer.effects.ScalingModeEffect
import com.winlator.renderer.effects.ToonEffect
import com.winlator.renderer.effects.VividEffect
import kotlin.math.abs

private const val SCREEN_EFFECT_SCALE_MODE_NONE = 0
private const val SCREEN_EFFECT_SCALE_MODE_FSR = 5

data class ScreenEffectsConfig(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1.0f,
    val scalingMode: Int = SCREEN_EFFECT_SCALE_MODE_NONE,
    val fsrSharpnessLevel: Int = 3,
    val enableToon: Boolean = false,
    val enableFXAA: Boolean = false,
    val enableVivid: Boolean = false,
    val enableCRT: Boolean = false,
    val enableNTSC: Boolean = false,
)

fun loadScreenEffectsConfig(): ScreenEffectsConfig {
    return ScreenEffectsConfig(
        brightness = PrefManager.screenEffectsBrightness,
        contrast = PrefManager.screenEffectsContrast,
        gamma = PrefManager.screenEffectsGamma,
        scalingMode = PrefManager.screenEffectsScalingMode,
        fsrSharpnessLevel = PrefManager.screenEffectsFsrSharpnessLevel,
        enableToon = PrefManager.screenEffectsEnableToon,
        enableFXAA = PrefManager.screenEffectsEnableFXAA,
        enableVivid = PrefManager.screenEffectsEnableVivid,
        enableCRT = PrefManager.screenEffectsEnableCRT,
        enableNTSC = PrefManager.screenEffectsEnableNTSC,
    )
}

fun persistScreenEffectsConfig(config: ScreenEffectsConfig) {
    PrefManager.screenEffectsBrightness = config.brightness
    PrefManager.screenEffectsContrast = config.contrast
    PrefManager.screenEffectsGamma = config.gamma
    PrefManager.screenEffectsScalingMode = config.scalingMode
    PrefManager.screenEffectsFsrSharpnessLevel = config.fsrSharpnessLevel
    PrefManager.screenEffectsEnableToon = config.enableToon
    PrefManager.screenEffectsEnableFXAA = config.enableFXAA
    PrefManager.screenEffectsEnableVivid = config.enableVivid
    PrefManager.screenEffectsEnableCRT = config.enableCRT
    PrefManager.screenEffectsEnableNTSC = config.enableNTSC
}

fun applyScreenEffectsConfig(renderer: GLRenderer, config: ScreenEffectsConfig) {
    val composer = renderer.effectComposer
    val effects = mutableListOf<Effect>()

    when (config.scalingMode) {
        SCREEN_EFFECT_SCALE_MODE_FSR -> {
            effects += composer.getEffect(FSR1EasuEffect::class.java) ?: FSR1EasuEffect()
            val rcasEffect = composer.getEffect(FSR1RcasEffect::class.java) ?: FSR1RcasEffect()
            rcasEffect.sharpnessStops = fsrQuickMenuLevelToStops(config.fsrSharpnessLevel)
            effects += rcasEffect
        }
        SCREEN_EFFECT_SCALE_MODE_NONE -> Unit
        else -> {
            val scalingEffect = composer.getEffect(ScalingModeEffect::class.java) ?: ScalingModeEffect()
            scalingEffect.mode = quickMenuModeToScalingEffectMode(config.scalingMode)
            effects += scalingEffect
        }
    }

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
    if (config.enableVivid) {
        effects += composer.getEffect(VividEffect::class.java) ?: VividEffect()
    }
    if (config.enableCRT) {
        effects += composer.getEffect(CRTEffect::class.java) ?: CRTEffect()
    }
    if (config.enableNTSC) {
        effects += composer.getEffect(NTSCCombinedEffect::class.java) ?: NTSCCombinedEffect()
    }

    composer.setEffects(effects)
}

internal fun fsrQuickMenuLevelToStops(level: Int): Float {
    val clamped = level.coerceIn(1, 5)
    return when (clamped) {
        1 -> 2.0f
        2 -> 1.5f
        3 -> 1.0f
        4 -> 0.5f
        else -> 0.0f
    }
}

internal fun quickMenuModeToScalingEffectMode(mode: Int): ScalingModeEffect.Mode = when (mode) {
    1 -> ScalingModeEffect.Mode.NEAREST
    3 -> ScalingModeEffect.Mode.FILL
    4 -> ScalingModeEffect.Mode.STRETCH
    else -> ScalingModeEffect.Mode.LINEAR
}
