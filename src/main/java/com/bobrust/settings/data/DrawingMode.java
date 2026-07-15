package com.bobrust.settings.data;

/**
 * Which drawing pipeline the draw dialog runs (PLAN-PALETTIZED-MODE.md §7).
 */
public enum DrawingMode {
	/** The classic pipeline: simulated-annealing shapes over the 64-swatch palette. */
	Brush,

	/**
	 * The palettized pipeline: quantize to N exact colors, square-tile per
	 * color, enter colors through the HSV picker and paint fully opaque.
	 */
	Palettized
}
