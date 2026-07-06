package com.bobrust.generator;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.bobrust.generator.sorter.Blob;
import com.bobrust.util.data.AppConstants;

/**
 * Shared test fixture: runs the real generator over an image and converts the
 * committed shapes into paint-order {@link Blob}s exactly like
 * {@code BorstGenerator.BorstData#update} does, so S1 pruning / paint-plan
 * tests exercise the same blob stream the app paints from.
 */
public final class TestBlobs {
	public static final int BACKGROUND = 0xFFFFFFFF;
	public static final int ALPHA = BorstUtils.ALPHAS[2];

	private TestBlobs() {
	}

	/** Generated blobs + their target, from a deterministic generator run. */
	public record Generated(List<Blob> blobs, List<Long> contributions, BorstImage target) {
	}

	public static Generated generate(BufferedImage image, int shapes) {
		return generate(image, shapes, GeneratorConfig.DEFAULT);
	}

	public static Generated generate(BufferedImage image, int shapes, GeneratorConfig config) {
		BorstImage target = new BorstImage(ensureArgb(image));
		Model model = new Model(target, BACKGROUND, ALPHA, config);
		for (int i = 0; i < shapes; i++) {
			model.processStep();
		}

		boolean perShapeAlpha = config.usePerShapeAlpha();
		List<Blob> blobs = new ArrayList<>();
		for (int i = 0; i < model.shapes.size(); i++) {
			Circle shape = model.shapes.get(i);
			blobs.add(Blob.of(
				shape.x,
				shape.y,
				shape.r,
				model.colors.get(i).rgb,
				perShapeAlpha ? BorstUtils.ALPHAS[shape.alphaIndex] : model.alpha,
				AppConstants.CIRCLE_SHAPE
			));
		}
		return new Generated(blobs, new ArrayList<>(model.getShapeContributions()), target);
	}

	/** Uniform RGBA squared-error total — the metric BlobPruner maintains. */
	public static long uniformError(BorstImage target, BorstImage rendered) {
		long total = 0;
		for (int i = 0; i < target.pixels.length; i++) {
			int aa = target.pixels[i];
			int bb = rendered.pixels[i];
			int da = ((aa >>> 24) & 0xff) - ((bb >>> 24) & 0xff);
			int dr = ((aa >>> 16) & 0xff) - ((bb >>> 16) & 0xff);
			int dg = ((aa >>>  8) & 0xff) - ((bb >>>  8) & 0xff);
			int db = ((aa       ) & 0xff) - ((bb       ) & 0xff);
			total += dr * dr + dg * dg + db * db + da * da;
		}
		return total;
	}

	public static BufferedImage ensureArgb(BufferedImage img) {
		if (img.getType() == BufferedImage.TYPE_INT_ARGB) {
			return img;
		}
		BufferedImage argb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = argb.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return argb;
	}
}
