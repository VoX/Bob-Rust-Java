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
	void balancedIsTheDefaultPresetAndMatchesTodaysBehavior() {
		assertEquals(PaintPreset.BALANCED, Settings.SettingsPaintPreset.get());

		PaintPreset.Params params = PaintPreset.BALANCED.getParams();
		// Generation and click pacing are exactly today's defaults…
		assertEquals(GeneratorConfig.DEFAULT, params.generator());
		assertEquals(30, params.clicksPerSecond());
		assertEquals(1, params.verifyInterval());
		// …and pruning only takes verified-FREE drops (maxLoss=0 accepts a
		// drop only when the true re-rendered score does not increase), so
		// Balanced is ≥ today's quality at ≤ today's click count.
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
		assertEquals(GeneratorConfig.DEFAULT, fast.generator());
		assertEquals(new BlobPruner.Options(0, 0, 0.01), fast.prune()); // S1 maxLoss=1% row
		assertEquals(40, fast.clicksPerSecond());
		assertEquals(5, fast.verifyInterval());

		PaintPreset.Params max = PaintPreset.MAX_QUALITY.getParams();
		assertEquals(1000, max.generator().maxRandomStates()); // G3 pre-tune row
		assertEquals(100, max.generator().age());
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
	void applyingDefaultsStoresUnsetSoDefaultsKeepTracking() {
		PaintPreset.BLAZING.apply();
		PaintPreset.BALANCED.apply();

		// Balanced's generator config IS the default — stored as unset, so a
		// future default change doesn't leave a stale pinned copy behind.
		assertNull(Settings.SettingsGeneratorConfig.get());
		assertEquals(GeneratorConfig.DEFAULT, Settings.getGeneratorConfig());
		// Max Quality disables pruning entirely — stored as unset.
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
	void measuredCaptureMsPersistsAndBlends() {
		assertEquals(PaintTimeEstimator.DEFAULT_CAPTURE_MS, Settings.getCaptureMs());

		// First measurement is taken as-is; the second blends 50/50.
		Settings.recordMeasuredCaptureMs(20.0);
		assertEquals(20.0, Settings.getCaptureMs(), 1e-9);
		Settings.recordMeasuredCaptureMs(10.0);
		assertEquals(15.0, Settings.getCaptureMs(), 1e-9);
	}
}
