package com.bobrust.settings.data;

/**
 * Palettized-mode detail level: the virtual-grid pitch in sign texels
 * (PLAN-PALETTIZED-MODE.md §3.2). Both pitches divide every built-in sign
 * dimension (multiples of 128) exactly, so stamps land on exact texel
 * boundaries.
 */
public enum PalettizedDetail {
	/** Pitch 1 texel — 512×512 cells on an XL frame: native resolution, the sharpest,
	 * but the most stamps (big/detailed images can be very long paints). */
	Max(1.0),

	/** Pitch 2 texels — 256×256 cells on XL: sharp, ~4× fewer stamps than Max. */
	Fine(2.0),

	/** Pitch 4 texels — 128×128 cells on XL: blockier, fastest (~16× fewer stamps than Max). */
	Fast(4.0);

	private final double pitch;

	PalettizedDetail(double pitch) {
		this.pitch = pitch;
	}

	public double getPitch() {
		return pitch;
	}
}
