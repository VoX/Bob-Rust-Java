package com.bobrust.robot;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

import com.bobrust.generator.tiler.PalettizedPaintPlan;
import com.bobrust.generator.tiler.PalettizedPaintPlan.Op;
import com.bobrust.generator.tiler.PalettizedPaintPlan.SetColor;
import com.bobrust.generator.tiler.PalettizedPaintPlan.SetSize;
import com.bobrust.generator.tiler.PalettizedPaintPlan.Stamp;
import com.bobrust.generator.tiler.SquareBrushGeometry;
import com.bobrust.robot.error.PaintingInterrupted;
import com.bobrust.robot.error.PaintingInterrupted.InterruptType;
import com.bobrust.robot.hsv.ColorEntryController;
import com.bobrust.robot.hsv.HsvPickerModel;
import com.bobrust.robot.hsv.PickerSensor;
import com.bobrust.robot.hsv.ProbePlanner;
import com.bobrust.robot.io.PacedInput;
import com.bobrust.robot.io.RobotIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The palettized-mode executor (PLAN-PALETTIZED-MODE.md §6): a sibling of
 * {@link BobRustPainter} built on the same extracted primitives. Runs the
 * flattened {@link PalettizedPaintPlan} — focus, clear, square brush,
 * opacity 1 (field-verified), the automatic probe pass, then per color:
 * closed-loop HSV entry (adopt or abort), per side group a verified SIZE
 * field entry, paced stamp clicks with plan-aware sparse verification,
 * periodic autosaves.
 *
 * <p>Everything the executor touches comes through {@link RobotIO}, so the
 * whole run loop is exercised offline by the FakeGame end-to-end test.
 */
public class PalettizedPainter {
	private static final Logger LOGGER = LogManager.getLogger(PalettizedPainter.class);

	/** Setup clicks run at this fixed delay, same as the legacy painter. */
	private static final double SETUP_CLICK_DELAY_MS = 50;
	/** Fraction of the swatch rect inset per side before the median read. */
	private static final double SWATCH_INSET = 0.15;
	/** Verified size-entry tolerance in SIZE units / opacity units (§3.1). */
	private static final double SIZE_TOLERANCE = 0.3;
	private static final double OPACITY_TOLERANCE = 0.02;

	/**
	 * Everything a palettized run needs, resolved by the caller (the UI reads
	 * Settings; tests build it directly). Coordinates are monitor-local, the
	 * display offset is applied here — same convention as the legacy painter.
	 */
	public record Config(
		ButtonConfiguration buttons,
		Rectangle canvasArea,
		int displayX, int displayY,
		int signWidth, int signHeight,
		double pitch,
		SquareBrushGeometry brush,
		int clickInterval, int autosaveInterval, int verifyInterval,
		boolean clearFirst,
		boolean pasteFields, FieldInput.DecimalKey decimalKey) {
	}

	private final RobotIO io;
	private final Config config;
	private final PacedInput paced;
	private final FieldInput fieldInput;
	private final double autoDelay;

	// Screen-space picker rects (display offset applied)
	private final Rectangle svRect;
	private final Rectangle hueRect;
	private final Rectangle swatchReadRect;

	private int drawnStamps;
	private int[] adoptedPalette;
	private HsvPickerModel fittedModel;

	public PalettizedPainter(RobotIO io, Config config) {
		this.io = io;
		this.config = config;
		this.paced = new PacedInput(io, () -> drawnStamps);
		this.fieldInput = new FieldInput(paced, () -> drawnStamps, config.pasteFields(), config.decimalKey());
		this.autoDelay = 1000.0 / (Math.max(1, config.clickInterval()) * 3.0);

		ButtonConfiguration buttons = config.buttons();
		this.svRect = screenRect(buttons.hsvSquare_topLeft, buttons.hsvSquare_botRight);
		this.hueRect = screenRect(buttons.hueBar_topLeft, buttons.hueBar_botRight);
		this.swatchReadRect = inset(screenRect(buttons.swatch_topLeft, buttons.swatch_botRight), SWATCH_INSET);
	}

	/** The palette actually read back and adopted per color index (0 where never entered). */
	public int[] getAdoptedPalette() {
		return adoptedPalette;
	}

	/** The probe-fitted picker model of the last run (persist as the next prior). */
	public HsvPickerModel getFittedModel() {
		return fittedModel;
	}

	/**
	 * Paints the plan from its resume cursor. Always exits by throwing
	 * {@link PaintingInterrupted} — {@code PaintingFinished} on success, the
	 * failure type otherwise — carrying the number of stamps painted this
	 * run (the caller advances the plan cursor with it).
	 */
	public void startDrawing(PalettizedPaintPlan plan, BiConsumer<Integer, Integer> renderCallback)
			throws PaintingInterrupted {
		this.drawnStamps = 0;
		this.adoptedPalette = new int[plan.getPaletteRgb().length];

		int resumeFrom = plan.getPaintedStamps();
		List<Op> ops = plan.opsFrom(resumeFrom);
		int stampsToPaint = plan.getTotalStamps() - resumeFrom;
		if (ops.isEmpty()) {
			throw new PaintingInterrupted(0, InterruptType.PaintingFinished);
		}

		ButtonConfiguration buttons = config.buttons();

		// Make sure that we have selected the game
		paced.clickPoint(transform(point(buttons.focus)), 4, SETUP_CLICK_DELAY_MS);

		// A deterministic substrate — but never when resuming into paint, and only
		// when the clear-canvas button is actually calibrated (an uncalibrated
		// coordinate is DEFAULT (0,0) → a stray click at the monitor origin).
		if (config.clearFirst() && resumeFrom == 0 && buttons.clearCanvas.valid()) {
			paced.clickPoint(transform(point(buttons.clearCanvas)), SETUP_CLICK_DELAY_MS);
		}

		// Square brush (same ×4 setup posture as the legacy tool setup)
		paced.clickPoint(transform(point(buttons.brush_square)), 4, SETUP_CLICK_DELAY_MS);

		// Opacity exactly 1, verified against the opacity slider fill
		fieldInput.enter(transform(point(buttons.opacityField)), "1",
			opacitySliderSpec(), autoDelay);

		// The probe pass: fits the picker mapping and pre-flights the whole
		// color subsystem before a single canvas click (§2.3)
		RobotSensor sensor = new RobotSensor();
		ProbePlanner.ProbeResult probe = ProbePlanner.probe(sensor, svRect, hueRect,
			ProbePlanner.DEFAULT_PROBES_PER_AXIS);
		if (!probe.ok()) {
			LOGGER.error("Palettized probe failed: {}", probe.failure());
			throw new PaintingInterrupted(drawnStamps, InterruptType.ColorEntryFailed);
		}
		this.fittedModel = probe.model();
		LOGGER.info("Palettized probe fitted (residual {} px): {}",
			"%.2f".formatted(probe.maxResidualPx()), fittedModel.serialize());

		ColorEntryController colorEntry = new ColorEntryController(sensor, fittedModel, svRect, hueRect);

		int currentSide = 0;
		int[] palette = plan.getPaletteRgb();
		for (Op op : ops) {
			if (op instanceof SetColor setColor) {
				enterColor(colorEntry, setColor.colorIndex(), palette[setColor.colorIndex()]);
			} else if (op instanceof SetSize setSize) {
				currentSide = setSize.sideCells();
				double size = config.brush().sizeFor(currentSide, config.pitch());
				fieldInput.enter(transform(point(buttons.sizeField)),
					SquareBrushGeometry.formatSize(size), sizeSliderSpec(), autoDelay);
			} else if (op instanceof Stamp stamp) {
				Point screen = stampScreenPoint(stamp, currentSide);
				// Verify against the center of the cell the changesCenter
				// prediction is about — the raw click point can sit on a cell
				// boundary (even sides) and truncate into a neighbor cell
				Point verifyPoint = cellCenterScreenPoint(
					stamp.cellX() + currentSide / 2, stamp.cellY() + currentSide / 2);
				boolean verify = stamp.changesCenter() && (drawnStamps % Math.max(1, config.verifyInterval())) == 0;
				clickStamp(screen, verifyPoint, autoDelay, verify);

				drawnStamps++;
				if (drawnStamps < stampsToPaint && (drawnStamps % Math.max(1, config.autosaveInterval())) == 0) {
					paced.clickPoint(transform(point(buttons.saveImage)), autoDelay);
				}
				renderCallback.accept(drawnStamps, stampsToPaint);
			}
		}

		// Make sure that we save the painting
		paced.clickPoint(transform(point(buttons.saveImage)), 4, autoDelay);

		throw new PaintingInterrupted(drawnStamps, InterruptType.PaintingFinished);
	}

	private void enterColor(ColorEntryController colorEntry, int colorIndex, int targetRgb)
			throws PaintingInterrupted {
		ColorEntryController.Result result = colorEntry.enter(targetRgb);
		if (result.status() != ColorEntryController.Status.ADOPTED) {
			LOGGER.error("Color entry failed for #{} (best dE00 {}) — aborting before its canvas pass",
				"%06x".formatted(targetRgb & 0xffffff), "%.2f".formatted(result.deltaE()));
			throw new PaintingInterrupted(drawnStamps, InterruptType.ColorEntryFailed);
		}
		if (result.loose()) {
			LOGGER.warn("Color #{} adopted loosely at dE00 {} after {} reads",
				"%06x".formatted(targetRgb & 0xffffff), "%.2f".formatted(result.deltaE()), result.reads());
		}
		adoptedPalette[colorIndex] = result.adoptedRgb();
	}

	/**
	 * Stamp cell → screen point: texel center {@code (cell + side/2)·pitch},
	 * then the exact canvas math of the legacy painter (sign fraction →
	 * canvas rect → display offset).
	 */
	Point stampScreenPoint(Stamp stamp, int side) {
		double texelX = (stamp.cellX() + side / 2.0) * config.pitch();
		double texelY = (stamp.cellY() + side / 2.0) * config.pitch();

		double dx = texelX / config.signWidth();
		double dy = texelY / config.signHeight();

		Rectangle canvas = config.canvasArea();
		double tx = dx * canvas.width + canvas.x;
		double ty = dy * canvas.height + canvas.y;

		return new Point((int) tx + config.displayX(), (int) ty + config.displayY());
	}

	/** The screen pixel at the center of a virtual cell (verification reads). */
	Point cellCenterScreenPoint(int cellX, int cellY) {
		double texelX = (cellX + 0.5) * config.pitch();
		double texelY = (cellY + 0.5) * config.pitch();

		Rectangle canvas = config.canvasArea();
		double tx = (texelX / config.signWidth()) * canvas.width + canvas.x;
		double ty = (texelY / config.signHeight()) * canvas.height + canvas.y;

		return new Point((int) tx + config.displayX(), (int) ty + config.displayY());
	}

	/**
	 * One paced canvas stamp; when {@code verify} is set the pixel at
	 * {@code verifyPoint} is compared before/after with the legacy retry
	 * envelope — only called for stamps the plan predicts actually change
	 * their center cell.
	 */
	private void clickStamp(Point point, Point verifyPoint, double delay, boolean verify) throws PaintingInterrupted {
		io.mouseMove(point.x, point.y);
		paced.addTimeDelay(io.currentTimeMs() + delay);

		if (!verify) {
			double time = io.currentTimeMs();
			io.mousePress();
			paced.addTimeDelay(time + delay);
			io.mouseRelease();
			paced.addTimeDelay(time + delay * 2.0);
			paced.checkMouseDisplacement(point);
			return;
		}

		int before = io.getPixelRgb(verifyPoint.x, verifyPoint.y);
		int maxAttempts = 3;
		do {
			double retryTime = io.currentTimeMs();
			io.mousePress();
			paced.addTimeDelay(retryTime + delay);
			io.mouseRelease();
			paced.addTimeDelay(retryTime + delay * 2.0);

			if (before != io.getPixelRgb(verifyPoint.x, verifyPoint.y)) {
				break;
			}
			paced.addTimeDelay(retryTime + delay * 3.0);
		} while (maxAttempts-- > 0);

		if (maxAttempts < 0) {
			LOGGER.warn("Potentially failed to paint stamp at {},{} — continuing", point.x, point.y);
		}
		paced.checkMouseDisplacement(point);
	}

	// ------------------------------------------------------------- plumbing

	/** The paced sensor the probe + color entry drive (clicks and swatch reads). */
	private class RobotSensor implements PickerSensor {
		@Override
		public void clickHue(int yPx) throws PaintingInterrupted {
			paced.clickPoint(new Point(hueRect.x + hueRect.width / 2, yPx), autoDelay);
		}

		@Override
		public void clickSv(int xPx, int yPx) throws PaintingInterrupted {
			paced.clickPoint(new Point(xPx, yPx), autoDelay);
		}

		@Override
		public int readSwatch() throws PaintingInterrupted {
			BufferedImage image = paced.captureRegion(swatchReadRect);
			if (image == null) {
				LOGGER.error("Could not capture the swatch rect {} — aborting color entry", swatchReadRect);
				throw new PaintingInterrupted(drawnStamps, InterruptType.ColorEntryFailed);
			}
			return medianRgb(image);
		}
	}

	/** Per-channel median of a capture — exact on the flat swatch fill (§2.5). */
	static int medianRgb(BufferedImage image) {
		int n = image.getWidth() * image.getHeight();
		int[] r = new int[n], g = new int[n], b = new int[n];
		int i = 0;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int rgb = image.getRGB(x, y);
				r[i] = (rgb >>> 16) & 0xff;
				g[i] = (rgb >>> 8) & 0xff;
				b[i] = rgb & 0xff;
				i++;
			}
		}
		Arrays.sort(r);
		Arrays.sort(g);
		Arrays.sort(b);
		return 0xff000000 | (r[n / 2] << 16) | (g[n / 2] << 8) | b[n / 2];
	}

	private FieldInput.SliderSpec sizeSliderSpec() {
		return new FieldInput.SliderSpec(
			transform(point(config.buttons().size_1)),
			transform(point(config.buttons().size_32)),
			1, 32, SIZE_TOLERANCE);
	}

	private FieldInput.SliderSpec opacitySliderSpec() {
		return new FieldInput.SliderSpec(
			transform(point(config.buttons().opacity_0)),
			transform(point(config.buttons().opacity_1)),
			0, 1, OPACITY_TOLERANCE);
	}

	private Point transform(Point point) {
		return new Point(config.displayX() + point.x, config.displayY() + point.y);
	}

	private static Point point(ButtonConfiguration.Coordinate coordinate) {
		return new Point(coordinate.x(), coordinate.y());
	}

	private Rectangle screenRect(ButtonConfiguration.Coordinate topLeft, ButtonConfiguration.Coordinate botRight) {
		return new Rectangle(
			config.displayX() + topLeft.x(),
			config.displayY() + topLeft.y(),
			botRight.x() - topLeft.x(),
			botRight.y() - topLeft.y());
	}

	private static Rectangle inset(Rectangle rect, double fraction) {
		int dx = (int) Math.round(rect.width * fraction);
		int dy = (int) Math.round(rect.height * fraction);
		return new Rectangle(rect.x + dx, rect.y + dy,
			Math.max(1, rect.width - 2 * dx), Math.max(1, rect.height - 2 * dy));
	}
}
