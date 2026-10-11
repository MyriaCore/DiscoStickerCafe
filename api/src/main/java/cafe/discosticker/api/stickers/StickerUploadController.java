package cafe.discosticker.api.stickers;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code POST /api/stickers}: one file, in a multipart part named {@code file}. Signed in only, with
 * the {@code X-XSRF-TOKEN} header (both enforced by Spring Security before this runs). Multipart
 * bodies are parsed lazily, here, so a body over the size limit fails inside this controller and is
 * audited as {@code too_large}. Responses carry only fixed values: the sticker's relative path and
 * size, or {@code {"error": code}}.
 */
@RestController
class StickerUploadController {

	record Created(String path, int width, int height) {
	}

	private static final String FILE_PART = "file";

	private final StickerUploadService uploads;

	StickerUploadController(StickerUploadService uploads) {
		this.uploads = uploads;
	}

	@PostMapping("/api/stickers")
	ResponseEntity<Created> upload(HttpServletRequest request, Principal principal) throws IOException {
		long started = System.nanoTime();
		long discordId = discordId(principal);
		MultipartFile file = (request instanceof MultipartHttpServletRequest multipart) ? onlyFile(multipart) : null;
		if (file == null) {
			throw this.uploads.rejectUnread(discordId, UploadRejection.INVALID_REQUEST, started);
		}
		StickerUploadService.Accepted accepted = this.uploads.upload(discordId, file.getBytes(), started);
		// Relative: the page adds its own origin, so no link is built from the forwarded Host.
		String path = "/s/" + accepted.publicId() + ".png";
		return ResponseEntity.created(URI.create(path)).body(new Created(path, accepted.width(), accepted.height()));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<Map<String, String>> tooLarge(Principal principal) {
		return rejected(this.uploads.rejectUnread(discordId(principal), UploadRejection.TOO_LARGE, System.nanoTime()));
	}

	@ExceptionHandler(MultipartException.class)
	ResponseEntity<Map<String, String>> malformed(Principal principal) {
		return rejected(
				this.uploads.rejectUnread(discordId(principal), UploadRejection.INVALID_REQUEST, System.nanoTime()));
	}

	@ExceptionHandler(UploadRejectedException.class)
	ResponseEntity<Map<String, String>> rejected(UploadRejectedException ex) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.rejection().status())
			.contentType(MediaType.APPLICATION_JSON);
		if (ex.retryAfterSeconds() != null) {
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
		}
		return response.body(Map.of("error", ex.rejection().code()));
	}

	/** The single file part, which must be named {@code file}; null when there are none, several, or it's misnamed. */
	private static MultipartFile onlyFile(MultipartHttpServletRequest request) {
		MultiValueMap<String, MultipartFile> files = request.getMultiFileMap();
		long count = files.values().stream().mapToLong(List::size).sum();
		List<MultipartFile> named = files.get(FILE_PART);
		return (count == 1 && named != null && named.size() == 1) ? named.getFirst() : null;
	}

	/** The signed-in user's Discord ID: the principal's name is Discord's {@code id} attribute. */
	private static long discordId(Principal principal) {
		return Long.parseLong(principal.getName());
	}

}
