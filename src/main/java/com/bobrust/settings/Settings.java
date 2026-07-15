package com.bobrust.settings;

import java.awt.*;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.GeneratorConfig;
import com.bobrust.lang.RustUI;
import com.bobrust.settings.data.DrawingMode;
import com.bobrust.settings.data.PaintPreset;
import com.bobrust.settings.data.PalettizedDetail;
import com.bobrust.settings.data.ScalingType;
import com.bobrust.util.PaintTimeEstimator;
import com.bobrust.settings.type.*;
import com.bobrust.settings.type.parent.InternalSettings;
import com.bobrust.settings.type.parent.GuiElement;
import com.bobrust.util.data.RustSigns;

/**
 * Settings interface of the app.
 * 
 * All variables are static and can be accessed
 * 
 * By using the {@code @GuiElement} annotation you can easily add
 * new settings to the app gui. These will be added automatically
 * 
 * @author HardCoded
 */
public interface Settings {
	// Editor
	@GuiElement(tab = GuiElement.Tab.Editor,
		label = RustUI.Type.EDITOR_CALLBACKINTERVAL_LABEL,
		tooltip = RustUI.Type.EDITOR_CALLBACKINTERVAL_TOOLTIP)
	IntType EditorCallbackInterval = new IntType(100, 1, 99999);
	
	StringType EditorImageDirectory = new StringType(System.getProperty("user.home"));
	
	// Generator
	@GuiElement(tab = GuiElement.Tab.Generator, type = GuiElement.Type.Custom,
		label = RustUI.Type.SETTINGS_BACKGROUNDCOLOR_LABEL,
		tooltip = RustUI.Type.SETTINGS_BACKGROUNDCOLOR_TOOLTIP,
		button = RustUI.Type.SETTINGS_BACKGROUNDCOLOR_BUTTON)
	ColorType SettingsBackground = new ColorType(null);
	
	@GuiElement(tab = GuiElement.Tab.Generator, type = GuiElement.Type.Custom,
		label = RustUI.Type.SETTINGS_SIGNTYPE_LABEL,
		tooltip = RustUI.Type.SETTINGS_SIGNTYPE_TOOLTIP,
		button = RustUI.Type.SETTINGS_SIGNTYPE_BUTTON)
	SignType SettingsSign = new SignType(RustSigns.FIRST);
	
	@GuiElement(tab = GuiElement.Tab.Generator,
		label = RustUI.Type.SETTINGS_CUSTOMSIGNDIMENSION_LABEL,
		tooltip = RustUI.Type.SETTINGS_CUSTOMSIGNDIMENSION_TOOLTIP)
	SizeType SettingsSignDimension = new SizeType(
		new Dimension(256, 256),
		new Dimension(1, 1),
		new Dimension(9999, 9999));
	
	@GuiElement(tab = GuiElement.Tab.Generator, type = GuiElement.Type.Combo,
		label = RustUI.Type.SETTINGS_ALPHAINDEX_LABEL,
		tooltip = RustUI.Type.SETTINGS_ALPHAINDEX_TOOLTIP)
	IntType SettingsAlpha = new IntType(2, 0, 5);
	
	/**
	 * Returns:
	 * <pre>
	 * 0: Nearest neighbour
	 * 1: Bilinear
	 * 2: Bicubic
	 * </pre>
	 */
	@GuiElement(tab = GuiElement.Tab.Generator,
		label = RustUI.Type.SETTINGS_SCALINGTYPE_LABEL,
		tooltip = RustUI.Type.SETTINGS_SCALINGTYPE_TOOLTIP)
	EnumType<ScalingType> SettingsScaling = new EnumType<>(ScalingType.Nearest);
	
	@GuiElement(tab = GuiElement.Tab.Generator,
		label = RustUI.Type.SETTINGS_MAXSHAPES_LABEL,
		tooltip = RustUI.Type.SETTINGS_MAXSHAPES_TOOLTIP)
	IntType SettingsMaxShapes = new IntType(99999, 0, 200000);
	
	@GuiElement(tab = GuiElement.Tab.Generator,
		label = RustUI.Type.SETTINGS_CLICKINTERVAL_LABEL,
		tooltip = RustUI.Type.SETTINGS_CLICKINTERVAL_TOOLTIP)
	IntType SettingsClickInterval = new IntType(30, 1, 99999);
	
	@GuiElement(tab = GuiElement.Tab.Generator,
		label = RustUI.Type.SETTINGS_AUTOSAVEINTERVAL_LABEL,
		tooltip = RustUI.Type.SETTINGS_AUTOSAVEINTERVAL_TOOLTIP)
	IntType SettingsAutosaveInterval = new IntType(1000, 1, 99999);
	
	@GuiElement(tab = GuiElement.Tab.Generator,
		label = RustUI.Type.SETTINGS_USEICCCONVERSION_LABEL,
		tooltip = RustUI.Type.SETTINGS_USEICCCONVERSION_TOOLTIP)
	BoolType SettingsUseICCConversion = new BoolType(false);

	/**
	 * Serialized {@link GeneratorConfig} ({@code key=value;...}, see
	 * {@link GeneratorConfig#serialize()}). Not exposed in the GUI — edit the
	 * config file directly to override generator internals (seed, SA toggle,
	 * candidate count, ...). Unset means {@link GeneratorConfig#DEFAULT}.
	 */
	StringType SettingsGeneratorConfig = new StringType(null);

	/**
	 * Serialized {@link BlobPruner.Options} ({@code budget=1500;maxLoss=0.01}
	 * or the S3 percent form {@code budget=70%;maxLoss=0.03}, see
	 * {@link BlobPruner.Options#parse}). Unset/blank means no pruning (the
	 * pre-S1 behavior). Driven by the S3 preset ladder
	 * ({@link #SettingsPaintPreset}); hand-edits survive only while the
	 * preset is {@link PaintPreset#CUSTOM}.
	 */
	StringType SettingsPaintPrune = new StringType(null);

	/**
	 * S3: the active speed/quality preset. Presets own
	 * {@link #SettingsGeneratorConfig}, {@link #SettingsPaintPrune},
	 * {@link #SettingsClickInterval} and {@link #SettingsClickVerifyInterval}
	 * and re-apply them when the draw dialog opens; hand-tweaking one of those
	 * controls flips this to {@link PaintPreset#CUSTOM}, which stops the
	 * re-apply (hand-edited config values then survive). Not exposed in the
	 * settings GUI — the DrawDialog preset row is the UI.
	 */
	EnumType<PaintPreset> SettingsPaintPreset = new EnumType<>(PaintPreset.BALANCED);

	/**
	 * S3: canvas-click verification cadence — the painter verifies the painted
	 * pixel (the two {@code getPixelColor} captures + retry loop) on every Nth
	 * canvas click. 1 = every click, the pre-S3 behavior. Tool-change
	 * verification is never skipped (a missed color change would corrupt every
	 * following blob). Hidden — set by the presets.
	 */
	IntType SettingsClickVerifyInterval = new IntType(1, 1, 1000);

	/**
	 * S2: measured per-{@code Robot.getPixelColor} screen-capture cost in
	 * milliseconds (fractional, stored as text), calibrated after each paint
	 * run by inverting the {@link PaintTimeEstimator} model against the
	 * realized pace. Unset means {@link PaintTimeEstimator#DEFAULT_CAPTURE_MS}.
	 * Hidden.
	 */
	StringType SettingsCaptureMs = new StringType(null);

	/**
	 * Palettized mode (PLAN-PALETTIZED-MODE.md). Which pipeline the draw
	 * dialog runs; selected per draw via the DrawDialog mode toggle, not the
	 * settings GUI.
	 */
	EnumType<DrawingMode> SettingsDrawingMode = new EnumType<>(DrawingMode.Brush);

	/** Palettized: the number of exact palette colors N. */
	IntType SettingsPalettizedColors = new IntType(32, 2, 64);

	/** Palettized: detail level (virtual-grid pitch in texels). */
	EnumType<PalettizedDetail> SettingsPalettizedDetail = new EnumType<>(PalettizedDetail.Fine);

	/** Palettized: Floyd–Steinberg dither (≈2× stamps on smooth images). */
	BoolType SettingsPalettizedDither = new BoolType(false);

	/** Palettized: click the clear-canvas button before painting. */
	BoolType SettingsPalettizedClearFirst = new BoolType(true);

	/** Palettized: skip cells whose color matches the sign material (advanced). */
	BoolType SettingsPalettizedSkipBase = new BoolType(false);

	/**
	 * Serialized square-brush geometry ({@code a=3.125;b=0;minSize=1.0}, see
	 * {@link com.bobrust.generator.tiler.SquareBrushGeometry#parse}): the
	 * measured footprint side in texels is {@code a·SIZE + b}. Defaults derive
	 * from the circle-brush measurements until the square-brush calibration
	 * pattern is run. Hidden.
	 */
	StringType SettingsSquareBrush = new StringType(null);

	/**
	 * Serialized HSV-picker mapping fitted by the probe pass
	 * ({@code x0=…;wx=…;y0=…;wy=…;h0=…;wh=…}, see
	 * {@link com.bobrust.robot.hsv.HsvPickerModel#parse}). Persisted after
	 * every probe run and used as the next run's prior. Hidden.
	 */
	StringType SettingsHsvPicker = new StringType(null);

	// Used for internal save state
	InternalSettings InternalSettings = new InternalSettings();
	
	/**
	 * The runtime generator configuration: the persisted
	 * {@link #SettingsGeneratorConfig} string when set, otherwise
	 * {@link GeneratorConfig#DEFAULT} (missing keys also fall back per key).
	 */
	static GeneratorConfig getGeneratorConfig() {
		return GeneratorConfig.parse(SettingsGeneratorConfig.get());
	}

	/**
	 * The paint-plan pruning options: the persisted {@link #SettingsPaintPrune}
	 * string when set, otherwise {@link BlobPruner.Options#NONE} (no pruning).
	 */
	static BlobPruner.Options getPaintPruneOptions() {
		return BlobPruner.Options.parse(SettingsPaintPrune.get());
	}

	/**
	 * The calibrated per-capture cost for the paint-time estimator, or the
	 * estimator's default prior when never measured.
	 */
	static double getCaptureMs() {
		String text = SettingsCaptureMs.get();
		if (text != null && !text.isBlank()) {
			try {
				return Double.parseDouble(text.trim());
			} catch (NumberFormatException ignored) {
				// Malformed persisted value — fall through to the prior
			}
		}
		return PaintTimeEstimator.DEFAULT_CAPTURE_MS;
	}

	/**
	 * Record a capture cost measured from a realized paint run: the first
	 * measurement is taken as-is, later ones blend 50/50 (EWMA) so the value
	 * tracks the platform without jumping on a single noisy run.
	 */
	static void recordMeasuredCaptureMs(double measured) {
		String raw = SettingsCaptureMs.get();
		double prior = (raw == null || raw.isBlank()) ? measured : getCaptureMs();
		SettingsCaptureMs.set("%.3f".formatted(prior * 0.5 + measured * 0.5));
	}

	static Color getSettingsBackgroundCalculated() {
		Color color = SettingsBackground.get();
		if (color == null) {
			return SettingsSign.get().getAverageColor();
		}
		
		return color;
	}
}
