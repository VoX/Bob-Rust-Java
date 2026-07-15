package com.bobrust.robot;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import com.bobrust.robot.error.PaintingInterrupted;
import com.bobrust.robot.error.PaintingInterrupted.InterruptType;
import com.bobrust.robot.io.PacedInput;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verified numeric-field entry (PLAN-PALETTIZED-MODE.md §3.1): click the
 * SIZE/OPACITY readout (focuses the field and selects the current number —
 * owner-confirmed), paste or type the value, press Enter, then read the
 * slider fill back between the calibrated endpoints and compare. One retry,
 * then a hard {@link InterruptType#FieldEntryFailed} abort — an unfocused
 * field means keystrokes leak into the game as chat/binds, so this never
 * soldiers on.
 *
 * <p>All points are screen coordinates (caller applies the monitor offset).
 */
public class FieldInput {
	private static final Logger LOGGER = LogManager.getLogger(FieldInput.class);
	private static final int MAX_ATTEMPTS = 2;
	/** Rows sampled around the slider midline for the fill read-back. */
	private static final int READBACK_ROWS = 3;

	/** Which decimal-separator key the typed fallback sends (edge case 21). */
	public enum DecimalKey {
		PERIOD(KeyEvent.VK_PERIOD),
		DECIMAL(KeyEvent.VK_DECIMAL),
		COMMA(KeyEvent.VK_COMMA);

		final int keyCode;

		DecimalKey(int keyCode) {
			this.keyCode = keyCode;
		}
	}

	/**
	 * The slider row a field entry is verified against: the two calibrated
	 * endpoint points (e.g. {@code size_1}/{@code size_32}), the value range
	 * they span, and the acceptance tolerance in value units.
	 */
	public record SliderSpec(Point start, Point end, double minValue, double maxValue, double tolerance) {
	}

	private final PacedInput paced;
	private final IntSupplier progress;
	private final boolean paste;
	private final DecimalKey decimalKey;

	public FieldInput(PacedInput paced, IntSupplier progress, boolean paste, DecimalKey decimalKey) {
		this.paced = paced;
		this.progress = progress;
		this.paste = paste;
		this.decimalKey = decimalKey;
	}

	/**
	 * Enters {@code value} into the field at {@code fieldPoint} and verifies
	 * it against {@code spec}. Throws {@link PaintingInterrupted} with
	 * {@link InterruptType#FieldEntryFailed} after the retry fails.
	 */
	public void enter(Point fieldPoint, String value, SliderSpec spec, double delay) throws PaintingInterrupted {
		double expected = Double.parseDouble(value);

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			// Focus click — selects the current number (owner, 2026-07-15)
			paced.clickPoint(fieldPoint, delay);

			if (paste) {
				paced.io().setClipboard(value);
				paced.tapChord(KeyEvent.VK_CONTROL, KeyEvent.VK_V, delay);
			} else {
				for (int keyCode : encodeKeystrokes(value, decimalKey)) {
					paced.tapKey(keyCode, delay);
				}
			}
			paced.tapKey(KeyEvent.VK_ENTER, delay);

			double got = readSliderValue(spec);
			if (got >= 0 && Math.abs(got - expected) <= spec.tolerance()) {
				return;
			}
			LOGGER.warn("Field entry '{}' read back {} (attempt {}/{})",
				value, got < 0 ? "unreadable" : "%.3f".formatted(got), attempt, MAX_ATTEMPTS);
		}

		throw new PaintingInterrupted(progress.getAsInt(), InterruptType.FieldEntryFailed);
	}

	/**
	 * Reads the slider's current value from its fill: one capture of the row
	 * between the calibrated endpoints, fill-edge scan, fraction → value.
	 * Returns -1 when the row cannot be read.
	 */
	public double readSliderValue(SliderSpec spec) {
		int x0 = Math.min(spec.start().x, spec.end().x);
		int x1 = Math.max(spec.start().x, spec.end().x);
		int midY = (spec.start().y + spec.end().y) / 2;
		if (x1 <= x0) {
			return -1;
		}

		Rectangle region = new Rectangle(x0, midY - READBACK_ROWS / 2, x1 - x0 + 1, READBACK_ROWS);
		BufferedImage row = paced.captureRegion(region);
		if (row == null) {
			return -1;
		}

		double fraction = parseFillFraction(row);
		if (fraction < 0) {
			return -1;
		}
		return valueFromFraction(spec, fraction);
	}

	/** Maps a fill fraction between the calibrated endpoints to a field value. */
	public static double valueFromFraction(SliderSpec spec, double fraction) {
		return spec.minValue() + fraction * (spec.maxValue() - spec.minValue());
	}

	/**
	 * Finds the fill edge in a captured slider row: the bright fill left of
	 * the thumb has green &gt; 120, the dark track right of it doesn't — the
	 * same discrimination the legacy slider-click verification keys on.
	 * Scans the middle row; returns the filled fraction in {@code [0, 1]},
	 * or -1 for a degenerate capture.
	 */
	public static double parseFillFraction(BufferedImage row) {
		int width = row.getWidth();
		if (width <= 0 || row.getHeight() <= 0) {
			return -1;
		}
		int y = row.getHeight() / 2;

		int lastFilled = -1;
		for (int x = 0; x < width; x++) {
			int green = (row.getRGB(x, y) >> 8) & 0xff;
			if (green > 120) {
				lastFilled = x;
			}
		}
		return (lastFilled + 1) / (double) width;
	}

	/**
	 * The typed-fallback key sequence for a numeric string: digits as numpad
	 * VK codes (layout-immune), the decimal point per the configured
	 * {@link DecimalKey}. Pure — unit-tested directly.
	 */
	public static List<Integer> encodeKeystrokes(String value, DecimalKey decimalKey) {
		List<Integer> keys = new ArrayList<>(value.length());
		for (char c : value.toCharArray()) {
			if (c >= '0' && c <= '9') {
				keys.add(KeyEvent.VK_NUMPAD0 + (c - '0'));
			} else if (c == '.' || c == ',') {
				keys.add(decimalKey.keyCode);
			} else {
				throw new IllegalArgumentException("Not a numeric field character: '" + c + "' in \"" + value + "\"");
			}
		}
		return keys;
	}
}
