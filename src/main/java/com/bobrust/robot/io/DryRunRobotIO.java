package com.bobrust.robot.io;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Dry-run wrapper (the {@code ALLOW_PRESSES} pattern generalized,
 * PLAN-PALETTIZED-MODE.md §6): moves the mouse and keeps the exact pacing but
 * swallows every press, release and keystroke, logging them instead — safe
 * in-game rehearsal of a plan without painting a single stamp.
 */
public class DryRunRobotIO implements RobotIO {
	private static final Logger LOGGER = LogManager.getLogger(DryRunRobotIO.class);

	private final RobotIO delegate;
	private int suppressedActions;

	public DryRunRobotIO(RobotIO delegate) {
		this.delegate = delegate;
	}

	public int getSuppressedActions() {
		return suppressedActions;
	}

	@Override
	public void mouseMove(int x, int y) {
		delegate.mouseMove(x, y);
	}

	@Override
	public void mousePress() {
		suppressedActions++;
		LOGGER.debug("dry-run: mousePress suppressed");
	}

	@Override
	public void mouseRelease() {
		LOGGER.debug("dry-run: mouseRelease suppressed");
	}

	@Override
	public void keyPress(int keyCode) {
		suppressedActions++;
		LOGGER.debug("dry-run: keyPress {} suppressed", keyCode);
	}

	@Override
	public void keyRelease(int keyCode) {
		LOGGER.debug("dry-run: keyRelease {} suppressed", keyCode);
	}

	@Override
	public int getPixelRgb(int x, int y) {
		return delegate.getPixelRgb(x, y);
	}

	@Override
	public BufferedImage createScreenCapture(Rectangle region) {
		return delegate.createScreenCapture(region);
	}

	@Override
	public Point getPointerLocation() {
		return delegate.getPointerLocation();
	}

	@Override
	public double currentTimeMs() {
		return delegate.currentTimeMs();
	}

	@Override
	public void sleepMs(long millis) throws InterruptedException {
		delegate.sleepMs(millis);
	}

	@Override
	public void setClipboard(String text) {
		LOGGER.debug("dry-run: clipboard write suppressed");
	}
}
