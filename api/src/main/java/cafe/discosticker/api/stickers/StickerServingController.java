package cafe.discosticker.api.stickers;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import cafe.discosticker.api.stickers.StickerRecords.StoredSticker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * {@code GET /s/<id>.png}, public (design D13). Anything that isn't an existing sticker, malformed
 * or unknown, gets the same 404 from Boot's error handling; a malformed ID is refused before any
 * database lookup. The PNG streams from storage with exact headers, written directly so nothing
 * adds or changes them.
 */
@Controller
class StickerServingController {

	private static final Pattern FILE = Pattern.compile("(" + PublicIds.CANONICAL.pattern() + ")\\.png");

	/** Stickers never change, but a future purge should take effect within a day. */
	private static final String CACHE_CONTROL = "public, max-age=86400";

	private static final Logger logger = LoggerFactory.getLogger(StickerServingController.class);

	private final StickerRecords records;

	private final StickerStorage storage;

	StickerServingController(StickerRecords records, StickerStorage storage) {
		this.records = records;
		this.storage = storage;
	}

	@GetMapping("/s/{file}")
	void serve(@PathVariable String file, HttpServletRequest request, HttpServletResponse response) throws IOException {
		Matcher matcher = FILE.matcher(file);
		if (!matcher.matches()) {
			throw notFound();
		}
		StoredSticker sticker = this.records.find(matcher.group(1)).orElseThrow(StickerServingController::notFound);
		if ("HEAD".equals(request.getMethod())) {
			writeHeaders(response, sticker);
			return;
		}
		try (InputStream png = this.storage.open(sticker.objectKey())) {
			writeHeaders(response, sticker);
			png.transferTo(response.getOutputStream());
		}
		catch (NoSuchKeyException ex) {
			// A row always has its object (design D9), so this is a bug or tampering, not a user error.
			logger.error("Sticker row has no object in storage: {}", sticker.objectKey());
			throw notFound();
		}
	}

	private static void writeHeaders(HttpServletResponse response, StoredSticker sticker) {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("image/png");
		response.setContentLengthLong(sticker.pngBytes());
		response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

}
