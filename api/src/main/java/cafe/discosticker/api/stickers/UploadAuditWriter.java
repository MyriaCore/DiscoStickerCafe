package cafe.discosticker.api.stickers;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes one {@code upload_audit} row per upload attempt by a signed-in user that passed the CSRF
 * check. The original's SHA-256 and size are recorded when its bytes were read; the bytes
 * themselves never are. No IP address.
 */
@Repository
class UploadAuditWriter {

	private final JdbcClient jdbc;

	UploadAuditWriter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Called inside the transaction that inserts the sticker row, so "accepted" always means stored. */
	void accepted(long discordId, byte[] originalSha256, long originalBytes, String publicId) {
		insert(discordId, "accepted", null, originalSha256, originalBytes, publicId);
	}

	/** {@code originalSha256} and {@code originalBytes} are null when the bytes were never read. */
	void rejected(long discordId, UploadRejection rejection, byte[] originalSha256, Long originalBytes) {
		insert(discordId, "rejected", rejection.code(), originalSha256, originalBytes, null);
	}

	void failed(long discordId, byte[] originalSha256, Long originalBytes) {
		insert(discordId, "failed", UploadRejection.ERROR.code(), originalSha256, originalBytes, null);
	}

	private void insert(long discordId, String outcome, String reason, byte[] originalSha256, Long originalBytes,
			String publicId) {
		this.jdbc.sql("""
				insert into upload_audit (discord_id, outcome, reason, original_sha256, original_bytes, sticker_public_id)
				values (?, ?, ?, ?, ?, ?)""")
			.params(discordId, outcome, reason, originalSha256, originalBytes, publicId)
			.update();
	}

}
