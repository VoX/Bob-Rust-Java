package com.bobrust.generator;

class State {
	private final Worker worker;
	/** Reused across evals so the exact path allocates nothing per call. */
	private final BorstColor[] colorOut = new BorstColor[1];

	public Circle shape;
	public float score;
	/**
	 * The exact optimal color derived by the last exact energy evaluation of
	 * {@link #shape}, or null. Assigned, copied and restored in exactly the
	 * same places as {@link #score}, so whenever a memoized score came from the
	 * exact kernel this color matches what BorstCore.computeColor would return
	 * for the shape on the current image — letting the commit path skip that
	 * re-run. Proxy (ranking-only) evaluations never set it.
	 */
	public BorstColor color;

	public State(Worker worker) {
		this.worker = worker;
		this.score = -1;
		this.shape = new Circle(worker);
	}

	public State(Worker worker, Circle sh, float score) {
		this.worker = worker;
		this.score = score;
		this.shape = sh;
	}

	Worker getWorker() {
		return worker;
	}

	public float getEnergy() {
		if (score < 0) {
			colorOut[0] = null;
			score = worker.getEnergy(shape, colorOut);
			color = colorOut[0];
		}

		return score;
	}

	/**
	 * Approximate energy used only to rank this candidate against the other
	 * random candidates of the same step (see Worker.getProxyEnergy). Stored in
	 * {@link #score} like the exact energy; the caller invalidates the winner's
	 * score afterwards so refinement and commit recompute exactly.
	 */
	public float getProxyEnergy() {
		if (score < 0) {
			score = worker.getProxyEnergy(shape);
			color = null;
		}

		return score;
	}

	public void doMove(State old) {
		old.fromValues(this);
		shape.mutateShape();
		score = -1;
		color = null;
	}

	public State getCopy() {
		// Copy EVERY searched field: x/y/r/alphaIndex plus the memoized
		// score/color pair. Dropping any of them silently corrupts the
		// hill-climb undo (the Q2 review flagged alphaIndex here explicitly).
		Circle shape_cope = new Circle(worker, shape.x, shape.y, shape.r, shape.alphaIndex);
		State copy = new State(worker, shape_cope, score);
		copy.color = color;
		return copy;
	}

	public void fromValues(State state) {
		shape.fromValues(state.shape);
		score = state.score;
		color = state.color;
	}
}
