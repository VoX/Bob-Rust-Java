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
import com.bobrust.util.metrics.ImageMetrics;
import com.bobrust.gui.comp.JIntegerField;
import com.bobrust.gui.comp.JResizeComponent;
import com.bobrust.gui.comp.JStyledToggleButton;
import com.bobrust.robot.BobRustPainter;
import com.bobrust.robot.BobRustPalette;
import com.bobrust.robot.error.PaintingInterrupted;
import com.bobrust.settings.Settings;
import com.bobrust.settings.data.PaintPreset;
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
	private static final Dimension REGULAR = new Dimension(320, 270);
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

	// S3 preset row + S2 live estimate readout
	private final Map<PaintPreset, JStyledToggleButton> presetButtons = new EnumMap<>(PaintPreset.class);
	private final ButtonGroup presetGroup = new ButtonGroup();
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

		// S3: the preset ladder — four named points on the measured
		// speed/quality curve; the shape slider below stays the fine control.
		JPanel qualityLabelPanel = new JPanel();
		qualityLabelPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		qualityLabelPanel.setLayout(new BoxLayout(qualityLabelPanel, BoxLayout.X_AXIS));
		qualityLabelPanel.add(new JLabel("Quality"));
		presetCustomLabel = new JLabel(" (Custom)");
		presetCustomLabel.setVisible(false);
		qualityLabelPanel.add(presetCustomLabel);
		rootPanel.add(qualityLabelPanel);

		JPanel presetPanel = new JPanel();
		presetPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		presetPanel.setLayout(new BoxLayout(presetPanel, BoxLayout.X_AXIS));
		for (PaintPreset preset : PaintPreset.values()) {
			if (preset == PaintPreset.CUSTOM) {
				continue;
			}
			JStyledToggleButton button = new JStyledToggleButton(preset.getDisplayName());
			button.setToolTipText(switch (preset) {
				case BLAZING -> "Draft: hard 70% blob budget, fastest clicks, sparse verification";
				case FAST -> "Adaptive: drops blobs costing <1% quality, faster clicks";
				case BALANCED -> "Today's quality — only verified-free blobs are skipped";
				default -> "No pruning, extra search effort, conservative clicks";
			});
			button.addActionListener(event -> applyPreset(preset));
			presetGroup.add(button);
			presetButtons.put(preset, button);
			presetPanel.add(button);
		}
		rootPanel.add(presetPanel);

		rootPanel.add(new JLabel("Shape Count"));

		JPanel panel = new JPanel();
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
		rootPanel.add(panel);

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
			if (findColorPalette()) {
				previousBorstModel = borstGenerator.stop();

				Point previous_location = getLocation();
				startDrawingAction(previous_location);
			} else {
				showPaletteWarning();
			}
		});
		rootPanel.add(colorPaletteButton);
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

		// Refresh the live estimate as generation progresses (debounced;
		// the timer is a Swing timer so poke it from the EDT).
		SwingUtilities.invokeLater(this::scheduleEstimate);
	}

	public void openDialog(GraphicsConfiguration monitor, Point point) {
		this.monitor = monitor;

		// Force the user to reset the palette
		previousBorstModel = null;
		rustPalette.reset();
		paintPlan.reset();
		drawnShapes = 0;

		// Update old graphics
		parent.shapeRender.reset();
		parent.repaint();

		// S3: reflect (and for a non-custom preset, re-apply) the persisted
		// preset before generation starts, so the generator picks up its
		// configuration.
		syncPresetFromSettings();

		// Before we block
		startGeneration(0);
		scheduleEstimate();

		setLocation(point);
		setSize(REGULAR);
		setVisible(true);
		borstGenerator.stop();
	}
}
