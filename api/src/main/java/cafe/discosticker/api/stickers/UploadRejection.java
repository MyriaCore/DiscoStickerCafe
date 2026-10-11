package cafe.discosticker.api.stickers;

import org.springframework.http.HttpStatus;

import cafe.discosticker.api.stickers.image.ImageRejectedException;

/**
 * Why an upload wasn't accepted. {@link #code()} is the fixed value written to
 * {@code upload_audit.reason} and returned as {@code {"error": code}}; nothing request-derived is
 * ever added to it.
 */
enum UploadRejection {

	INVALID_REQUEST("invalid_request", HttpStatus.BAD_REQUEST),

	TOO_LARGE("too_large", HttpStatus.CONTENT_TOO_LARGE),

	BANNED("banned", HttpStatus.FORBIDDEN),

	RATE_LIMITED("rate_limited", HttpStatus.TOO_MANY_REQUESTS),

	UNSUPPORTED_TYPE("unsupported_type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

	DIMENSIONS_TOO_LARGE("dimensions_too_large", HttpStatus.UNPROCESSABLE_CONTENT),

	UNSUPPORTED_COLOR("unsupported_color", HttpStatus.UNPROCESSABLE_CONTENT),

	UNREADABLE("unreadable", HttpStatus.UNPROCESSABLE_CONTENT),

	BUSY("busy", HttpStatus.SERVICE_UNAVAILABLE),

	/** Not a rejection: storing or recording failed. Audited with outcome {@code failed}. */
	ERROR("error", HttpStatus.INTERNAL_SERVER_ERROR);

	private final String code;

	private final HttpStatus status;

	UploadRejection(String code, HttpStatus status) {
		this.code = code;
		this.status = status;
	}

	String code() {
		return this.code;
	}

	HttpStatus status() {
		return this.status;
	}

	static UploadRejection of(ImageRejectedException.Reason reason) {
		return switch (reason) {
			case UNSUPPORTED_TYPE -> UNSUPPORTED_TYPE;
			case DIMENSIONS_TOO_LARGE -> DIMENSIONS_TOO_LARGE;
			case UNSUPPORTED_COLOR -> UNSUPPORTED_COLOR;
			case UNREADABLE -> UNREADABLE;
			case BUSY -> BUSY;
		};
	}

}
