package cafe.discosticker.api.stickers.image;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import cafe.discosticker.api.stickers.image.ImageRejectedException.Reason;

/**
 * Turns an uploaded file into a sticker PNG (design D4–D7): type from the leading bytes, header
 * checks before any pixel is decoded, a bounded number of decodes at once, shrinking to fit the
 * sticker box (never enlarging), EXIF orientation, and a PNG with no metadata.
 */
public final class StickerImages {

	/** A width and height in pixels. */
	public record Size(int width, int height) {
	}

	private final int stickerSize;

	private final int maxSide;

	private final long maxPixels;

	private final DecodeLimiter limiter;

	private final Decoder decoder;

	public StickerImages(int stickerSize, int maxSide, long maxPixels, DecodeLimiter limiter) {
		this(stickerSize, maxSide, maxPixels, limiter, new ImageIoDecoder());
	}

	StickerImages(int stickerSize, int maxSide, long maxPixels, DecodeLimiter limiter, Decoder decoder) {
		// Streams created by ImageIO itself would otherwise be cached in temp files, copying
		// upload bytes to disk. The pipeline also builds its own in-memory streams.
		ImageIO.setUseCache(false);
		this.stickerSize = stickerSize;
		this.maxSide = maxSide;
		this.maxPixels = maxPixels;
		this.limiter = limiter;
		this.decoder = decoder;
	}

	/**
	 * @throws ImageRejectedException when the file isn't an acceptable PNG or JPEG, or no decode
	 * permit frees up in time
	 */
	public Sticker process(byte[] original) {
		ImageType type = ImageType.detect(original)
			.orElseThrow(() -> new ImageRejectedException(Reason.UNSUPPORTED_TYPE));
		ImageHeader header = ImageHeader.read(type, original, this.maxSide, this.maxPixels);
		try (DecodeLimiter.Permit _ = this.limiter.acquire()) {
			BufferedImage source = this.decoder.decode(type, original);
			boolean alpha = source.getColorModel().hasAlpha();
			// Fitting a square box commutes with quarter turns, so fit the stored size, then orient.
			Size target = fit(source.getWidth(), source.getHeight(), this.stickerSize);
			BufferedImage working = (target.width() == source.getWidth() && target.height() == source.getHeight())
					? draw(source, target.width(), target.height(), RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
					: shrink(source, target.width(), target.height());
			BufferedImage output = forOutput(orient(working, header.orientation()), alpha);
			return new Sticker(encodePng(output), output.getWidth(), output.getHeight());
		}
	}

	/**
	 * The sticker size for an image: unchanged when neither side exceeds {@code stickerSize};
	 * otherwise the longer side becomes {@code stickerSize} and the shorter is rounded, at least 1.
	 */
	static Size fit(int width, int height, int stickerSize) {
		if (width <= stickerSize && height <= stickerSize) {
			return new Size(width, height);
		}
		if (width >= height) {
			return new Size(stickerSize, Math.max(1, (int) Math.round((double) height * stickerSize / width)));
		}
		return new Size(Math.max(1, (int) Math.round((double) width * stickerSize / height)), stickerSize);
	}

	/**
	 * Shrinks by repeated halving with bilinear interpolation, each step averaging 2 × 2 pixels so
	 * fine detail averages out instead of aliasing, then one bicubic step to the exact size. Works in
	 * premultiplied alpha, so the color hidden in fully transparent pixels never bleeds into edges.
	 */
	static BufferedImage shrink(BufferedImage source, int width, int height) {
		BufferedImage current = source;
		int w = source.getWidth();
		int h = source.getHeight();
		while (w / 2 >= width || h / 2 >= height) {
			w = Math.max(w / 2, width);
			h = Math.max(h / 2, height);
			current = draw(current, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		}
		if (current == source || w != width || h != height) {
			current = draw(current, width, height, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		}
		return current;
	}

	/**
	 * Turns a stored image upright for its EXIF orientation by moving pixels exactly, with no
	 * interpolation. Orientations 5 to 8 swap width and height.
	 */
	static BufferedImage orient(BufferedImage stored, int orientation) {
		if (orientation < 2 || orientation > 8) {
			return stored;
		}
		int w = stored.getWidth();
		int h = stored.getHeight();
		boolean swap = orientation >= 5;
		int width = swap ? h : w;
		int height = swap ? w : h;
		ColorModel model = stored.getColorModel();
		WritableRaster target = model.createCompatibleWritableRaster(width, height);
		Raster source = stored.getRaster();
		Object pixel = null;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int sx = switch (orientation) {
					case 2, 3 -> w - 1 - x; // mirror; rotate 180°
					case 4 -> x; // flip vertically
					case 5, 6 -> y; // transpose; rotate 90° clockwise
					default -> w - 1 - y; // 7 transverse; 8 rotate 90° counter-clockwise
				};
				int sy = switch (orientation) {
					case 2 -> y;
					case 3, 4 -> h - 1 - y;
					case 5, 8 -> x;
					default -> h - 1 - x; // 6, 7
				};
				pixel = source.getDataElements(sx, sy, pixel);
				target.setDataElements(x, y, pixel);
			}
		}
		return new BufferedImage(model, target, model.isAlphaPremultiplied(), null);
	}

	/** Non-premultiplied ARGB when the upload had transparency, RGB when it didn't. */
	private static BufferedImage forOutput(BufferedImage image, boolean alpha) {
		BufferedImage output = new BufferedImage(image.getWidth(), image.getHeight(),
				alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D g = output.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return output;
	}

	/** Draws {@code source} scaled to width × height into a new premultiplied ARGB image. */
	private static BufferedImage draw(BufferedImage source, int width, int height, Object interpolation) {
		BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g = target.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
		g.drawImage(source, 0, 0, width, height, null);
		g.dispose();
		return target;
	}

	/** A PNG with no source metadata: the JDK writer emits only IHDR, IDAT and IEND for these types. */
	private static byte[] encodePng(BufferedImage image) {
		ImageWriter writer = ImageCodecs.pngWriter();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), null);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

}
