package com.bobrust.unit.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bobrust.robot.ButtonConfiguration;
import com.bobrust.robot.ButtonConfiguration.Coordinate;
import org.junit.jupiter.api.Test;

public class ButtonConfigurationTest {

	@Test
	public void test() {
		ButtonConfiguration config = new ButtonConfiguration();
		System.out.println(config.serialize());
	}

	@Test
	public void serializationRoundTrip() {
		ButtonConfiguration config = new ButtonConfiguration();
		config.saveImage          = new Coordinate(10, 20);
		config.brush_square       = new Coordinate(30, 40);
		config.hsvSquare_topLeft  = new Coordinate(61, 762);
		config.hsvSquare_botRight = new Coordinate(372, 1073);
		config.hueBar_topLeft     = new Coordinate(377, 762);
		config.hueBar_botRight    = new Coordinate(426, 1073);
		config.swatch_topLeft     = new Coordinate(431, 762);
		config.swatch_botRight    = new Coordinate(520, 999);
		config.sizeField          = new Coordinate(500, 100);
		config.opacityField       = new Coordinate(500, 130);

		ButtonConfiguration copy = ButtonConfiguration.deserialize(config.serialize());

		assertEquals(config.saveImage, copy.saveImage);
		assertEquals(config.brush_square, copy.brush_square);
		assertEquals(config.hsvSquare_topLeft, copy.hsvSquare_topLeft);
		assertEquals(config.hsvSquare_botRight, copy.hsvSquare_botRight);
		assertEquals(config.hueBar_topLeft, copy.hueBar_topLeft);
		assertEquals(config.hueBar_botRight, copy.hueBar_botRight);
		assertEquals(config.swatch_topLeft, copy.swatch_topLeft);
		assertEquals(config.swatch_botRight, copy.swatch_botRight);
		assertEquals(config.sizeField, copy.sizeField);
		assertEquals(config.opacityField, copy.opacityField);
		assertTrue(copy.isPalettizedCalibrated());
	}

	/**
	 * A button_config.json written before the palettized fields existed
	 * deserializes with those fields null; update() must map them to the
	 * invalid DEFAULT so the UI knows the mode is uncalibrated.
	 */
	@Test
	public void missingFieldsBackCompat() {
		String legacyJson = """
			{
			  "saveImage": { "x": 251, "y": 66, "valid": true },
			  "focus": { "x": 1446, "y": 975, "valid": true }
			}""";

		ButtonConfiguration config = new ButtonConfiguration();
		config.update(ButtonConfiguration.deserialize(legacyJson));

		assertTrue(config.saveImage.valid());
		assertTrue(config.focus.valid());
		assertFalse(config.hsvSquare_topLeft.valid());
		assertFalse(config.hsvSquare_botRight.valid());
		assertFalse(config.hueBar_topLeft.valid());
		assertFalse(config.hueBar_botRight.valid());
		assertFalse(config.swatch_topLeft.valid());
		assertFalse(config.swatch_botRight.valid());
		assertFalse(config.sizeField.valid());
		assertFalse(config.opacityField.valid());
		assertFalse(config.isPalettizedCalibrated());
	}

	@Test
	public void palettizedCalibrationGateNeedsEveryCoordinate() {
		ButtonConfiguration config = new ButtonConfiguration();
		config.brush_square       = new Coordinate(1, 1);
		config.hsvSquare_topLeft  = new Coordinate(1, 1);
		config.hsvSquare_botRight = new Coordinate(2, 2);
		config.hueBar_topLeft     = new Coordinate(3, 1);
		config.hueBar_botRight    = new Coordinate(4, 2);
		config.swatch_topLeft     = new Coordinate(5, 1);
		config.swatch_botRight    = new Coordinate(6, 2);
		config.sizeField          = new Coordinate(7, 1);
		// opacityField left DEFAULT (invalid)
		assertFalse(config.isPalettizedCalibrated());

		config.opacityField = new Coordinate(7, 2);
		assertTrue(config.isPalettizedCalibrated());
	}
}
