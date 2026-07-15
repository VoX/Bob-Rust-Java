package com.bobrust.robot.error;

import java.util.Objects;

public class PaintingInterrupted extends Exception {
	/**
	 * How many shapes had been drawn
	 */
	private final int drawnShapes;
	private final InterruptType interruptType;
	
	public PaintingInterrupted(int drawnShapes, InterruptType interruptType) {
		this.drawnShapes = drawnShapes;
		this.interruptType = Objects.requireNonNull(interruptType);
	}
	
	public int getDrawnShapes() {
		return drawnShapes;
	}
	
	public InterruptType getInterruptType() {
		return interruptType;
	}
	
	public enum InterruptType {
		/**
		 * This is used when the painting was interrupted because the mouse moved
		 */
		MouseMoved,
		
		/**
		 * This is used when the painting was interrupted because of Thread.interrupt()
		 */
		ThreadInterrupted,
		
		/**
		 * This is used when the painting has been finished
		 */
		PaintingFinished,

		/**
		 * Palettized mode: a color entry through the HSV picker could not be
		 * verified against the swatch (or the pre-paint probe gates failed).
		 * A wrong color would corrupt an entire color pass — fail fast,
		 * resume later.
		 */
		ColorEntryFailed,

		/**
		 * Palettized mode: a SIZE/OPACITY field entry could not be verified
		 * against the slider fill. An unfocused field leaks keystrokes into
		 * the game (chat, binds) — fail fast, never soldier on.
		 */
		FieldEntryFailed
	}
}
