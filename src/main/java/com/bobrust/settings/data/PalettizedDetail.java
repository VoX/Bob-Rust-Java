package com.bobrust.settings.data;

/**
 * Palettized-mode detail level: the virtual-grid pitch in sign texels
 * (PLAN-PALETTIZED-MODE.md §3.2). Both pitches divide every built-in sign
 * dimension (multiples of 128) exactly, so stamps land on exact texel
 * boundaries.
 */
public enum PalettizedDetail {
	/** Pitch 3.2 texels — 160×160 cells on an XL frame. */
	Fine(3.2),

	/** Pitch 4.0 texels — 128×128 cells on XL; ~20–35% fewer actions, blockier. */
	Economy(4.0);

	private final double pitch;

	PalettizedDetail(double pitch) {
		this.pitch = pitch;
	}

	public double getPitch() {
		return pitch;
	}
}
