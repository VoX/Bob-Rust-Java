package com.bobrust.generator;

import java.util.Random;

public class Circle {
	private final Worker worker;
	
	// Position
	public int x;
	public int y;
	
	// Radius
	public int r;
	
	public Circle(Worker worker) {
		this.worker = worker;
		this.randomize();
	}
	
	public Circle(Worker worker, int x, int y, int r) {
		this.worker = worker;
		this.x = x;
		this.y = y;
		this.r = r;
	}

	public void mutateShape() {
		int w = worker.w - 1;
		int h = worker.h - 1;
		Random rnd = worker.getRandom();

		if (rnd.nextInt(3) == 0) {
			int a = x + (int)(rnd.nextGaussian() * 16);
			int b = y + (int)(rnd.nextGaussian() * 16);
			x = BorstUtils.clampInt(a, 0, w);
			y = BorstUtils.clampInt(b, 0, h);
		} else {
			int c = BorstUtils.getClosestSize(r + (int)(rnd.nextGaussian() * 16));
			r = BorstUtils.clampInt(c, 1, w);
		}
	}

	public void randomize() {
		randomize(null);
	}

	/**
	 * Randomize circle position and size.
	 * When an {@link ErrorMap} is provided and error-guided placement is enabled,
	 * 80% of placements are biased toward high-error regions via importance
	 * sampling. The remaining 20% use uniform random placement for exploration.
	 */
	public void randomize(ErrorMap errorMap) {
		Random rnd = worker.getRandom();
		if (errorMap != null && rnd.nextFloat() < 0.8f) {
			int[] pos = errorMap.samplePosition(rnd);
			this.x = pos[0];
			this.y = pos[1];
		} else {
			this.x = rnd.nextInt(worker.w);
			this.y = rnd.nextInt(worker.h);
		}
		this.r = BorstUtils.SIZES[rnd.nextInt(BorstUtils.SIZES.length)];
	}
	
	public void fromValues(Circle shape) {
		this.r = shape.r;
		this.x = shape.x;
		this.y = shape.y;
	}
}