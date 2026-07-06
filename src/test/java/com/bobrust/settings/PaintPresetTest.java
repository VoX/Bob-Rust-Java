package com.bobrust.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.GeneratorConfig;
import com.bobrust.generator.sorter.BlobList;
import com.bobrust.generator.sorter.Blob;
import com.bobrust.generator.BorstUtils;
import com.bobrust.settings.data.PaintPreset;
import com.bobrust.util.PaintTimeEstimator;
import com.bobrust.util.data.AppConstants;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3: the preset→knob mapping is part of the measured contract — each preset
 * must resolve to exactly the parameter set justified by the F1/S1 benchmark
 * rows (see PaintPreset's javadoc for provenance), and applying a preset must
 * round-trip through the settings.
 */
class PaintPresetTest {

	@AfterEach
	void resetTouchedSettings() {
		Settings.SettingsGeneratorConfig.set(null);
		Settings.SettingsPaintPrune.set(null);
		Settings.SettingsClickInterval.set(30);
		Settings.SettingsClickVerifyInterval.set(1);
		Settings.SettingsPaintPreset.set(PaintPreset.BALANCED);
		Settings.SettingsCaptureMs.set(null);
	}

	@Test
	void balancedIsTheDefaultPresetAndAddsTheAutoAlphaFloor() {
		assertEquals(PaintPreset.BALANCED, Settings.SettingsPaintPreset.get());

		PaintPreset.Params params = PaintPreset.BALANCED.getParams();
		// S5: BALANCED is the S1 default plus the S2 content-adaptive alpha floor (auto), gated on ab-s5.csv
		// (pooled win, no per-image loss). Everything else stays the shipped default; click pacing unchanged.
		assertEquals(GeneratorConfig.DEFAULT.withMinAlphaIndex(GeneratorConfig.MIN_ALPHA_AUTO), params.generator());
		assertEquals(GeneratorConfig.MIN_ALPHA_AUTO, params.generator().minAlphaIndex());
		assertFalse(params.generator().useAdaptiveSize(), "S1: adaptive size stays off");
		assertFalse(params.generator().useSimulatedAnnealing(), "BALANCED is the classic hill climb");
		assertEquals(30, params.clicksPerSecond());
		assertEquals(1, params.verifyInterval());
		// Pruning only takes verified-FREE drops (maxLoss=0 accepts a drop only when the true re-rendered score
		// does not increase), so Balanced is ≥ un-pruned quality at ≤ its click count.
		assertEquals(new BlobPruner.Options(0, 0, 0.0), params.prune());
	}

	@Test
	void presetsResolveToTheirMeasuredParameterSets() {
		PaintPreset.Params blazing = PaintPreset.BLAZING.getParams();
		assertEquals(250, blazing.generator().maxRandomStates()); // G3 row
		assertEquals(50, blazing.generator().age());              // G3 age row
		assertFalse(blazing.generator().useSimulatedAnnealing()); // G1
		assertTrue(blazing.generator().useProxyRanking());        // G2
		assertEquals(new BlobPruner.Options(0, 70, 0.03), blazing.prune());
		assertEquals(50, blazing.clicksPerSecond());
		assertEquals(10, blazing.verifyInterval());

		PaintPreset.Params fast = PaintPreset.FAST.getParams();
		assertEquals(GeneratorConfig.DEFAULT.withMinAlphaIndex(GeneratorConfig.MIN_ALPHA_AUTO), fast.generator()); // S5 auto floor
		assertEquals(new BlobPruner.Options(0, 0, 0.01), fast.prune()); // S1 maxLoss=1% row
		assertEquals(40, fast.clicksPerSecond());
		assertEquals(5, fast.verifyInterval());

		PaintPreset.Params max = PaintPreset.MAX_QUALITY.getParams();
		assertEquals(1000, max.generator().maxRandomStates()); // G3 pre-tune row
		assertEquals(100, max.generator().age());
		assertTrue(max.generator().useSimulatedAnnealing()); // S5: sa adopted (ab-s5.csv: wins all 5 images)
		assertFalse(max.prune().enabled()); // never drops a blob
		assertEquals(25, max.clicksPerSecond());
		assertEquals(1, max.verifyInterval());

		assertNull(PaintPreset.CUSTOM.getParams());
	}

	@Test
	void applyWritesAllOwnedSettingsAndRoundTrips() {
		PaintPreset.BLAZING.apply();

		assertEquals(PaintPreset.BLAZING.getParams().generator(), Settings.getGeneratorConfig());
		assertEquals(PaintPreset.BLAZING.getParams().prune(), Settings.getPaintPruneOptions());
		assertEquals(50, Settings.SettingsClickInterval.get());
		assertEquals(10, Settings.SettingsClickVerifyInterval.get());
		assertEquals(PaintPreset.BLAZING, Settings.SettingsPaintPreset.get());
		assertTrue(PaintPreset.BLAZING.matchesCurrentSettings());
		assertFalse(PaintPreset.BALANCED.matchesCurrentSettings());
	}

	@Test
	void applyStoresSnapshotsOrUnsetPerKeyAndRoundTrips() {
		PaintPreset.BLAZING.apply();
		PaintPreset.BALANCED.apply();

		// S5 semantics change: BALANCED's generator now differs from DEFAULT (auto alpha floor), so apply() stores
		// a full serialized snapshot (not null) — and it must round-trip back to exactly BALANCED's config.
		assertNotNull(Settings.SettingsGeneratorConfig.get());
		assertEquals(PaintPreset.BALANCED.getParams().generator(), Settings.getGeneratorConfig());
		// The disabled-pruning path still stores unset: Max Quality never prunes.
		PaintPreset.MAX_QUALITY.apply();
		assertNull(Settings.SettingsPaintPrune.get());
		assertFalse(Settings.getPaintPruneOptions().enabled());
	}

	@Test
	void customApplyOnlyRecordsTheDirtyState() {
		PaintPreset.FAST.apply();
		PaintPreset.CUSTOM.apply();

		assertEquals(PaintPreset.CUSTOM, Settings.SettingsPaintPreset.get());
		// The knobs FAST wrote are untouched — CUSTOM owns no values.
		assertEquals(PaintPreset.FAST.getParams().prune(), Settings.getPaintPruneOptions());
		assertEquals(40, Settings.SettingsClickInterval.get());
		assertFalse(PaintPreset.CUSTOM.matchesCurrentSettings());
	}

	@Test
	void ladderIsMonotonicallySlowerPerBlobTowardQuality() {
		// Same plan, each preset's pacing knobs: the ladder must order
		// Blazing < Fast < Balanced < Max Quality in per-blob paint cost.
		BlobList plan = new BlobList();
		for (int i = 0; i < 100; i++) {
			plan.add(Blob.of(20, 20, BorstUtils.SIZES[2], BorstUtils.COLORS[0].rgb,
				BorstUtils.ALPHAS[2], AppConstants.CIRCLE_SHAPE));
		}

		long previous = -1;
		for (PaintPreset preset : new PaintPreset[] {
				PaintPreset.BLAZING, PaintPreset.FAST, PaintPreset.BALANCED, PaintPreset.MAX_QUALITY }) {
			PaintPreset.Params params = preset.getParams();
			long millis = PaintTimeEstimator.estimateMillis(plan,
				params.clicksPerSecond(), PaintTimeEstimator.DEFAULT_CAPTURE_MS,
				params.verifyInterval(), 1000);
			assertTrue(millis > previous, preset + " must paint slower than the previous rung");
			previous = millis;
		}
	}

	@Test
	void noPresetShipsAMeasuredDominatedAlphaConfig() {
		// Guard the proposals §7 negative results: no preset may ship opaque (minAlpha=5), the dominated floor 2,
		// or a single global alpha. Opaque stays an explicit per-use stencil toggle only; BLAZING keeps the plain
		// default floor (draft tier, minimal).
		for (PaintPreset preset : PaintPreset.values()) {
			if (preset == PaintPreset.CUSTOM) continue;
			GeneratorConfig g = preset.getParams().generator();
			assertNotEquals(5, g.minAlphaIndex(), preset + " must not ship opaque (stencil is a toggle)");
			assertNotEquals(2, g.minAlphaIndex(), preset + " must not ship the dominated floor 2");
			assertTrue(g.usePerShapeAlpha(), preset + " must keep per-shape alpha (single global alpha is dominated)");
			// The two dormant speed knobs stay off in every shipped preset.
			assertEquals(0.0, g.sizeClickBias(), preset + " must keep sizeClickBias dormant");
			assertEquals(0.0, g.qualityStop(), preset + " must keep qualityStop dormant");
		}
	}

	@Test
	void measuredCaptureMsPersistsAndBlends() {
		assertEquals(PaintTimeEstimator.DEFAULT_CAPTURE_MS, Settings.getCaptureMs());

		// First measurement is taken as-is; the second blends 50/50.
		Settings.recordMeasuredCaptureMs(20.0);
		assertEquals(20.0, Settings.getCaptureMs(), 1e-9);
		Settings.recordMeasuredCaptureMs(10.0);
		assertEquals(15.0, Settings.getCaptureMs(), 1e-9);
	}
}
