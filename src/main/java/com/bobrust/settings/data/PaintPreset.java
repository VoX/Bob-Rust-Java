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
 * <p>Provenance of the numbers (don't retune without a matching F1 run —
 * see IMPROVEMENT-PLAN.md §3 and {@code PruneBenchmarkTest.presetLadder}):
 * <ul>
 * <li><b>BALANCED</b> (default) — today's generation config and click pacing
 *     exactly, plus {@code maxLoss=0} pruning: only drops whose re-rendered
 *     true score does NOT increase are accepted (fully occluded / off-canvas /
 *     net-harmful blobs), so output quality is ≥ today's at strictly fewer
 *     clicks. The only default-behavior delta, and it is verified-free.</li>
 * <li><b>FAST</b> — S1's measured {@code maxLoss=1%} row (−17% blobs corpus
 *     at 800 shapes, adaptive: −38% on solid, −4% on edges where every blob
 *     is load-bearing); cps 40 + verify every 5th click (PROPOSALS-SPEED §A4;
 *     in-game reliability of cps>30 is unvalidated — the verifier still
 *     catches a systematic failure within 5 blobs).</li>
 * <li><b>BLAZING</b> — hard 70% blob budget on top of a 3% tolerance
 *     (accepts visible loss on hard images — draft tier); generation from the
 *     measured G-phase speed rows: states=250 (G3: −2.3% metrics), age=50
 *     (G3: 1.78× faster refine, −1.9% SSIM — explicitly earmarked for this
 *     preset); cps 50, verify every 10th.</li>
 * <li><b>MAX_QUALITY</b> — states=1000 (G3's pre-tune row: sub-1% aggregate
 *     gain over 500, inside seed noise, at 1.13× generation time — paint time
 *     dominates so the margin is free here), no pruning, conservative cps 25,
 *     verify every click.</li>
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
		GeneratorConfig.DEFAULT,
		new BlobPruner.Options(0, 0, 0.01),
		40, 5)),
	BALANCED("Balanced", new Params(
		GeneratorConfig.DEFAULT,
		new BlobPruner.Options(0, 0, 0.0),
		30, 1)),
	MAX_QUALITY("Max Quality", new Params(
		GeneratorConfig.DEFAULT.withMaxRandomStates(1000),
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
