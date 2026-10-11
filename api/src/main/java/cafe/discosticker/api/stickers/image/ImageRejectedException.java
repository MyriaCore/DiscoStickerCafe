package cafe.discosticker.api.stickers.image;

/**
 * Why the pipeline refused an image. {@link Reason#code()} is the upload reason code the api
 * returns and audits. The message is fixed per reason and never holds request-derived text.
 */
public class ImageRejectedException extends RuntimeException {

	public enum Reason {

		UNSUPPORTED_TYPE("unsupported_type"),

		DIMENSIONS_TOO_LARGE("dimensions_too_large"),

		UNSUPPORTED_COLOR("unsupported_color"),

		UNREADABLE("unreadable"),

		BUSY("busy");

		private final String code;

		Reason(String code) {
			this.code = code;
		}

		public String code() {
			return this.code;
		}

	}

	private final Reason reason;

	public ImageRejectedException(Reason reason) {
		this(reason, null);
	}

	public ImageRejectedException(Reason reason, Throwable cause) {
		super("Image rejected: " + reason.code(), cause);
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

}
