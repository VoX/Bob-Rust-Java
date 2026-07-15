package com.bobrust.robot.io;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * The raw device seam of the painters (PLAN-PALETTIZED-MODE.md §6): every
 * mouse/keyboard/screen/clock primitive the paint executors touch, extracted
 * from {@link com.bobrust.robot.BobRustPainter} so the legacy path and the
 * palettized path share one implementation — and so a test fake can run an
 * entire paint end-to-end offline.
 */
public interface RobotIO {
	void mouseMove(int x, int y);

	void mousePress();

	void mouseRelease();

	void keyPress(int keyCode);

	void keyRelease(int keyCode);

	/** The screen pixel at (x, y) as an opaque sRGB int. */
	int getPixelRgb(int x, int y);

	/**
	 * Captures a screen region. May throw {@link RuntimeException} or return
	 * null when the screen cannot be captured (same contract as
	 * {@code Robot.createScreenCapture}).
	 */
	BufferedImage createScreenCapture(Rectangle region);

	/** The real pointer location, or null when unavailable. */
	Point getPointerLocation();

	/** Monotonic time in fractional milliseconds ({@code System.nanoTime()} based). */
	double currentTimeMs();

	void sleepMs(long millis) throws InterruptedException;

	/** Puts {@code text} on the clipboard (the paste-entry path). */
	void setClipboard(String text);
}
