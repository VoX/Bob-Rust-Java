package com.bobrust.robot;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.List;

import com.bobrust.robot.FieldInput.DecimalKey;
import com.bobrust.robot.FieldInput.SliderSpec;
import org.junit.jupiter.api.Test;

public class FieldInputTest {

	// ---------------------------------------------------- keystroke encoder

	@Test
	public void digitsEncodeAsNumpadKeys() {
		assertEquals(
			List.of(KeyEvent.VK_NUMPAD3, KeyEvent.VK_NUMPAD2),
			FieldInput.encodeKeystrokes("32", DecimalKey.PERIOD));
	}

	@Test
	public void decimalPointFollowsTheConfiguredKey() {
		assertEquals(
			List.of(KeyEvent.VK_NUMPAD1, KeyEvent.VK_PERIOD, KeyEvent.VK_NUMPAD0, KeyEvent.VK_NUMPAD2),
			FieldInput.encodeKeystrokes("1.02", DecimalKey.PERIOD));
		assertEquals(
			List.of(KeyEvent.VK_NUMPAD1, KeyEvent.VK_DECIMAL, KeyEvent.VK_NUMPAD0, KeyEvent.VK_NUMPAD2),
			FieldInput.encodeKeystrokes("1.02", DecimalKey.DECIMAL));
		assertEquals(
			List.of(KeyEvent.VK_NUMPAD1, KeyEvent.VK_COMMA, KeyEvent.VK_NUMPAD0, KeyEvent.VK_NUMPAD2),
			FieldInput.encodeKeystrokes("1.02", DecimalKey.COMMA));
	}

	@Test
	public void nonNumericCharactersAreRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> FieldInput.encodeKeystrokes("1a", DecimalKey.PERIOD));
		assertThrows(IllegalArgumentException.class,
			() -> FieldInput.encodeKeystrokes("-1", DecimalKey.PERIOD));
	}

	// ---------------------------------------------------- fill-edge parser

	/** A synthetic slider row: bright green fill up to fillX, dark track after. */
	private static BufferedImage sliderRow(int width, int fillPixels) {
		BufferedImage row = new BufferedImage(width, 3, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < width; x++) {
				row.setRGB(x, y, x < fillPixels ? 0xff58c832 : 0xff2a2a24);
			}
		}
		return row;
	}

	@Test
	public void fillFractionMatchesTheFilledPortion() {
		assertEquals(0.4, FieldInput.parseFillFraction(sliderRow(100, 40)), 1e-9);
		assertEquals(0.0, FieldInput.parseFillFraction(sliderRow(100, 0)), 1e-9);
		assertEquals(1.0, FieldInput.parseFillFraction(sliderRow(100, 100)), 1e-9);
	}

	@Test
	public void valueMappingSpansTheSliderRange() {
		SliderSpec size = new SliderSpec(new Point(0, 0), new Point(100, 0), 1, 32, 0.3);
		assertEquals(1.0, FieldInput.valueFromFraction(size, 0.0), 1e-9);
		assertEquals(32.0, FieldInput.valueFromFraction(size, 1.0), 1e-9);
		assertEquals(16.5, FieldInput.valueFromFraction(size, 0.5), 1e-9);

		SliderSpec opacity = new SliderSpec(new Point(0, 0), new Point(100, 0), 0, 1, 0.02);
		assertEquals(0.75, FieldInput.valueFromFraction(opacity, 0.75), 1e-9);
	}
}
