package dev.saltartist.settings

enum class MotionResolution(val label: String, val width: Int, val height: Int) {
    FULL_HD("1080p", 1920, 1080), QHD("2K（1440p）", 2560, 1440), UHD("4K（2160p）", 3840, 2160);
}

internal fun motionResolution(value: Any?): MotionResolution = MotionResolution.entries.firstOrNull {
    it.name == value || it.height.toString() == value
} ?: MotionResolution.FULL_HD

internal fun motionFrameRate(value: Any?): Int = when (value?.toString()) {
    "24", "15" -> 24 // Migrate the retired low-frame-rate choice.
    else -> 30
}
