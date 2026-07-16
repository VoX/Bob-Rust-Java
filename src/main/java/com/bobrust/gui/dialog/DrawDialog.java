package com.bobrust.gui.dialog;

import com.bobrust.generator.BlobPruner;
import com.bobrust.generator.BorstGenerator;
import com.bobrust.generator.BorstImage;
import com.bobrust.generator.BorstUtils;
import com.bobrust.generator.GeneratorConfig;
import com.bobrust.generator.Model;
import com.bobrust.generator.sorter.Blob;
import com.bobrust.generator.sorter.BlobList;
import com.bobrust.generator.sorter.PaintPlan;
import com.bobrust.generator.tiler.PalettizedPlanner;
import com.bobrust.generator.tiler.SquareBrushGeometry;
import com.bobrust.util.metrics.ImageMetrics;
import com.bobrust.gui.comp.JIntegerField;
import com.bobrust.gui.comp.JResizeComponent;
import com.bobrust.gui.comp.JStyledToggleButton;
import com.bobrust.robot.BobRustPainter;
import com.bobrust.robot.BobRustPalette;
import com.bobrust.robot.FieldInput;
import com.bobrust.robot.PalettizedPainter;
import com.bobrust.robot.error.PaintingInterrupted;
import com.bobrust.robot.hsv.HsvPickerModel;
import com.bobrust.robot.io.AwtRobotIO;
import com.bobrust.settings.Settings;
import com.bobrust.settings.data.DrawingMode;
import com.bobrust.settings.data.PaintPreset;
import com.bobrust.settings.data.PalettizedDetail;
import com.bobrust.util.*;
import com.bobrust.util.data.AppConstants;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class DrawDialog extends JDialog {
	private static final Logger LOGGER = LogManager.getLogger(DrawDialog.class);
	private static final Dimension REGULAR = new Dimension(320, 300);
	private static final Dimension MINIMIZED = new Dimension(120, 40);
	/** Debounce for the live estimate recompute (S2). */
	private static final int ESTIMATE_DEBOUNCE_MS = 300;
	/** Don't calibrate the capture cost from runs shorter than this. */
	private static final int CALIBRATION_MIN_SHAPES = 50;

	public final BobRustPalette rustPalette;
	private final BobRustPainter rustPainter;
	private final RegionSelectionDialog selectionDialog;

	private final ScreenDrawDialog parent;

	private final JTextField maxShapesField;
	private final JIntegerField clickIntervalField;
	final JSlider shapesSlider;

	private final JLabel minShapeLabel;
	private final JLabel maxShapeLabel;

	// Palettized mode (PLAN-PALETTIZED-MODE.md §7): mode toggle + control set
	private final JStyledToggleButton brushModeButton;
	private final JStyledToggleButton palettizedModeButton;
	private final JPanel palettizedPanel;
	private JIntegerField palettizedColorsField;
	private JSlider palettizedColorsSlider;
	/** Guards the colors field/slider two-way sync from looping. */
	private boolean suppressColorsSync;
	/** The latest computed palettized plan + exact preview (worker-published). */
	private volatile PalettizedPlanner.PlanResult palettizedPlanResult;

	// SA-only rows, hidden in palettized mode
	private final JPanel qualityLabelPanel;
	private final JPanel presetPanel;
	private final JLabel shapeCountLabel;
	private final JPanel shapeCountPanel;

	// S3 preset row + S2 live estimate readout
	private final Map<PaintPreset, JStyledToggleButton> presetButtons = new EnumMap<>(PaintPreset.class);
	private final ButtonGroup presetGroup = new ButtonGroup();
	/** S5/P2b: explicit opaque-paint (stencil) override — the auto alpha classifier can never select it. */
	private final JCheckBox stencilCheckbox;
	/** The alpha floor stencil overrode, captured when it was switched ON, restored when switched OFF. */
	private int preStencilFloor = AppConstants.MIN_ALPHA_INDEX;
	private final JLabel presetCustomLabel;
	private final JLabel estimateLabel;
	private final JLabel estimateDetailLabel;
	private final Timer estimateDebounce;
	private int estimateRequest;
	/** Guards preset/slider updates made by code from flipping to (Custom). */
	private boolean suppressDirty;

	// Borst stuff
	private GraphicsConfiguration monitor;
	private Model previousBorstModel;
	private int drawnShapes;
	/**
	 * The explicit paint instruction list + resume cursor (S1c). Replaces the
	 * old 'previouslyUsed' prefix bookkeeping so budget selection / pruning
	 * and resume-after-interrupt stay correct together.
	 */
	private final PaintPlan paintPlan = new PaintPlan();
	// The generator's input image + background, kept for the pruner's
	// re-render-and-verify pass (only consulted when pruning is enabled).
	private BufferedImage lastScaledImage;
	private int lastBackground;
	final BorstGenerator borstGenerator;

	public DrawDialog(ScreenDrawDialog parent) {
		super(parent, "Draw Settings", ModalityType.APPLICATION_MODAL);
		this.parent = parent;
		this.rustPalette = new BobRustPalette();
		this.rustPainter = new BobRustPainter(rustPalette);
		this.borstGenerator = new BorstGenerator(this::onBorstData);
		this.selectionDialog = new RegionSelectionDialog(this, false);

		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		setIconImage(AppConstants.DIALOG_ICON);
		setResizable(false);
		setSize(REGULAR);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowActivated(WindowEvent e) {
				clickIntervalField.requestFocus();
			}
		});

		JPanel rootPanel = new JPanel();
		rootPanel.setLayout(new BoxLayout(rootPanel, BoxLayout.Y_AXIS));
		rootPanel.setBorder(new EmptyBorder(3, 3, 3, 3));
		setContentPane(rootPanel);

		// The drawing-mode toggle (PLAN-PALETTIZED-MODE.md §7): a per-draw
		// decision, so it lives here and not in the settings dialog.
		JPanel modePanel = new JPanel();
		modePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		modePanel.setLayout(new BoxLayout(modePanel, BoxLayout.X_AXIS));
		brushModeButton = new JStyledToggleButton("Brush (palette)");
		brushModeButton.setToolTipText("The classic pipeline: annealed shapes over the 64-swatch palette");
		palettizedModeButton = new JStyledToggleButton("Palettized (pixel)");
		palettizedModeButton.setToolTipText(
			"Quantize to N exact colors, square-tile, colors entered through the HSV picker at full opacity. "
				+ "Toggle the COLOUR panel to the HSV picker before painting.");
		ButtonGroup modeGroup = new ButtonGroup();
		modeGroup.add(brushModeButton);
		modeGroup.add(palettizedModeButton);
		brushModeButton.addActionListener(event -> setDrawingMode(DrawingMode.Brush));
		palettizedModeButton.addActionListener(event -> setDrawingMode(DrawingMode.Palettized));
		modePanel.add(brushModeButton);
		modePanel.add(palettizedModeButton);
		rootPanel.add(modePanel);

		// S3: the preset ladder — four named points on the measured
		// speed/quality curve; the shape slider below stays the fine control.
		qualityLabelPanel = new JPanel();
		qualityLabelPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		qualityLabelPanel.setLayout(new BoxLayout(qualityLabelPanel, BoxLayout.X_AXIS));
		qualityLabelPanel.add(new JLabel("Quality"));
		presetCustomLabel = new JLabel(" (Custom)");
		presetCustomLabel.setVisible(false);
		qualityLabelPanel.add(presetCustomLabel);
		rootPanel.add(qualityLabelPanel);

		presetPanel = new JPanel();
		presetPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		presetPanel.setLayout(new BoxLayout(presetPanel, BoxLayout.X_AXIS));
		for (PaintPreset preset : PaintPreset.values()) {
			if (preset == PaintPreset.CUSTOM) {
				continue;
			}
			JStyledToggleButton button = new JStyledToggleButton(preset.getDisplayName());
			button.setToolTipText(switch (preset) {
				case BLAZING -> "Draft: hard 70% blob budget, fastest clicks, sparse verification "
					+ "(≈ −0.015 SSIM vs Balanced at the same clicks)";
				case FAST -> "Adaptive: per-image alpha floor, drops blobs costing <1% quality, faster clicks";
				case BALANCED -> "Default: per-image alpha floor (sharper photos), only verified-free blobs skipped";
				default -> "No pruning, extra search effort (simulated annealing), conservative clicks";
			});
			button.addActionListener(event -> applyPreset(preset));
			presetGroup.add(button);
			presetButtons.put(preset, button);
			presetPanel.add(button);
		}
		rootPanel.add(presetPanel);

		// S5/P2b: stencil (opaque) toggle. Opaque paint is dominated on photos but the single biggest measured win
		// on text/logos (+0.094 SSIM on glyphs), and the auto alpha floor can never pick it (S2, by construction) —
		// so it is an explicit user choice living beside the presets.
		stencilCheckbox = new JCheckBox("Stencil (text / logos)");
		stencilCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		stencilCheckbox.setFocusable(false);
		stencilCheckbox.setToolTipText(
			"Opaque paint only: much sharper text and hard edges, visibly wrong colors on photos. Never chosen automatically.");
		stencilCheckbox.addActionListener(event -> onStencilToggled());
		rootPanel.add(stencilCheckbox);

		shapeCountLabel = new JLabel("Shape Count");
		rootPanel.add(shapeCountLabel);

		JPanel panel = new JPanel();
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
		rootPanel.add(panel);
		shapeCountPanel = panel;

		Dimension buttonSize = new Dimension(60, 20);
		shapesSlider = new JSlider();
		maxShapesField = new JTextField("1");
		maxShapesField.setMaximumSize(buttonSize);
		maxShapesField.setPreferredSize(buttonSize);
		maxShapesField.addActionListener((event) -> {
			int value;
			try {
				value = Integer.parseInt(maxShapesField.getText());
			} catch (NumberFormatException e) {
				Toolkit.getDefaultToolkit().beep();

				value = shapesSlider.getValue();
				maxShapesField.setText(Integer.toString(value));
			}

			shapesSlider.setValue(value);
			parent.repaint();
		});
		panel.add(maxShapesField);

		minShapeLabel = new JLabel("1");
		minShapeLabel.setBorder(new EmptyBorder(0, 10, 0, 5));
		panel.add(minShapeLabel);

		shapesSlider.setMinimum(1);
		shapesSlider.setUI(new BasicSliderUI(shapesSlider) {
			@Override
			public void paintFocus(Graphics g) {
				// don't paint focus
			}
		});
		shapesSlider.setOpaque(false);
		shapesSlider.addChangeListener((event) -> {
			int value = shapesSlider.getValue();
			if (!maxShapesField.hasFocus()) {
				maxShapesField.setText(Integer.toString(value));
			}

			// Only user gestures dirty the preset — programmatic updates
			// (generator progress, preset application) don't.
			if (!suppressDirty
					&& (shapesSlider.getValueIsAdjusting() || shapesSlider.hasFocus() || maxShapesField.hasFocus())) {
				markCustom();
			}

			parent.topPanel.setGeneratedShapes(shapesSlider.getValue(), borstGenerator.data.getIndex());
			parent.repaint();
			scheduleEstimate();
		});
		panel.add(shapesSlider);

		maxShapeLabel = new JLabel("1");
		maxShapeLabel.setBorder(new EmptyBorder(0, 5, 0, 10));
		panel.add(maxShapeLabel);

		palettizedPanel = createPalettizedPanel();
		rootPanel.add(palettizedPanel);

		JLabel clickIntervalLabel = new JLabel("Clicks per second");
		clickIntervalLabel.setToolTipText("The amount of clicks per second");
		rootPanel.add(clickIntervalLabel);

		JPanel clickIntervalPanel = new JPanel();
		clickIntervalPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		rootPanel.add(clickIntervalPanel);
		clickIntervalPanel.setLayout(new BoxLayout(clickIntervalPanel, BoxLayout.X_AXIS));

		Dimension buttonSize2 = new Dimension(60, 20);
		clickIntervalField = new JIntegerField(
			Settings.SettingsClickInterval.get(),
			Settings.SettingsClickInterval.getMin(),
			Settings.SettingsClickInterval.getMax()
		);
		clickIntervalField.setFocusable(true);
		clickIntervalField.setPreferredSize(buttonSize2);
		clickIntervalField.setMaximumSize(buttonSize2);
		clickIntervalField.setMinimumSize(buttonSize2);
		clickIntervalField.setAlignmentX(0.0f);
		clickIntervalField.addActionListener((event) -> {
			int value = clickIntervalField.getNumberValue();
			if (value != Settings.SettingsClickInterval.get()) {
				Settings.SettingsClickInterval.set(value);
				markCustom();
			}
			scheduleEstimate();
		});
		clickIntervalPanel.add(clickIntervalField);

		// S2: the live estimate readout — replaces the EDT-blocking
		// "Calculate Exact Time" button. A debounced background worker
		// recomputes select→prune→sort→estimate off the EDT.
		// TODO(P4 follow-up): replace the raw shape spinner with a click/time target + predicted-quality readout,
		// driving the generator's qualityStop auto-stop from a UI curve (docs/SPEED-IMPL-PLAN.md S4).
		estimateLabel = new JLabel("Estimating…");
		estimateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		estimateLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
		estimateLabel.setFont(estimateLabel.getFont().deriveFont(Font.BOLD));
		rootPanel.add(estimateLabel);

		estimateDetailLabel = new JLabel(" ");
		estimateDetailLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		estimateDetailLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
		rootPanel.add(estimateDetailLabel);

		estimateDebounce = new Timer(ESTIMATE_DEBOUNCE_MS, event -> runEstimate());
		estimateDebounce.setRepeats(false);

		JButton changeAreaButton = new JButton("Update canvas area");
		changeAreaButton.setFocusable(false);
		changeAreaButton.addActionListener((event) -> {
			parent.setAlwaysOnTop(true);
			parent.repaint();
			parent.updateCanvasRect(selectionDialog.openDialog(
				monitor, false, JResizeComponent.RenderType.BASIC, "Update canvas region and press ESC", null, parent.canvasRect).selection());
			parent.setAlwaysOnTop(false);
			parent.repaint();
		});
		rootPanel.add(changeAreaButton);

		JButton colorPaletteButton = new JButton("Select Color Palette And Draw");
		colorPaletteButton.setFocusable(false);
		colorPaletteButton.addActionListener((event) -> {
			if (Settings.SettingsDrawingMode.get() == DrawingMode.Palettized) {
				// No 64-swatch scan in palettized mode — the probe pass is the
				// pre-flight (§7 draw-button gate)
				startPalettizedDrawingAction(getLocation());
				return;
			}
			if (findColorPalette()) {
				previousBorstModel = borstGenerator.stop();

				Point previous_location = getLocation();
				startDrawingAction(previous_location);
			} else {
				showPaletteWarning();
			}
		});
		rootPanel.add(colorPaletteButton);

		updateModeVisibility();
	}

	/** The palettized control set (§7): colors, detail, dither, clear, skip-base. */
	private JPanel createPalettizedPanel() {
		JPanel root = new JPanel();
		root.setAlignmentX(Component.LEFT_ALIGNMENT);
		root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

		JLabel colorsLabel = new JLabel("Colors");
		colorsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		root.add(colorsLabel);

		JPanel colorsRow = new JPanel();
		colorsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		colorsRow.setLayout(new BoxLayout(colorsRow, BoxLayout.X_AXIS));

		Dimension fieldSize = new Dimension(60, 20);
		palettizedColorsField = new JIntegerField(
			Settings.SettingsPalettizedColors.get(),
			Settings.SettingsPalettizedColors.getMin(),
			Settings.SettingsPalettizedColors.getMax());
		palettizedColorsField.setMaximumSize(fieldSize);
		palettizedColorsField.setPreferredSize(fieldSize);
		palettizedColorsField.addActionListener(event -> {
			int value = palettizedColorsField.getNumberValue();
			Settings.SettingsPalettizedColors.set(value);
			if (!suppressColorsSync) {
				suppressColorsSync = true;
				try {
					palettizedColorsSlider.setValue(value);
				} finally {
					suppressColorsSync = false;
				}
			}
			scheduleEstimate();
		});
		colorsRow.add(palettizedColorsField);

		palettizedColorsSlider = new JSlider(
			Settings.SettingsPalettizedColors.getMin(),
			Settings.SettingsPalettizedColors.getMax(),
			Settings.SettingsPalettizedColors.get());
		palettizedColorsSlider.setOpaque(false);
		palettizedColorsSlider.addChangeListener(event -> {
			if (suppressColorsSync) {
				return;
			}
			int value = palettizedColorsSlider.getValue();
			Settings.SettingsPalettizedColors.set(value);
			suppressColorsSync = true;
			try {
				palettizedColorsField.setText(Integer.toString(value));
			} finally {
				suppressColorsSync = false;
			}
			scheduleEstimate();
		});
		colorsRow.add(palettizedColorsSlider);
		root.add(colorsRow);

		JPanel detailRow = new JPanel();
		detailRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		detailRow.setLayout(new BoxLayout(detailRow, BoxLayout.X_AXIS));
		JLabel detailLabel = new JLabel("Detail ");
		detailRow.add(detailLabel);
		JComboBox<PalettizedDetail> detailCombo = new JComboBox<>(PalettizedDetail.values());
		detailCombo.setSelectedItem(Settings.SettingsPalettizedDetail.get());
		detailCombo.setMaximumSize(new Dimension(120, 22));
		detailCombo.setToolTipText("Fine: pitch 3.2 texels (160x160 on XL). Economy: pitch 4.0 — "
			+ "20-35% fewer actions, visibly blockier.");
		detailCombo.addActionListener(event -> {
			Settings.SettingsPalettizedDetail.set((PalettizedDetail) detailCombo.getSelectedItem());
			scheduleEstimate();
		});
		detailRow.add(detailCombo);
		root.add(detailRow);

		JCheckBox ditherCheckbox = new JCheckBox("Dither");
		ditherCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		ditherCheckbox.setFocusable(false);
		ditherCheckbox.setSelected(Settings.SettingsPalettizedDither.get());
		ditherCheckbox.setToolTipText("Floyd-Steinberg error diffusion: smoother gradients at roughly "
			+ "2x the stamps on smooth images");
		ditherCheckbox.addActionListener(event -> {
			Settings.SettingsPalettizedDither.set(ditherCheckbox.isSelected());
			scheduleEstimate();
		});
		root.add(ditherCheckbox);

		JCheckBox clearFirstCheckbox = new JCheckBox("Clear canvas first");
		clearFirstCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		clearFirstCheckbox.setFocusable(false);
		clearFirstCheckbox.setSelected(Settings.SettingsPalettizedClearFirst.get());
		clearFirstCheckbox.setToolTipText("One clear-canvas click before painting: a deterministic substrate");
		clearFirstCheckbox.addActionListener(event ->
			Settings.SettingsPalettizedClearFirst.set(clearFirstCheckbox.isSelected()));
		root.add(clearFirstCheckbox);

		JCheckBox skipBaseCheckbox = new JCheckBox("Skip sign-colored cells");
		skipBaseCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		skipBaseCheckbox.setFocusable(false);
		skipBaseCheckbox.setSelected(Settings.SettingsPalettizedSkipBase.get());
		skipBaseCheckbox.setToolTipText("Advanced: leave cells matching the sign material unpainted. "
			+ "The material is textured and lighting-shifted in game — only for freshly placed signs.");
		skipBaseCheckbox.addActionListener(event -> {
			Settings.SettingsPalettizedSkipBase.set(skipBaseCheckbox.isSelected());
			scheduleEstimate();
		});
		root.add(skipBaseCheckbox);

		return root;
	}

	/** Mode toggle handler: persist, swap the control set, swap the pipeline. */
	private void setDrawingMode(DrawingMode mode) {
		if (Settings.SettingsDrawingMode.get() == mode) {
			return;
		}
		Settings.SettingsDrawingMode.set(mode);
		updateModeVisibility();

		if (mode == DrawingMode.Palettized) {
			// The SA generator is pure waste while palettized is selected
			previousBorstModel = borstGenerator.stop();
		} else if (monitor != null) {
			restartGenerationFresh();
		}
		scheduleEstimate();
		parent.repaint();
	}

	/** Shows the control set of the active mode (SA rows ⇄ palettized rows). */
	private void updateModeVisibility() {
		boolean palettized = Settings.SettingsDrawingMode.get() == DrawingMode.Palettized;
		qualityLabelPanel.setVisible(!palettized);
		presetPanel.setVisible(!palettized);
		stencilCheckbox.setVisible(!palettized);
		shapeCountLabel.setVisible(!palettized);
		shapeCountPanel.setVisible(!palettized);
		palettizedPanel.setVisible(palettized);
		brushModeButton.setSelected(!palettized);
		palettizedModeButton.setSelected(palettized);
		getContentPane().revalidate();
		getContentPane().repaint();
	}

	/** The exact palettized preview for {@link ScreenDrawDialog}, or null. */
	BufferedImage getPalettizedPreviewImage() {
		PalettizedPlanner.PlanResult result = palettizedPlanResult;
		return result == null ? null : result.cellImage();
	}

	/**
	 * S3: write the preset's parameter set into the settings, refresh the
	 * controls it owns, and restart generation if the generation-side config
	 * changed (the model is discarded — same as reopening the dialog).
	 */
	private void applyPreset(PaintPreset preset) {
		GeneratorConfig before = Settings.getGeneratorConfig();
		suppressDirty = true;
		try {
			preset.apply();
			clickIntervalField.setValue(Settings.SettingsClickInterval.get());
			clickIntervalField.setText(Integer.toString(clickIntervalField.getNumberValue()));
			JStyledToggleButton button = presetButtons.get(preset);
			if (button != null && !button.isSelected()) {
				presetGroup.setSelected(button.getModel(), true);
			}
			stencilCheckbox.setSelected(Settings.getGeneratorConfig().minAlphaIndex() == 5);
			presetCustomLabel.setVisible(false);
		} finally {
			suppressDirty = false;
		}

		if (monitor != null && !Settings.getGeneratorConfig().equals(before)) {
			LOGGER.info("Preset {} changed the generator config, restarting generation", preset);
			restartGenerationFresh();
		}
		scheduleEstimate();
	}

	/**
	 * S5/P2b: the stencil toggle. ON forces the opaque alpha floor ({@code minAlpha=5}) as a hand-tweak (opaque is a
	 * deliberate override, not a preset); OFF restores the exact floor stencil overrode — captured on the way in,
	 * because {@code markCustom()} flips the preset to CUSTOM so the pre-stencil floor (e.g. a preset's auto floor)
	 * can't be recovered from the preset on the way out. This keeps toggling a clean round-trip. Either way the
	 * generation config changed, so the model is stale — restart fresh, same contract as {@link #applyPreset}.
	 * {@code setSelected} does not fire this listener, so the sync paths can set the box freely.
	 */
	private void onStencilToggled() {
		GeneratorConfig current = Settings.getGeneratorConfig();
		if (stencilCheckbox.isSelected()) {
			preStencilFloor = current.minAlphaIndex(); // may be MIN_ALPHA_AUTO (-1) for a BALANCED/FAST preset
			Settings.SettingsGeneratorConfig.set(current.withMinAlphaIndex(5).serialize());
			markCustom();
		} else {
			Settings.SettingsGeneratorConfig.set(current.withMinAlphaIndex(preStencilFloor).serialize());
			preStencilFloor = AppConstants.MIN_ALPHA_INDEX; // consumed; default fallback if stencil was on at open
		}
		if (monitor != null) {
			restartGenerationFresh();
		}
		scheduleEstimate();
	}

	/** Hand-tweaking a preset-owned control flips the display to (Custom). */
	private void markCustom() {
		if (Settings.SettingsPaintPreset.get() == PaintPreset.CUSTOM) {
			return;
		}
		Settings.SettingsPaintPreset.set(PaintPreset.CUSTOM);
		presetGroup.clearSelection();
		presetCustomLabel.setVisible(true);
	}

	/**
	 * Reflect the persisted preset in the UI at dialog open; a non-CUSTOM
	 * preset re-applies its parameter set so the settings can't drift from
	 * what the selected button claims.
	 */
	private void syncPresetFromSettings() {
		PaintPreset preset = Settings.SettingsPaintPreset.get();
		suppressDirty = true;
		try {
			if (preset != PaintPreset.CUSTOM) {
				preset.apply();
			}
			clickIntervalField.setValue(Settings.SettingsClickInterval.get());
			clickIntervalField.setText(Integer.toString(clickIntervalField.getNumberValue()));
			presetGroup.clearSelection();
			JStyledToggleButton button = presetButtons.get(preset);
			if (button != null) {
				presetGroup.setSelected(button.getModel(), true);
			}
			stencilCheckbox.setSelected(Settings.getGeneratorConfig().minAlphaIndex() == 5);
			presetCustomLabel.setVisible(preset == PaintPreset.CUSTOM);
		} finally {
			suppressDirty = false;
		}
	}

	/** Full generation restart with a fresh model (config changed). */
	private void restartGenerationFresh() {
		borstGenerator.stop();
		previousBorstModel = null;
		paintPlan.reset();
		drawnShapes = 0;
		parent.shapeRender.reset();
		parent.repaint();
		startGeneration(0);
	}

	/** Debounced trigger for the S2 estimate worker. Safe to call often. */
	private void scheduleEstimate() {
		estimateDebounce.restart();
	}

	/**
	 * S2: recompute the live paint-time + match estimate off the EDT. The
	 * worker previews exactly what pressing Draw right now would paint —
	 * a copy of the current plan extended (with pruning) to the slider count —
	 * and runs the calibrated cost model over it.
	 */
	private void runEstimate() {
		if (Settings.SettingsDrawingMode.get() == DrawingMode.Palettized) {
			runPalettizedEstimate();
			return;
		}
		final int request = ++estimateRequest;
		final int count = shapesSlider.getValue();
		final int cps = Math.max(1, clickIntervalField.getNumberValue());
		final int verifyInterval = Math.max(1, Settings.SettingsClickVerifyInterval.get());
		final int autosaveInterval = Math.max(1, Settings.SettingsAutosaveInterval.get());
		final double captureMs = Settings.getCaptureMs();
		final BlobPruner.Options pruneOptions = Settings.getPaintPruneOptions();
		final BufferedImage scaled = lastScaledImage;
		final int background = lastBackground;

		new SwingWorker<PaintTimeEstimator.Estimate, Void>() {
			@Override
			protected PaintTimeEstimator.Estimate doInBackground() {
				try {
					List<Blob> snapshot;
					synchronized (borstGenerator.data) {
						snapshot = List.copyOf(borstGenerator.data.getBlobs());
					}

					BorstImage target = scaled == null ? null
						: new BorstImage(ImageMetrics.argbPixels(scaled), scaled.getWidth());
					PaintPlan scratch = paintPlan.copy();
					scratch.extend(snapshot, count, pruneOptions, target, background);
					BlobList toPaint = scratch.paintList(count);

					long millis = PaintTimeEstimator.estimateMillis(
						toPaint, cps, captureMs, verifyInterval, autosaveInterval);
					double match = -1;
					if (target != null) {
						int end = Math.max(scratch.getPainted(), scratch.planIndexFor(count));
						match = PaintTimeEstimator.matchPercent(
							scratch.getInstructions().subList(0, end), target, background);
					}
					return new PaintTimeEstimator.Estimate(
						millis, toPaint.size(), PaintTimeEstimator.toolChanges(toPaint), match);
				} catch (Exception e) {
					LOGGER.warn("Estimate computation failed", e);
					return null;
				}
			}

			@Override
			protected void done() {
				if (request != estimateRequest) {
					return; // A newer estimate is on its way
				}
				PaintTimeEstimator.Estimate estimate;
				try {
					estimate = get();
				} catch (Exception e) {
					return;
				}
				if (estimate == null) {
					return;
				}
				String match = estimate.matchPercent() < 0
					? ""
					: "   ·   ≈ %.0f%% match".formatted(estimate.matchPercent());
				estimateLabel.setText("≈ %s paint%s".formatted(formatDuration(estimate.millis()), match));
				estimateDetailLabel.setText("%d blobs · %d tool changes"
					.formatted(estimate.blobs(), estimate.toolChanges()));
				parent.topPanel.setExactGenerationLabel(estimate.millis());
			}
		}.execute();
	}

	private static String formatDuration(long millis) {
		long seconds = millis / 1000;
		return seconds < 60
			? "%ds".formatted(seconds)
			: "%dm %02ds".formatted(seconds / 60, seconds % 60);
	}

	/**
	 * Palettized branch of the live estimate: recompute the plan (quantize +
	 * snap + tile — tens of ms at cell resolution) and the §7 cost model off
	 * the EDT. The published PlanResult doubles as the preview and the plan
	 * the draw button executes, so preview == paint by construction.
	 */
	private void runPalettizedEstimate() {
		final int request = ++estimateRequest;
		final Image source = parent.parent.getDrawImage();
		final Rectangle canvasRect = new Rectangle(parent.parent.getCanvasRect());
		final Rectangle imageRect = new Rectangle(parent.parent.getImageRect());
		if (source == null || canvasRect.width <= 0 || canvasRect.height <= 0) {
			return;
		}

		final Sign sign = Settings.SettingsSign.get();
		final Color background = Settings.getSettingsBackgroundCalculated();
		final int colors = Settings.SettingsPalettizedColors.get();
		final double pitch = Settings.SettingsPalettizedDetail.get().getPitch();
		final boolean dither = Settings.SettingsPalettizedDither.get();
		final boolean skipBase = Settings.SettingsPalettizedSkipBase.get();
		final SquareBrushGeometry brush = SquareBrushGeometry.parse(Settings.SettingsSquareBrush.get());
		final HsvPickerModel snapModel = HsvPickerModel.parse(Settings.SettingsHsvPicker.get());
		final int cps = Math.max(1, clickIntervalField.getNumberValue());
		final int verifyInterval = Math.max(1, Settings.SettingsClickVerifyInterval.get());
		final int autosaveInterval = Math.max(1, Settings.SettingsAutosaveInterval.get());
		final double captureMs = Settings.getCaptureMs();

		new SwingWorker<PalettizedPlanner.PlanResult, Void>() {
			@Override
			protected PalettizedPlanner.PlanResult doInBackground() {
				try {
					return PalettizedPlanner.plan(source, canvasRect, imageRect, sign, background,
						colors, pitch, dither, skipBase, brush, snapModel);
				} catch (Exception e) {
					LOGGER.warn("Palettized plan computation failed", e);
					return null;
				}
			}

			@Override
			protected void done() {
				if (request != estimateRequest) {
					return; // A newer estimate is on its way
				}
				PalettizedPlanner.PlanResult result;
				try {
					result = get();
				} catch (Exception e) {
					return;
				}
				if (result == null) {
					return;
				}

				// An identical recompute keeps the old plan object so an
				// interrupted paint's resume cursor survives control nudges
				PalettizedPlanner.PlanResult previous = palettizedPlanResult;
				if (previous == null
						|| !previous.plan().getOps().equals(result.plan().getOps())
						|| !java.util.Arrays.equals(previous.plan().getPaletteRgb(), result.plan().getPaletteRgb())) {
					palettizedPlanResult = result;
				}

				PalettizedTimeEstimator.Estimate estimate = PalettizedTimeEstimator.estimate(
					palettizedPlanResult.plan(), cps, captureMs, verifyInterval, autosaveInterval);
				estimateLabel.setText("≈ %s paint".formatted(formatDuration(estimate.millis())));
				estimateDetailLabel.setText("%d colors · %d stamps · %d size entries"
					.formatted(estimate.colors(), estimate.stamps(), estimate.sizeEntries()));
				parent.topPanel.setExactGenerationLabel(estimate.millis());
				parent.repaint();
			}
		}.execute();
	}

	/**
	 * The palettized draw path (§6/§7): gate on calibration, then run the
	 * PalettizedPainter over the current plan in the same UI envelope as the
	 * legacy path. The resume cursor advances by whatever the interrupt
	 * carried; pressing draw again continues the same plan.
	 */
	private void startPalettizedDrawingAction(Point previousLocation) {
		PalettizedPlanner.PlanResult planResult = palettizedPlanResult;
		if (planResult == null || planResult.plan().getTotalStamps() == 0) {
			Toolkit.getDefaultToolkit().beep();
			LOGGER.warn("No palettized plan computed yet — nothing to paint");
			return;
		}
		if (!parent.parent.config.isPalettizedCalibrated()) {
			JOptionPane.showMessageDialog(this,
				"Palettized mode needs its picker calibration.\n"
					+ "Run 'Setup Buttons' and mark the square brush, SIZE/OPACITY fields,\n"
					+ "SV square, hue bar and color swatch (with the COLOUR panel toggled\n"
					+ "to the HSV picker).",
				"Palettized mode is not calibrated",
				JOptionPane.WARNING_MESSAGE);
			return;
		}

		parent.setAlwaysOnTop(true);
		parent.repaint();
		setAlwaysOnTop(true);
		start = -1;

		Thread thread = new Thread(() -> {
			var plan = planResult.plan();
			PalettizedPainter painter = null;
			int offsetStamps = 0;
			try {
				setLocation(monitor.getBounds().getLocation());
				setSize(MINIMIZED);

				Robot robot = new Robot(monitor.getDevice());
				robot.setAutoDelay(0);
				Rectangle bounds = monitor.getBounds();

				FieldInput.DecimalKey decimalKey;
				try {
					decimalKey = FieldInput.DecimalKey.valueOf(Settings.SettingsPalettizedDecimalKey.get());
				} catch (IllegalArgumentException e) {
					decimalKey = FieldInput.DecimalKey.PERIOD;
				}

				PalettizedPainter.Config config = new PalettizedPainter.Config(
					parent.parent.config,
					parent.canvasRect,
					bounds.x, bounds.y,
					Settings.SettingsSign.get().getWidth(),
					Settings.SettingsSign.get().getHeight(),
					Settings.SettingsPalettizedDetail.get().getPitch(),
					SquareBrushGeometry.parse(Settings.SettingsSquareBrush.get()),
					Settings.SettingsClickInterval.get(),
					Settings.SettingsAutosaveInterval.get(),
					Math.max(1, Settings.SettingsClickVerifyInterval.get()),
					Settings.SettingsPalettizedClearFirst.get(),
					Settings.SettingsPalettizedPaste.get(),
					decimalKey);
				painter = new PalettizedPainter(new AwtRobotIO(robot), config);

				int remaining = plan.getTotalStamps() - plan.getPaintedStamps();
				updateTimeRemaining(0, remaining);
				LOGGER.info("Start palettized drawing");
				LOGGER.info("- Colors         : {}", plan.getColorEntries());
				LOGGER.info("- Stamps         : {} (resume cursor {})", plan.getTotalStamps(), plan.getPaintedStamps());
				LOGGER.info("- Pitch          : {}", Settings.SettingsPalettizedDetail.get().getPitch());
				LOGGER.info("- Click Interval : {}", Settings.SettingsClickInterval.get());

				painter.startDrawing(plan, this::updateTimeRemaining);
			} catch (PaintingInterrupted e) {
				boolean finished = e.getInterruptType() == PaintingInterrupted.InterruptType.PaintingFinished;
				Level level = finished ? Level.INFO : Level.WARN;
				LOGGER.log(level, finished ? "Palettized painting finished" : "Palettized painting stopped early");
				LOGGER.log(level, "- Type   : {}", e.getInterruptType());
				LOGGER.log(level, "- Stamps : {}", e.getDrawnShapes());
				offsetStamps = e.getDrawnShapes();
			} catch (Exception e) {
				LOGGER.throwing(e);
			} finally {
				plan.advancePainted(offsetStamps);

				// Persist the probe-fitted picker mapping as the next prior —
				// future previews snap through the truth, not the guess
				if (painter != null && painter.getFittedModel() != null) {
					Settings.SettingsHsvPicker.set(painter.getFittedModel().serialize());
				}

				parent.setAlwaysOnTop(false);
				setAlwaysOnTop(false);
				parent.repaint();

				setLocation(previousLocation);
				setSize(REGULAR);
			}
		}, "BobRustPalettizedDrawing Thread");
		thread.setDaemon(true);
		thread.start();
	}

	private void startDrawingAction(Point previous_location) {
		parent.setAlwaysOnTop(true);
		parent.repaint();
		setAlwaysOnTop(true);
		start = -1;

		Thread thread = new Thread(() -> {
			int offsetShapes = 0;
			BlobList list = null;
			long paintStart = 0;
			try {
				setLocation(monitor.getBounds().getLocation());
				setSize(MINIMIZED);

				int count = shapesSlider.getValue();

				BlobPruner.Options pruneOptions = Settings.getPaintPruneOptions();
				synchronized (borstGenerator.data) {
					BorstImage pruneTarget = null;
					if (pruneOptions.enabled() && lastScaledImage != null) {
						pruneTarget = new BorstImage(
							ImageMetrics.argbPixels(lastScaledImage), lastScaledImage.getWidth());
					}
					paintPlan.extend(borstGenerator.data.getBlobs(), count, pruneOptions, pruneTarget, lastBackground);
				}
				if (pruneOptions.enabled() && paintPlan.getLastPruneResult() != null) {
					var pruneResult = paintPlan.getLastPruneResult();
					LOGGER.info("Paint plan pruning: kept {}/{} blobs, true score {} -> {}",
						pruneResult.kept().size(), pruneResult.originalCount(),
						"%.6f".formatted(pruneResult.originalScore()),
						"%.6f".formatted(pruneResult.prunedScore()));
				}

				list = paintPlan.paintList(count);
				updateTimeRemaining(0, list.size());

				start = -1;
				LOGGER.info("Start drawing");
				LOGGER.info("- Alpha Index    : {}", Settings.SettingsAlpha.get());
				LOGGER.info("- Click Interval : {}", Settings.SettingsClickInterval.get());
				LOGGER.info("- Verify Interval: {}", Settings.SettingsClickVerifyInterval.get());
				LOGGER.info("- Scaling        : {}", Settings.SettingsScaling.get());
				LOGGER.info("- Sign Type      : {}", Settings.SettingsSign.get().getName());
				paintStart = System.nanoTime();
				if (!rustPainter.startDrawing(monitor, parent.canvasRect, list, this::updateTimeRemaining)) {
					LOGGER.warn("The user stopped the drawing process early");
				}
			} catch (PaintingInterrupted e) {
				e.printStackTrace();
				boolean finished = e.getInterruptType() == PaintingInterrupted.InterruptType.PaintingFinished;
				Level level = finished
					? Level.INFO
					: Level.WARN;
				LOGGER.log(level, finished
					? "Painting process finished"
					: "The user stopped the drawing process early");
				LOGGER.log(level, "- Type   : {}", e.getInterruptType());
				LOGGER.log(level, "- Shapes : {}", e.getDrawnShapes());
				offsetShapes = e.getDrawnShapes();
			} catch (Exception e) {
				LOGGER.throwing(e);
			} finally {
				// S2 calibration: solve the cost model for the per-capture
				// cost from the realized pace of this run and persist it, so
				// the next estimate predicts this machine, not the prior.
				if (list != null && paintStart != 0 && offsetShapes >= CALIBRATION_MIN_SHAPES) {
					long realizedMs = (System.nanoTime() - paintStart) / 1_000_000L;
					BlobList painted = new BlobList();
					painted.assign(list.getList(), 0, offsetShapes);
					double measured = PaintTimeEstimator.calibrateCaptureMs(
						realizedMs, painted,
						Math.max(1, Settings.SettingsClickInterval.get()),
						Math.max(1, Settings.SettingsClickVerifyInterval.get()),
						Math.max(1, Settings.SettingsAutosaveInterval.get()));
					Settings.recordMeasuredCaptureMs(measured);
					LOGGER.info("Calibrated capture cost: measured {} ms/capture over {} blobs in {} ms (persisted {} ms)",
						"%.2f".formatted(measured), offsetShapes, realizedMs,
						"%.2f".formatted(Settings.getCaptureMs()));
				}

				parent.setAlwaysOnTop(false);
				setAlwaysOnTop(false);
				parent.repaint();

				setLocation(previous_location);
				setSize(REGULAR);

				// Advance the resume cursor past what this pass painted; the
				// already-painted instructions are frozen in the plan.
				paintPlan.advancePainted(offsetShapes);

				// Start generation again
				startGeneration(offsetShapes);
			}
		}, "BobRustDrawing Thread");
		thread.setDaemon(true);
		thread.start();
	}

	private long start = -1;
	private double msDelay = 0;
	private void updateTimeRemaining(int index, int length) {
		long now = System.nanoTime();
		if (start == -1) {
			start = now;
			msDelay = (1000.0 / Settings.SettingsClickInterval.get());
		} else if (now - start > 50000000) { // 50 ms
			msDelay = ((now - start) / 1000000.0) / index;
		}

		parent.topPanel.setDrawnShapes(drawnShapes + index, drawnShapes + length, (int) (msDelay * (length - index)));
	}

	private void showPaletteWarning() {
		JEditorPane pane = new JEditorPane("text/html", "");
		pane.setEditable(false);
		pane.setOpaque(false);
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		pane.setText(
			"Could not find the color palette.<br>" +
			"If you think this is a bug please take a screenshot and create a new issue on the github.<br>" +
			"<a href=\"#blank\">https://github.com/Bob-Rust/Bob-Rust-Java/issues/new</a>"
		);
		pane.addHyperlinkListener((e) -> {
			if (e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED)) {
				UrlUtils.openIssueUrl();
			}
		});
		JOptionPane.showMessageDialog(this, pane, "Could not find the palette", JOptionPane.WARNING_MESSAGE);
	}

	private boolean findColorPalette() {
		// Take a screenshot
		BufferedImage screenshot = RustWindowUtil.captureScreenshotWithScale(monitor);
		if (screenshot == null) {
			LOGGER.warn("Failed to take screenshot. Was null");
			return false;
		}

		if (!rustPalette.initWith(screenshot, monitor, parent.parent.config)) {
			if (!RustWindowUtil.showConfirmDialog(
				"Could not find color panel, do you still wish to proceed",
				"Could not find color panel"))  {
				LOGGER.warn("User stopped the drawing because color panel was not found");
				return false;
			} else {
				LOGGER.warn("User allowed drawing even when color panel was not found");
				return true;
			}
		}

		LOGGER.info("Found the color palette");
		return true;
	}

	private void startGeneration(int offset) {
		Sign signType = Settings.SettingsSign.get();
		Color bgColor = Settings.getSettingsBackgroundCalculated();

		BufferedImage scaled = ImageUtil.getScaledInstance(
			parent.parent.getDrawImage(),
			parent.parent.getCanvasRect(),
			parent.parent.getImageRect(),
			signType.getWidth(),
			signType.getHeight(),
			bgColor,
			Settings.SettingsScaling.get()
		);

		// Apply the ICC cmyk lut filter
		if (Settings.SettingsUseICCConversion.get()) {
			scaled = ImageUtil.applyFilters(scaled);
		}

		lastScaledImage = scaled;
		lastBackground = bgColor.getRGB();

		drawnShapes += offset;
		minShapeLabel.setText(Integer.toString(drawnShapes + 1));
		shapesSlider.setMinimum(drawnShapes + 1);

		if (borstGenerator.start(
			previousBorstModel,
			scaled,
			Settings.SettingsMaxShapes.get(),
			Settings.EditorCallbackInterval.get(),
			bgColor.getRGB(),
			BorstUtils.ALPHAS[Settings.SettingsAlpha.get()],
			Settings.getGeneratorConfig()
		)) {
			if (previousBorstModel == null) {
				parent.shapeRender.reset();
				parent.shapeRender.createCanvas(scaled.getWidth(), scaled.getHeight(), bgColor.getRGB());
			}
		}
	}

	private void onBorstData(BorstGenerator.BorstData data) {
		// Invoked on the off-EDT "Borst Generator Thread". Everything below mutates Swing state
		// (repaint, slider bounds/value, labels, topPanel) and toggles suppressDirty which the
		// slider's change-listener reads, so marshal the whole callback onto the EDT — otherwise it
		// violates Swing's single-thread rule and suppressDirty can be seen stale (spuriously
		// flipping the preset to "Custom" or dropping a real user edit).
		SwingUtilities.invokeLater(() -> {
			parent.repaint();

			suppressDirty = true;
			try {
				if (shapesSlider.getValue() == shapesSlider.getMaximum()) {
					shapesSlider.setMaximum(data.getIndex());
					shapesSlider.setValue(data.getIndex());
				} else {
					shapesSlider.setMaximum(data.getIndex());
				}
			} finally {
				suppressDirty = false;
			}

			parent.topPanel.setGeneratedShapes(shapesSlider.getValue(), data.getIndex());
			maxShapeLabel.setText(Integer.toString(data.getIndex()));

			// Refresh the live estimate as generation progresses (debounced).
			scheduleEstimate();
		});
	}

	public void openDialog(GraphicsConfiguration monitor, Point point) {
		this.monitor = monitor;

		// Force the user to reset the palette
		previousBorstModel = null;
		rustPalette.reset();
		paintPlan.reset();
		palettizedPlanResult = null;
		drawnShapes = 0;

		// Update old graphics
		parent.shapeRender.reset();
		parent.repaint();

		// S3: reflect (and for a non-custom preset, re-apply) the persisted
		// preset before generation starts, so the generator picks up its
		// configuration.
		syncPresetFromSettings();
		updateModeVisibility();

		// Before we block. The SA generator only runs in brush mode; the
		// palettized plan is computed by the (debounced) estimate worker.
		if (Settings.SettingsDrawingMode.get() == DrawingMode.Brush) {
			startGeneration(0);
		}
		scheduleEstimate();

		setLocation(point);
		setSize(REGULAR);
		setVisible(true);
		borstGenerator.stop();
	}
}
