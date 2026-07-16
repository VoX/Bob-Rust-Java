package com.bobrust.robot.io;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.function.IntSupplier;

import com.bobrust.robot.error.PaintingInterrupted;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The shared click/keystroke pacing discipline, extracted verbatim from
 * {@link com.bobrust.robot.BobRustPainter}: three-phase paced clicks, the
 * high-precision {@code addTimeDelay} scheduler, the mouse-displacement
 * abort, and safe screen capture. Keystrokes go through the same scheduling
 * so the input cadence stays inside the user's clicks-per-second envelope
 * (PLAN-PALETTIZED-MODE.md §6).
 *
 * <p>All points are screen coordinates — the caller applies any monitor
 * offset before calling in.
 */
public class PacedInput {
	private static final Logger LOGGER = LogManager.getLogger(PacedInput.class);

	/** The maximum distance the mouse can be from the correct position. */
	public static final double MAXIMUM_DISPLACEMENT = 10;

	private final RobotIO io;
	private final IntSupplier progress;
	private final boolean allowPresses;

	public PacedInput(RobotIO io, IntSupplier progress) {
		this(io, progress, true);
	}

	public PacedInput(RobotIO io, IntSupplier progress, boolean allowPresses) {
		this.io = io;
		this.progress = progress;
		this.allowPresses = allowPresses;
	}

	public RobotIO io() {
		return io;
	}

	/**
	 * One three-phase paced click: move, press, release, each a {@code delay}
	 * apart, then the displacement check. Identical cadence to the legacy
	 * {@code BobRustPainter.clickPoint}.
	 */
	public void clickPoint(Point point, double delay) throws PaintingInterrupted {
		double time = io.currentTimeMs();

		io.mouseMove(point.x, point.y);
		addTimeDelay(time + delay);

		if (allowPresses) {
			io.mousePress();
		}
		addTimeDelay(time + delay * 2.0);

		if (allowPresses) {
			io.mouseRelease();
		}
		addTimeDelay(time + delay * 3.0);

		checkMouseDisplacement(point);
	}

	public void clickPoint(Point point, int times, double delay) throws PaintingInterrupted {
		for (int i = 0; i < times; i++) {
			clickPoint(point, delay);
		}
	}

	/**
	 * One paced key tap (press + release), scheduled exactly like a click
	 * phase so keystrokes never outpace the configured click interval.
	 */
	public void tapKey(int keyCode, double delay) throws PaintingInterrupted {
		double time = io.currentTimeMs();

		io.keyPress(keyCode);
		addTimeDelay(time + delay);

		io.keyRelease(keyCode);
		addTimeDelay(time + delay * 2.0);
	}

	/** A paced modifier chord (e.g. Ctrl+V). */
	public void tapChord(int modifierKeyCode, int keyCode, double delay) throws PaintingInterrupted {
		double time = io.currentTimeMs();

		io.keyPress(modifierKeyCode);
		addTimeDelay(time + delay);

		io.keyPress(keyCode);
		addTimeDelay(time + delay * 2.0);

		io.keyRelease(keyCode);
		addTimeDelay(time + delay * 3.0);

		io.keyRelease(modifierKeyCode);
		addTimeDelay(time + delay * 4.0);
	}

	/** Interrupt painting if the user moved the mouse away from the target. */
	public void checkMouseDisplacement(Point point) throws PaintingInterrupted {
		Point pointer = io.getPointerLocation();
		if (pointer != null && point.distance(pointer) > MAXIMUM_DISPLACEMENT) {
			throw new PaintingInterrupted(progress.getAsInt(), PaintingInterrupted.InterruptType.MouseMoved);
		}
	}

	/**
	 * High-precision pacing: sleep until {@code expected} (fractional ms on
	 * the {@link RobotIO#currentTimeMs()} clock). Thread interruption becomes
	 * a typed {@link PaintingInterrupted}.
	 */
	public void addTimeDelay(double expected) throws PaintingInterrupted {
		double time = expected - io.currentTimeMs();
		if (time < 0) {
			return;
		}

		try {
			io.sleepMs(Math.round(time));
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
			throw new PaintingInterrupted(progress.getAsInt(), PaintingInterrupted.InterruptType.ThreadInterrupted);
		}
	}

	/** Wait roughly {@code ms} from now — lets the game repaint before a read (interrupt-aware). */
	public void settle(double ms) throws PaintingInterrupted {
		addTimeDelay(io.currentTimeMs() + ms);
	}

	/**
	 * Captures a screen region, or null when the capture fails (the legacy
	 * warn-and-degrade contract).
	 */
	public BufferedImage captureRegion(Rectangle region) {
		try {
			return io.createScreenCapture(region);
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to capture screen region {}: {}", region, e.toString());
			return null;
		}
	}
}
