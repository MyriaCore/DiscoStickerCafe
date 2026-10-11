package cafe.discosticker.api.stickers;

/**
 * An upload that ends without a sticker. It has already been audited and logged when this is thrown.
 */
class UploadRejectedException extends RuntimeException {

	private final UploadRejection rejection;

	private final Long retryAfterSeconds;

	UploadRejectedException(UploadRejection rejection) {
		this(rejection, null);
	}

	UploadRejectedException(UploadRejection rejection, Long retryAfterSeconds) {
		super("Upload " + rejection.code(), null, false, false);
		this.rejection = rejection;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	UploadRejection rejection() {
		return this.rejection;
	}

	/** Seconds until the hourly limit allows another upload; set only for {@code rate_limited}. */
	Long retryAfterSeconds() {
		return this.retryAfterSeconds;
	}

}
