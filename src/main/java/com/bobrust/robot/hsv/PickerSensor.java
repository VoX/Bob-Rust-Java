package com.bobrust.robot.hsv;

import com.bobrust.robot.error.PaintingInterrupted;

/**
 * The color-entry subsystem's view of the game: paced picker clicks + the
 * swatch read-back (PLAN-PALETTIZED-MODE.md §2.5). The production
 * implementation drives the Robot through the calibrated rects; tests plug
 * in a simulated picker. Keeping the controller behind this interface is
 * what makes the entire color-entry brain unit-testable without a Robot.
 */
public interface PickerSensor {
	/** Clicks the hue bar at {@code yPx} (the bar's center x is implied). */
	void clickHue(int yPx) throws PaintingInterrupted;

	/** Clicks the SV square at ({@code xPx}, {@code yPx}). */
	void clickSv(int xPx, int yPx) throws PaintingInterrupted;

	/**
	 * Reads the current swatch color: one capture of the calibrated swatch
	 * rect, per-channel median (the swatch is a flat fill, §1). Returns an
	 * opaque sRGB pixel.
	 */
	int readSwatch() throws PaintingInterrupted;
}
