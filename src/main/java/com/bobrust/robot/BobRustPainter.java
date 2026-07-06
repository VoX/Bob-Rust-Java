package com.bobrust.robot;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

import com.bobrust.robot.error.PaintingInterrupted;
import com.bobrust.settings.Settings;
import com.bobrust.util.debug.DebugUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bobrust.generator.BorstUtils;
import com.bobrust.generator.sorter.Blob;
import com.bobrust.generator.sorter.BlobList;
import com.bobrust.util.Sign;

public class BobRustPainter {
	private static final Logger LOGGER = LogManager.getLogger(BobRustPainter.class);
	
	// The maximum distance the mouse can be from the correct position
	private static final double MAXIMUM_DISPLACEMENT = 10;
	private static final boolean ALLOW_PRESSES = true;
	// Radius of the pixel disc sampled around the color preview point when
	// verifying a color change. A single pixel is too fragile when the
	// configured point is a few pixels off the actual swatch.
	private static final int COLOR_PREVIEW_RADIUS = 5;

	private final BobRustPalette palette;
	private int displayX;
	private int displayY;
	private double widthDelta;
	private double heightDelta;
	private Rectangle screenBounds;
	
	// Exception
	private int drawnShapes;
	
	public BobRustPainter(BobRustPalette palette) {
		this.palette = palette;
	}
	
	public boolean startDrawing(GraphicsConfiguration monitor, Rectangle canvasArea, BlobList list, BiConsumer<Integer, Integer> renderCallback) throws PaintingInterrupted {
		// Reset values
		this.drawnShapes = 0;
		
		if (list.size() < 1) {
			return true;
		}
		
		Robot robot;
		try {
			robot = new Robot(monitor.getDevice());
		} catch (AWTException e) {
			return false;
		}
		
		{
			GraphicsDevice gd = monitor.getDevice();
			Rectangle bounds = monitor.getBounds();
			
			displayX = bounds.x;
			displayY = bounds.y;
			widthDelta = bounds.getWidth() / (double)gd.getDisplayMode().getWidth();
			heightDelta = bounds.getHeight() / (double)gd.getDisplayMode().getHeight();
			// The color preview is read in monitor local coordinates, the same
			// space the getPixelColor calls use. Captures are clamped to this
			screenBounds = new Rectangle(0, 0, bounds.width, bounds.height);
		}
		
		Sign signType = Settings.SettingsSign.get();
		
		int clickInterval = Settings.SettingsClickInterval.get();
		double autoDelay = 1000.0 / (clickInterval * 3.0);
		int autosaveInterval = Settings.SettingsAutosaveInterval.get();
		// S3 verification cadence: verify the painted pixel every Nth canvas
		// click (1 = every click, the classic behavior). Tool-change
		// verification below is never skipped — a missed color change would
		// corrupt every following blob.
		int verifyInterval = Math.max(1, Settings.SettingsClickVerifyInterval.get());
		
		// Configure the robot
		robot.setAutoDelay(0);
		List<Blob> blobList = list.getList();
		int count = blobList.size();
		int signWidth = signType.getWidth();
		int signHeight = signType.getHeight();
		
		// Last fields
		Point lastPoint = new Point(0, 0);
		int lastColor;
		int lastSize;
		int lastAlpha;
		int lastShape;
		
		{
			Blob startBlob = blobList.get(0);
			
			// Make sure that we have selected the game
			clickPoint(robot, palette.getFocusPoint(), 4, 50);
			
			// Select first color to prevent exception
			Point colorPoint = palette.getColorButton(BorstUtils.getClosestColor(startBlob.color));
			if (colorPoint != null) {
				clickColor(robot, colorPoint, 4, 50);
			} else {
				LOGGER.error("Could not draw color '" + startBlob.color + "' as it does not exist in the palette");
			}
			
			clickPoint(robot, palette.getSizeButton(startBlob.sizeIndex), 4, 50);
			clickPoint(robot, palette.getAlphaButton(startBlob.alphaIndex), 4, 50);
			clickPoint(robot, palette.getShapeButton(startBlob.shapeIndex), 4, 50);
			
			// Fill in last color information
			lastColor = startBlob.colorIndex;
			lastSize = startBlob.sizeIndex;
			lastAlpha = startBlob.alphaIndex;
			lastShape = startBlob.shapeIndex;
		}
		
		for (int i = 0, actions = 1; i < count; i++, actions++) {
			Blob blob = blobList.get(i);
			
			// Change the size
			if (lastSize != blob.sizeIndex) {
				clickSlider(robot, palette.getSizeButton(blob.sizeIndex), 20, autoDelay);
				lastSize = blob.sizeIndex;
				actions++;
			}
			
			// Change the color
			if (lastColor != blob.colorIndex) { // Without 20 here it will not work
				Point colorPoint = palette.getColorButton(BorstUtils.getClosestColor(blob.color));
				if (colorPoint != null) {
					clickColor(robot, colorPoint, 20, autoDelay);
					lastColor = blob.colorIndex;
					actions++;
				} else {
					LOGGER.error("Could not draw color '" + blob.color + "' as it does not exist in the palette");
				}
			}
			
			// Change the alpha
			if (lastAlpha != blob.alphaIndex) {
				clickSlider(robot, palette.getAlphaButton(blob.alphaIndex), 20, autoDelay);
				lastAlpha = blob.alphaIndex;
				actions++;
			}
			
			// Change the shape
			if (lastShape != blob.shapeIndex) {
				clickSlider(robot, palette.getShapeButton(blob.shapeIndex), 20, autoDelay);
				lastShape = blob.shapeIndex;
				actions++;
			}
			
			// Blob coordinates to sign coordinates
			double dx = blob.x / (double) signWidth;
			double dy = blob.y / (double) signHeight;
			
			// Sign coordinates to canvas coordinates
			double tx = dx * canvasArea.width + canvasArea.x;
			double ty = dy * canvasArea.height + canvasArea.y;
			
			// Canvas coordinates to screen coordinates
			int sx = (int) tx + displayX;
			int sy = (int) ty + displayY;
			
			lastPoint.setLocation(sx, sy);
			clickPointScaledDrawColor(robot, lastPoint, autoDelay, (i % verifyInterval) == 0);
			
			if (i > 0 && (i % autosaveInterval) == 0) {
				clickPoint(robot, palette.getSaveButton(), autoDelay);
				actions++;
			}
			
			drawnShapes += 1;
			renderCallback.accept(drawnShapes, count);
		}
		
		// Make sure that we save the painting
		clickPoint(robot, palette.getSaveButton(), 4, autoDelay);
		
		// Return the result
		throw new PaintingInterrupted(drawnShapes, PaintingInterrupted.InterruptType.PaintingFinished);
	}
	
	private Point transformPoint(Point point) {
		return new Point(
			displayX + point.x,
			displayY + point.y
		);
	}
	
	private void clickPoint(Robot robot, Point point, int times, double delay) throws PaintingInterrupted {
		for (int i = 0; i < times; i++) {
			clickPoint(robot, point, delay);
		}
	}
	
	/**
	 * Click a point on the screen with a scaled point. When {@code verify} is
	 * false (S3 sparse verification) the two {@code getPixelColor} screen
	 * captures and the retry loop are skipped — the click keeps the exact same
	 * three-delay cadence, it just drops the ~2 capture tax.
	 */
	private void clickPointScaledDrawColor(Robot robot, Point point, double delay, boolean verify) throws PaintingInterrupted {
		robot.mouseMove(point.x, point.y);
		addTimeDelay(System.nanoTime() / 1000000.0 + delay);

		if (!verify) {
			double time = System.nanoTime() / 1000000.0;

			if (ALLOW_PRESSES) {
				robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
			}
			addTimeDelay(time + delay);

			if (ALLOW_PRESSES) {
				robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
			}
			addTimeDelay(time + delay * 2.0);

			checkMouseDisplacement(point);
			return;
		}

		Color before = robot.getPixelColor(point.x, point.y);

		int maxAttempts = 3;
		do {
			double retryTime = System.nanoTime() / 1000000.0;

			if (ALLOW_PRESSES) {
				robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
			}
			addTimeDelay(retryTime + delay);

			if (ALLOW_PRESSES) {
				robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
			}
			addTimeDelay(retryTime + delay * 2.0);

			Color after = robot.getPixelColor(point.x, point.y);
			if (!before.equals(after)) {
				break;
			}

			addTimeDelay(retryTime + delay * 3.0);
		} while (maxAttempts-- > 0);

		if (maxAttempts < 0) {
			LOGGER.warn("Potentially failed to paint color! Will still keep trying to draw");
		}

		checkMouseDisplacement(point);
	}

	/** Interrupt painting if the user moved the mouse away from the target. */
	private void checkMouseDisplacement(Point point) throws PaintingInterrupted {
		var pointerInfo = MouseInfo.getPointerInfo();
		if (pointerInfo != null) {
			double distance = point.distance(pointerInfo.getLocation());
			if (distance > MAXIMUM_DISPLACEMENT) {
				throw new PaintingInterrupted(drawnShapes, PaintingInterrupted.InterruptType.MouseMoved);
			}
		}
	}
	
	private void clickPoint(Robot robot, Point point, double delay) throws PaintingInterrupted {
		point = transformPoint(point);

		double time = System.nanoTime() / 1000000.0;

		robot.mouseMove(point.x, point.y);
		addTimeDelay(time + delay);

		if (ALLOW_PRESSES) {
			robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
		}
		addTimeDelay(time + delay * 2.0);

		if (ALLOW_PRESSES) {
			robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
		}
		addTimeDelay(time + delay * 3.0);

		var pointerInfo = MouseInfo.getPointerInfo();
		if (pointerInfo != null) {
			double distance = point.distance(pointerInfo.getLocation());
			if (distance > MAXIMUM_DISPLACEMENT) {
				throw new PaintingInterrupted(drawnShapes, PaintingInterrupted.InterruptType.MouseMoved);
			}
		}
	}
	
	private void clickSlider(Robot robot, Point point, int maxAttempts, double delay) throws PaintingInterrupted {
		// Make sure that we press the size
		while (maxAttempts-- > 0) {
			clickPoint(robot, point, delay);
			
			/*
			DebugUtil.debugShowImage(
				robot.createScreenCapture(new Rectangle(
					point.x - 20,
					point.y - 20,
					40, 40
				)),
				1
			);
			*/
			
			// TODO: Potential bugs. Because rust uses those weird random patterns this might not work anymore :/
			Color after    = robot.getPixelColor(point.x - 1, point.y);
			Color afterOne = robot.getPixelColor(point.x + 1, point.y);
			if (after.getGreen() > 120 && afterOne.getGreen() < 120) {
				return;
			}
		}
	}
	
	/**
	 * Click a color swatch and verify that the change registered by watching
	 * the color preview. A small disc of pixels around the configured preview
	 * point is captured once before the clicks and compared after each click —
	 * if any sampled pixel changed the color change is treated as successful.
	 * Falls back to the old single pixel check if the region cannot be captured.
	 */
	private void clickColor(Robot robot, Point point, int maxAttempts, double delay) throws PaintingInterrupted {
		Point colorPreview = palette.getColorPreview();

		Rectangle region = getPreviewRegion(colorPreview);
		int[] before = (region != null) ? capturePreviewDisc(robot, region, colorPreview) : null;

		if (before == null || before.length == 0) {
			Color beforePixel = robot.getPixelColor(colorPreview.x, colorPreview.y);

			while (maxAttempts-- > 0) {
				clickPoint(robot, point, delay);

				Color after = robot.getPixelColor(colorPreview.x, colorPreview.y);
				if (!beforePixel.equals(after)) {
					return;
				}
			}
		} else {
			while (maxAttempts-- > 0) {
				clickPoint(robot, point, delay);

				int[] after = capturePreviewDisc(robot, region, colorPreview);
				if (after == null) {
					// The screen capture stopped working mid verify. Retrying
					// would burn the remaining attempts without verification
					LOGGER.warn("Could not capture the color preview region! Skipping color verification");
					return;
				}

				if (regionChanged(before, after)) {
					return;
				}
			}
		}

		LOGGER.warn("Potentially failed to select color! Will still keep trying to draw");
	}

	/**
	 * Compute the capture rectangle around the color preview point, clamped to
	 * the monitor bounds. Returns {@code null} if nothing of it is on screen.
	 */
	private Rectangle getPreviewRegion(Point center) {
		if (screenBounds == null) {
			return null;
		}

		Rectangle rect = new Rectangle(
			center.x - COLOR_PREVIEW_RADIUS,
			center.y - COLOR_PREVIEW_RADIUS,
			COLOR_PREVIEW_RADIUS * 2 + 1,
			COLOR_PREVIEW_RADIUS * 2 + 1
		);
		Rectangle clamped = rect.intersection(screenBounds);
		return (clamped.width > 0 && clamped.height > 0) ? clamped : null;
	}

	/**
	 * Capture the preview region with a single screen read and keep only the
	 * pixels within {@link #COLOR_PREVIEW_RADIUS} of the preview point.
	 * Returns {@code null} if the screen could not be captured.
	 */
	private int[] capturePreviewDisc(Robot robot, Rectangle region, Point center) {
		BufferedImage image;
		try {
			image = robot.createScreenCapture(region);
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to capture the color preview region: {}", e.toString());
			return null;
		}

		if (image == null) {
			return null;
		}

		return extractDisc(image, center.x - region.x, center.y - region.y, COLOR_PREVIEW_RADIUS);
	}

	/**
	 * Extract the pixels within {@code radius} (Euclidean) of the center point,
	 * in row major order. The center is in image coordinates and may lie
	 * outside the image when the capture was clamped to the screen edge.
	 */
	static int[] extractDisc(BufferedImage image, int centerX, int centerY, int radius) {
		int radiusSq = radius * radius;
		int[] pixels = new int[image.getWidth() * image.getHeight()];
		int count = 0;

		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int dx = x - centerX;
				int dy = y - centerY;
				if (dx * dx + dy * dy <= radiusSq) {
					pixels[count++] = image.getRGB(x, y);
				}
			}
		}

		return Arrays.copyOf(pixels, count);
	}

	/**
	 * Returns {@code true} if any sampled pixel differs between the two
	 * captures. Null, empty or mismatched captures give {@code false} —
	 * no evidence of a change.
	 */
	static boolean regionChanged(int[] before, int[] after) {
		if (before == null || after == null || before.length != after.length) {
			return false;
		}

		for (int i = 0; i < before.length; i++) {
			if (before[i] != after[i]) {
				return true;
			}
		}

		return false;
	}
	
	/**
	 * This method is used to provide a more accurate timing than {@code Robot.setAutoDelay}.
	 */
	private void addTimeDelay(double expected) throws PaintingInterrupted {
		double time = expected - (System.nanoTime() / 1000000.0);
		if (time < 0) return;
		
		try {
			Thread.sleep(Math.round(time));
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
			throw new PaintingInterrupted(drawnShapes, PaintingInterrupted.InterruptType.ThreadInterrupted);
		}
	}
}
