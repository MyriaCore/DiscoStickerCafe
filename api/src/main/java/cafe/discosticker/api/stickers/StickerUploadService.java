package cafe.discosticker.api.stickers;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cafe.discosticker.api.stickers.UploadStandings.Standing;
import cafe.discosticker.api.stickers.image.ImageRejectedException;
import cafe.discosticker.api.stickers.image.Sticker;
import cafe.discosticker.api.stickers.image.StickerImages;

/**
 * One upload, in the order of design D3: hash the original; check the ban and the hourly limit
 * (no lock); type, header and decode checks with the image pipeline; then the write transaction of
 * design D9: lock the account row, check again, put the PNG, insert the sticker and its accepted
 * audit row, commit. Every attempt writes exactly one audit row and one INFO line holding the
 * outcome, the fixed reason code and the time taken, never the file name, Discord ID or IP.
 */
@Service
class StickerUploadService {

	record Accepted(String publicId, int width, int height) {
	}

	private record Outcome(UploadRejection rejection, Long retryAfterSeconds) {
	}

	private static final Logger logger = LoggerFactory.getLogger(StickerUploadService.class);

	private final StickerImages images;

	private final UploadStandings standings;

	private final StickerRecords records;

	private final StickerStorage storage;

	private final UploadAuditWriter audit;

	private final TransactionTemplate transaction;

	private final int uploadsPerHour;

	StickerUploadService(StickerImages images, UploadStandings standings, StickerRecords records,
			StickerStorage storage, UploadAuditWriter audit, TransactionTemplate transaction, UploadProperties upload) {
		this.images = images;
		this.standings = standings;
		this.records = records;
		this.storage = storage;
		this.audit = audit;
		this.transaction = transaction;
		this.uploadsPerHour = upload.uploadsPerHour();
	}

	/**
	 * @param started {@link System#nanoTime()} when the request reached the controller
	 * @throws UploadRejectedException after auditing and logging the rejection or failure
	 */
	Accepted upload(long discordId, byte[] original, long started) {
		byte[] sha256 = sha256(original);
		long size = original.length;
		Standing standing = this.standings.current(discordId);
		if (standing == null) {
			throw fail(discordId, sha256, size, started, new IllegalStateException("Signed-in user has no account row"));
		}
		if (standing.banned()) {
			throw reject(discordId, UploadRejection.BANNED, sha256, size, started, null);
		}
		if (standing.acceptedLastHour() >= this.uploadsPerHour) {
			throw reject(discordId, UploadRejection.RATE_LIMITED, sha256, size, started, standing.retryAfterSeconds());
		}
		Sticker sticker;
		try {
			sticker = this.images.process(original);
		}
		catch (ImageRejectedException ex) {
			throw reject(discordId, UploadRejection.of(ex.reason()), sha256, size, started, null);
		}
		return store(discordId, sticker, sha256, size, started);
	}

	/** A rejection decided before the file's bytes were read ({@code too_large}, {@code invalid_request}). */
	UploadRejectedException rejectUnread(long discordId, UploadRejection rejection, long started) {
		return reject(discordId, rejection, null, null, started, null);
	}

	private Accepted store(long discordId, Sticker sticker, byte[] sha256, long size, long started) {
		String publicId = PublicIds.next();
		String key = publicId + ".png";
		AtomicBoolean written = new AtomicBoolean();
		Outcome outcome;
		try {
			outcome = this.transaction.execute((status) -> {
				Standing locked = this.standings.locked(discordId);
				if (locked == null) {
					throw new IllegalStateException("Signed-in user has no account row");
				}
				if (locked.banned()) {
					this.audit.rejected(discordId, UploadRejection.BANNED, sha256, size);
					return new Outcome(UploadRejection.BANNED, null);
				}
				if (locked.acceptedLastHour() >= this.uploadsPerHour) {
					this.audit.rejected(discordId, UploadRejection.RATE_LIMITED, sha256, size);
					return new Outcome(UploadRejection.RATE_LIMITED, locked.retryAfterSeconds());
				}
				// The object first: a sticker row is only ever committed once its PNG is stored.
				this.storage.put(key, sticker.png());
				written.set(true);
				this.records.insert(publicId, locked.userId(), key, sticker.width(), sticker.height(), sticker.png().length);
				this.audit.accepted(discordId, sha256, size, publicId);
				return new Outcome(null, null);
			});
		}
		catch (RuntimeException ex) {
			if (written.get()) {
				deleteOrphan(key);
			}
			throw fail(discordId, sha256, size, started, ex);
		}
		if (outcome != null && outcome.rejection() != null) {
			log(outcome.rejection(), started);
			throw new UploadRejectedException(outcome.rejection(), outcome.retryAfterSeconds());
		}
		logger.info("Upload accepted in {} ms", millisSince(started));
		return new Accepted(publicId, sticker.width(), sticker.height());
	}

	private UploadRejectedException reject(long discordId, UploadRejection rejection, byte[] sha256, Long size,
			long started, Long retryAfterSeconds) {
		try {
			this.audit.rejected(discordId, rejection, sha256, size);
		}
		catch (RuntimeException ex) {
			logger.error("Could not audit a rejected upload ({})", rejection.code(), ex);
		}
		log(rejection, started);
		return new UploadRejectedException(rejection, retryAfterSeconds);
	}

	private UploadRejectedException fail(long discordId, byte[] sha256, Long size, long started, Exception cause) {
		logger.error("Upload failed: the sticker could not be stored or recorded", cause);
		try {
			this.audit.failed(discordId, sha256, size);
		}
		catch (RuntimeException ex) {
			logger.error("Could not audit a failed upload", ex);
		}
		logger.info("Upload failed {} in {} ms", UploadRejection.ERROR.code(), millisSince(started));
		return new UploadRejectedException(UploadRejection.ERROR);
	}

	/** An object whose row never committed. Unreachable either way; the runbook's orphan check finds any left. */
	private void deleteOrphan(String key) {
		try {
			this.storage.delete(key);
		}
		catch (RuntimeException ex) {
			logger.error("Could not delete an uploaded object after a failed upload; the orphan check will find it", ex);
		}
	}

	private static void log(UploadRejection rejection, long started) {
		logger.info("Upload rejected {} in {} ms", rejection.code(), millisSince(started));
	}

	private static long millisSince(long started) {
		return (System.nanoTime() - started) / 1_000_000;
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
