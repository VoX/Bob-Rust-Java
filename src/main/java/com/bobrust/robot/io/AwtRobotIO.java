package com.bobrust.robot.io;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;

/**
 * The production {@link RobotIO}: a thin adapter over {@link java.awt.Robot}
 * with the exact primitives {@link com.bobrust.robot.BobRustPainter} always
 * used (left button, {@code MouseInfo} pointer reads, AWT clipboard).
 */
public class AwtRobotIO implements RobotIO {
	private final Robot robot;

	public AwtRobotIO(Robot robot) {
		this.robot = robot;
	}

	@Override
	public void mouseMove(int x, int y) {
		robot.mouseMove(x, y);
	}

	@Override
	public void mousePress() {
		robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
	}

	@Override
	public void mouseRelease() {
		robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
	}

	@Override
	public void keyPress(int keyCode) {
		robot.keyPress(keyCode);
	}

	@Override
	public void keyRelease(int keyCode) {
		robot.keyRelease(keyCode);
	}

	@Override
	public int getPixelRgb(int x, int y) {
		return robot.getPixelColor(x, y).getRGB();
	}

	@Override
	public BufferedImage createScreenCapture(Rectangle region) {
		return robot.createScreenCapture(region);
	}

	@Override
	public Point getPointerLocation() {
		var pointerInfo = MouseInfo.getPointerInfo();
		return pointerInfo == null ? null : pointerInfo.getLocation();
	}

	@Override
	public double currentTimeMs() {
		return System.nanoTime() / 1000000.0;
	}

	@Override
	public void sleepMs(long millis) throws InterruptedException {
		Thread.sleep(millis);
	}

	@Override
	public void setClipboard(String text) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}
}
