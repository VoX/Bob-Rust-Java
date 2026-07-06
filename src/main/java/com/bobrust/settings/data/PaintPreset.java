package com.bobrust.settings.data;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.GeneratorConfig;
import com.bobrust.settings.Settings;

/**
 * S3: the speed/quality preset ladder. Each preset is one point on the
 * measured Pareto curve — a concrete set of values for every knob the ladder
 * owns: the generator configuration (Phase-G measured rows), the blob
 * budget/tolerance ({@code SettingsPaintPrune}, S1 measured curve), the click
 * rate ({@code SettingsClickInterval}) and the canvas-click verification
 * cadence ({@code SettingsClickVerifyInterval}). {@link #CUSTOM} is the dirty
 * state shown when the user hand-tweaks a control after selecting a preset.
 *
 * <p>Provenance of the numbers (don't retune without a matching sweep run —
 * see the S1/S2/S5 A/B rows in {@code docs/ab-s5.csv} + the harness
 * {@code ClickBudgetSweepTest}, and {@code PruneBenchmarkTest.presetLadder}):
 * <ul>
 * <li><b>BALANCED</b> (default) — the S1 default generation config (adaptive
 *     size off) plus the S2 content-adaptive alpha floor
 *     ({@link GeneratorConfig#MIN_ALPHA_AUTO}: floor 0/glazing on photographic
 *     content, floor 1 on hard-edge/text) and {@code maxLoss=0} pruning. The
 *     auto floor is gated on {@code docs/ab-s5.csv}: pooled +0.005 SSIM /
 *     −0.31 ΔE00 over a fixed floor 1, no per-image regression (portrait
 *     ΔE00 −31%, texture −21%; hard-edge images tie because the classifier
 *     picks the same floor). Pruning drops only blobs whose re-rendered true
 *     score does not increase, so quality is ≥ un-pruned at fewer clicks.</li>
 * <li><b>FAST</b> — same S2 auto alpha floor as BALANCED; S1's measured
 *     {@code maxLoss=1%} pruning row (−17% blobs corpus at 800 shapes); cps 40
 *     + verify every 5th click (PROPOSALS-SPEED §A4; in-game reliability of
 *     cps>30 is unvalidated — the verifier still catches a systematic failure
 *     within 5 blobs).</li>
 * <li><b>BLAZING</b> — hard 70% blob budget on top of a 3% tolerance
 *     (accepts visible loss on hard images — draft tier); generation from the
 *     measured G-phase speed rows: states=250 (G3: −2.3% metrics), age=50
 *     (G3: 1.78× faster refine, −1.9% SSIM — explicitly earmarked for this
 *     preset); cps 50, verify every 10th.</li>
 * <li><b>MAX_QUALITY</b> — states=1000 plus simulated annealing, adopted per
 *     {@code docs/ab-s5.csv}: sa wins both pooled SSIM (+0.009) and ΔE00
 *     (−0.21) and every one of the five images, at ~1.7× generation time —
 *     justified only here, where paint time dominates and there is no pruning.
 *     Conservative cps 25, verify every click.</li>
 * </ul>
 *
 * <p>All presets keep the full size vocabulary and palette: restricting them
 * (PROPOSALS-SPEED §A3) has no measured curve yet and no generator plumbing —
 * deferred, see the plan.
 */
public enum PaintPreset {
	BLAZING("Blazing", new Params(
		GeneratorConfig.DEFAULT.withMaxRandomStates(250).withAge(50),
		new BlobPruner.Options(0, 70, 0.03),
		50, 10)),
	FAST("Fast", new Params(
		GeneratorConfig.DEFAULT.withMinAlphaIndex(GeneratorConfig.MIN_ALPHA_AUTO),
		new BlobPruner.Options(0, 0, 0.01),
		40, 5)),
	BALANCED("Balanced", new Params(
		GeneratorConfig.DEFAULT.withMinAlphaIndex(GeneratorConfig.MIN_ALPHA_AUTO),
		new BlobPruner.Options(0, 0, 0.0),
		30, 1)),
	MAX_QUALITY("Max Quality", new Params(
		GeneratorConfig.DEFAULT.withMaxRandomStates(1000).withUseSimulatedAnnealing(true),
		BlobPruner.Options.NONE,
		25, 1)),
	/** Dirty state: the user hand-tweaked a preset-owned knob. No params. */
	CUSTOM("Custom", null);

	/**
	 * The full parameter set a preset resolves to.
	 *
	 * @param generator       generation config (states/age/sa/proxy/…)
	 * @param prune           blob budget + tolerance for the paint plan
	 * @param clicksPerSecond painter click rate ({@code SettingsClickInterval})
	 * @param verifyInterval  verify the painted pixel every Nth canvas click
	 */
	public record Params(GeneratorConfig generator, BlobPruner.Options prune, int clicksPerSecond, int verifyInterval) {
	}

	private final String displayName;
	private final Params params;

	PaintPreset(String displayName, Params params) {
		this.displayName = displayName;
		this.params = params;
	}

	public String getDisplayName() {
		return displayName;
	}

	/** The preset's parameter set, or {@code null} for {@link #CUSTOM}. */
	public Params getParams() {
		return params;
	}

	/**
	 * Write this preset's parameters into the settings (and record the preset
	 * itself). {@link #CUSTOM} only records the state — it owns no values.
	 * Defaults are stored as unset so they keep tracking future default
	 * changes.
	 */
	public void apply() {
		if (params != null) {
			GeneratorConfig generator = params.generator();
			Settings.SettingsGeneratorConfig.set(
				generator.equals(GeneratorConfig.DEFAULT) ? null : generator.serialize());
			Settings.SettingsPaintPrune.set(
				params.prune().enabled() ? params.prune().serialize() : null);
			Settings.SettingsClickInterval.set(params.clicksPerSecond());
			Settings.SettingsClickVerifyInterval.set(params.verifyInterval());
		}
		Settings.SettingsPaintPreset.set(this);
	}

	/**
	 * Whether the current settings exactly match this preset's parameters
	 * (seed excluded — it is not a preset knob).
	 */
	public boolean matchesCurrentSettings() {
		if (params == null) {
			return false;
		}
		return Settings.getGeneratorConfig().withSeed(0).equals(params.generator().withSeed(0))
			&& Settings.getPaintPruneOptions().equals(params.prune())
			&& Settings.SettingsClickInterval.get() == params.clicksPerSecond()
			&& Settings.SettingsClickVerifyInterval.get() == params.verifyInterval();
	}
}
