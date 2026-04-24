package app.gamenative.omfg

/**
 * Full configuration state for the OMFG (Open Multi Frame Generation) Vulkan layer.
 * Mirrors the omfg-deck plugin schema: https://github.com/xXJSONDeruloXx/omfg-deck
 *
 * Fields are named to match the TOML env-var keys exactly so we can write them directly.
 */
data class OmfgConfig(
    // ── Mode ──────────────────────────────────────
    val OMFG_LAYER_MODE: String = "reproject-blend",
    val OMFG_DEBUG_VIEW: String = "off",

    // ── Reprojection ──────────────────────────────
    val OMFG_REPROJECT_SEARCH_RADIUS: Int = 2,
    val OMFG_REPROJECT_PATCH_RADIUS: Int = 1,
    val OMFG_REPROJECT_CONFIDENCE_SCALE: Float = 4.0f,
    val OMFG_REPROJECT_DISOCCLUSION_CURRENT_BIAS: Float = 0.75f,
    val OMFG_REPROJECT_DISOCCLUSION_SCALE: Float = 2.0f,
    val OMFG_REPROJECT_HOLE_FILL_STRENGTH: Float = 0.85f,
    val OMFG_REPROJECT_HOLE_FILL_RADIUS: Int = 2,
    val OMFG_REPROJECT_GRADIENT_CONFIDENCE_WEIGHT: Float = 8.0f,
    val OMFG_REPROJECT_CHROMA_WEIGHT: Float = 0.3f,
    val OMFG_REPROJECT_AMBIGUITY_SCALE: Float = 6.0f,

    // ── Optical Flow ──────────────────────────────
    val OMFG_OPTICAL_FLOW_SEARCH_RADIUS: Int = 2,
    val OMFG_OPTICAL_FLOW_PATCH_RADIUS: Int = 1,
    val OMFG_OPTICAL_FLOW_LEVELS: Int = 3,
    val OMFG_OPTICAL_FLOW_CONFIDENCE_SCALE: Float = 4.0f,
    val OMFG_OPTICAL_FLOW_MOTION_PENALTY: Float = 0.01f,

    // ── Multi / Adaptive ──────────────────────────
    val OMFG_MULTI_BLEND_COUNT: Int = 2,
    val OMFG_ADAPTIVE_MULTI_MIN_GENERATED_FRAMES: Int = 0,
    val OMFG_ADAPTIVE_MULTI_MAX_GENERATED_FRAMES: Int = 2,
    val OMFG_ADAPTIVE_MULTI_TARGET_FPS: Int = 120,
    val OMFG_ADAPTIVE_MULTI_INTERVAL_THRESHOLD_MS: Float = 1.0f,

    // ── BFI / Visual timing ───────────────────────
    val OMFG_BFI_PERIOD: Int = 1,
    val OMFG_BFI_HOLD_MS: Int = 8,
    val OMFG_VISUAL_HOLD_MS: Int = 8,

    // ── Mode-specific flags ───────────────────────
    val OMFG_BLEND_ORIGINAL_PRESENT_FIRST: Boolean = false,
    val OMFG_COPY_ORIGINAL_PRESENT_FIRST: Boolean = false,
    val OMFG_HISTORY_COPY_FREEZE_HISTORY: Boolean = false,

    // ── Diagnostics ───────────────────────────────
    val OMFG_PRESENT_TIMING: Boolean = false,
    val OMFG_PRESENT_WAIT: Boolean = false,
    val OMFG_PRESENT_WAIT_TIMEOUT_S: Int = 5,
    val OMFG_BENCHMARK: Boolean = false,
    val OMFG_BENCHMARK_LABEL: String = "live",

    // ── Startup-scoped (requires game restart) ────
    val OMFG_SWAPCHAIN_IMAGE_BUMP_OVERRIDE: Int = 0,
    val OMFG_CREATE_DEVICE_DEBUG: Boolean = false,
    val OMFG_CREATE_DEVICE_APPEND_TIMING_EXTENSIONS: Boolean = false,
    val OMFG_CREATE_DEVICE_APPEND_TIMING_FEATURES: Boolean = false,
) {
    companion object {
        val DEFAULTS = OmfgConfig()

        // ── Layer mode lists ──────────────────────
        val LAYER_MODES_UTILITY = listOf(
            "passthrough", "clear", "bfi", "copy", "history-copy",
        )
        val LAYER_MODES_SINGLE = listOf(
            "blend", "adaptive-blend", "search-blend", "search-adaptive-blend",
            "reproject-blend", "reproject-adaptive-blend", "optflow-blend",
        )
        val LAYER_MODES_MULTI = listOf(
            "multi-blend", "adaptive-multi-blend", "reproject-multi-blend",
            "reproject-adaptive-multi-blend", "optflow-multi-blend", "optflow-adaptive-multi-blend",
        )
        val ALL_LAYER_MODES: List<String> = LAYER_MODES_UTILITY + LAYER_MODES_SINGLE + LAYER_MODES_MULTI

        val DEBUG_VIEWS = listOf(
            "off", "motion", "confidence", "ambiguity", "disocclusion", "hole-fill", "fallback",
        )

        // Quick menu cycle subset (used in the compact HUD overlay)
        val QUICK_MENU_MODES = listOf(
            "passthrough",
            "blend",
            "adaptive-blend",
            "reproject-blend",
        )
    }

    // ── Mode classification helpers ──────────────

    fun isMultiMode(): Boolean = mode in LAYER_MODES_MULTI
    fun isAdaptiveMode(): Boolean = mode.contains("adaptive")
    fun isReprojectMode(): Boolean = mode.startsWith("reproject") || mode.startsWith("search")
    fun isOptflowMode(): Boolean = mode.startsWith("optflow")
    fun isBfiMode(): Boolean = mode == "bfi"
    fun isBlendMode(): Boolean = mode.contains("blend")
    fun isCopyMode(): Boolean = mode == "copy"
    fun isHistoryCopyMode(): Boolean = mode == "history-copy"
    fun isVisualMode(): Boolean = mode in listOf("bfi", "copy", "history-copy")

    private val mode: String get() = OMFG_LAYER_MODE

    /**
     * Render this config as a TOML file the OMFG layer can hot-reload.
     */
    fun toToml(): String = buildString {
        appendLine("[omfg]")
        appendLine("OMFG_LAYER_MODE=$OMFG_LAYER_MODE")
        appendLine("OMFG_DEBUG_VIEW=$OMFG_DEBUG_VIEW")
        // Reproject
        appendLine("OMFG_REPROJECT_SEARCH_RADIUS=$OMFG_REPROJECT_SEARCH_RADIUS")
        appendLine("OMFG_REPROJECT_PATCH_RADIUS=$OMFG_REPROJECT_PATCH_RADIUS")
        appendLine("OMFG_REPROJECT_CONFIDENCE_SCALE=$OMFG_REPROJECT_CONFIDENCE_SCALE")
        appendLine("OMFG_REPROJECT_DISOCCLUSION_CURRENT_BIAS=$OMFG_REPROJECT_DISOCCLUSION_CURRENT_BIAS")
        appendLine("OMFG_REPROJECT_DISOCCLUSION_SCALE=$OMFG_REPROJECT_DISOCCLUSION_SCALE")
        appendLine("OMFG_REPROJECT_HOLE_FILL_STRENGTH=$OMFG_REPROJECT_HOLE_FILL_STRENGTH")
        appendLine("OMFG_REPROJECT_HOLE_FILL_RADIUS=$OMFG_REPROJECT_HOLE_FILL_RADIUS")
        appendLine("OMFG_REPROJECT_GRADIENT_CONFIDENCE_WEIGHT=$OMFG_REPROJECT_GRADIENT_CONFIDENCE_WEIGHT")
        appendLine("OMFG_REPROJECT_CHROMA_WEIGHT=$OMFG_REPROJECT_CHROMA_WEIGHT")
        appendLine("OMFG_REPROJECT_AMBIGUITY_SCALE=$OMFG_REPROJECT_AMBIGUITY_SCALE")
        // Optical flow
        appendLine("OMFG_OPTICAL_FLOW_SEARCH_RADIUS=$OMFG_OPTICAL_FLOW_SEARCH_RADIUS")
        appendLine("OMFG_OPTICAL_FLOW_PATCH_RADIUS=$OMFG_OPTICAL_FLOW_PATCH_RADIUS")
        appendLine("OMFG_OPTICAL_FLOW_LEVELS=$OMFG_OPTICAL_FLOW_LEVELS")
        appendLine("OMFG_OPTICAL_FLOW_CONFIDENCE_SCALE=$OMFG_OPTICAL_FLOW_CONFIDENCE_SCALE")
        appendLine("OMFG_OPTICAL_FLOW_MOTION_PENALTY=$OMFG_OPTICAL_FLOW_MOTION_PENALTY")
        // Multi / Adaptive
        appendLine("OMFG_MULTI_BLEND_COUNT=$OMFG_MULTI_BLEND_COUNT")
        appendLine("OMFG_ADAPTIVE_MULTI_MIN_GENERATED_FRAMES=$OMFG_ADAPTIVE_MULTI_MIN_GENERATED_FRAMES")
        appendLine("OMFG_ADAPTIVE_MULTI_MAX_GENERATED_FRAMES=$OMFG_ADAPTIVE_MULTI_MAX_GENERATED_FRAMES")
        appendLine("OMFG_ADAPTIVE_MULTI_TARGET_FPS=$OMFG_ADAPTIVE_MULTI_TARGET_FPS")
        appendLine("OMFG_ADAPTIVE_MULTI_INTERVAL_THRESHOLD_MS=$OMFG_ADAPTIVE_MULTI_INTERVAL_THRESHOLD_MS")
        // BFI / Visual
        appendLine("OMFG_BFI_PERIOD=$OMFG_BFI_PERIOD")
        appendLine("OMFG_BFI_HOLD_MS=$OMFG_BFI_HOLD_MS")
        appendLine("OMFG_VISUAL_HOLD_MS=$OMFG_VISUAL_HOLD_MS")
        // Mode-specific flags
        appendLine("OMFG_BLEND_ORIGINAL_PRESENT_FIRST=${if (OMFG_BLEND_ORIGINAL_PRESENT_FIRST) 1 else 0}")
        appendLine("OMFG_COPY_ORIGINAL_PRESENT_FIRST=${if (OMFG_COPY_ORIGINAL_PRESENT_FIRST) 1 else 0}")
        appendLine("OMFG_HISTORY_COPY_FREEZE_HISTORY=${if (OMFG_HISTORY_COPY_FREEZE_HISTORY) 1 else 0}")
        // Diagnostics
        appendLine("OMFG_PRESENT_TIMING=${if (OMFG_PRESENT_TIMING) 1 else 0}")
        appendLine("OMFG_PRESENT_WAIT=${if (OMFG_PRESENT_WAIT) 1 else 0}")
        appendLine("OMFG_PRESENT_WAIT_TIMEOUT_NS=${OMFG_PRESENT_WAIT_TIMEOUT_S * 1_000_000_000L}")
        appendLine("OMFG_BENCHMARK=${if (OMFG_BENCHMARK) 1 else 0}")
        appendLine("OMFG_BENCHMARK_LABEL=$OMFG_BENCHMARK_LABEL")
        // Startup-scoped
        appendLine("OMFG_SWAPCHAIN_IMAGE_BUMP_OVERRIDE=$OMFG_SWAPCHAIN_IMAGE_BUMP_OVERRIDE")
        appendLine("OMFG_CREATE_DEVICE_DEBUG=${if (OMFG_CREATE_DEVICE_DEBUG) 1 else 0}")
        appendLine("OMFG_CREATE_DEVICE_APPEND_TIMING_EXTENSIONS=${if (OMFG_CREATE_DEVICE_APPEND_TIMING_EXTENSIONS) 1 else 0}")
        appendLine("OMFG_CREATE_DEVICE_APPEND_TIMING_FEATURES=${if (OMFG_CREATE_DEVICE_APPEND_TIMING_FEATURES) 1 else 0}")
    }
}
