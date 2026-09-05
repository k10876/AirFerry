package com.airferry.sender.encode

/**
 * Speed presets — mirrors `apps/sender/src/types.ts` `SPEED_PRESETS`.
 * Larger symbols pack more payload per QR but are denser / harder to scan.
 */
data class SpeedPreset(
    val id: String,
    val label: String,
    val symbolSize: Int,
    val fps: Int,
    val blurb: String
)

object SpeedPresets {
    val ALL: List<SpeedPreset> = listOf(
        SpeedPreset("stable", "稳定 512B", 512, 45, "V16，最易扫"),
        SpeedPreset("fast", "高速 896B", 896, 60, "V22"),
        SpeedPreset("extreme", "极限 1008B", 1008, 60, "V23"),
        SpeedPreset("aggressive", "激进 1400B", 1400, 60, "V27，默认"),
        SpeedPreset("turbo", "极速 1904B", 1904, 60, "V34"),
        SpeedPreset("max", "极限 2400B", 2400, 60, "V39")
    )

    val DEFAULT: SpeedPreset = ALL.first { it.id == "aggressive" }

    fun forSymbolSize(symbolSize: Int): SpeedPreset? = ALL.find { it.symbolSize == symbolSize }
}

data class TransferParams(
    val redundancyPct: Int = 5,
    val fps: Int = 60,
    val symbolSize: Int = 1400,
    val multiQr: Int = 4
)
