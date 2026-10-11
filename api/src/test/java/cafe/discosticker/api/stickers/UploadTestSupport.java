package cafe.discosticker.api.stickers;

import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import cafe.discosticker.api.login.FakeDiscord;
import cafe.discosticker.api.login.LoginFlow;
import cafe.discosticker.api.login.LoginFlow.FormPart;
import cafe.discosticker.api.storage.S3Properties;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * What upload tests need around the api: the bucket's keys and objects, a user's sticker and
 * upload-audit rows, back-dated audit rows for rate-limit windows, and a signed-in upload client.
 */
final class UploadTestSupport {

	/** One {@code upload_audit} row. */
	record AuditRow(String outcome, String reason, byte[] originalSha256, Long originalBytes, String stickerPublicId) {
	}

	/** One {@code stickers} row. */
	record StickerRow(String publicId, String objectKey, int width, int height, int pngBytes) {
	}

	private final JdbcClient jdbc;

	private final S3Client s3;

	private final String bucket;

	UploadTestSupport(JdbcClient jdbc, S3Client s3, S3Properties s3Properties) {
		this.jdbc = jdbc;
		this.s3 = s3;
		this.bucket = s3Properties.bucket();
	}

	/** Creates the bucket if this context's storage container doesn't have it yet. */
	void ensureBucket() {
		try {
			this.s3.headBucket((b) -> b.bucket(this.bucket));
		}
		catch (NoSuchBucketException ex) {
			this.s3.createBucket((b) -> b.bucket(this.bucket));
		}
	}

	List<String> keys() {
		List<String> keys = new ArrayList<>();
		this.s3.listObjectsV2Paginator((b) -> b.bucket(this.bucket))
			.contents()
			.stream()
			.map(S3Object::key)
			.forEach(keys::add);
		return keys;
	}

	byte[] object(String key) {
		return this.s3.getObjectAsBytes((b) -> b.bucket(this.bucket).key(key)).asByteArray();
	}

	List<AuditRow> audit(long discordId) {
		return this.jdbc.sql("""
				select outcome, reason, original_sha256, original_bytes, sticker_public_id
				from upload_audit where discord_id = ? order by id""")
			.param(discordId)
			.query((rs, i) -> new AuditRow(rs.getString(1), rs.getString(2), rs.getBytes(3),
					(Long) rs.getObject(4), rs.getString(5)))
			.list();
	}

	long auditCount() {
		return this.jdbc.sql("select count(*) from upload_audit").query(Long.class).single();
	}

	List<StickerRow> stickers(long discordId) {
		return this.jdbc.sql("""
				select s.public_id, s.object_key, s.width, s.height, s.png_bytes
				from stickers s join users u on u.id = s.owner_id where u.discord_id = ? order by s.id""")
			.param(discordId)
			.query((rs, i) -> new StickerRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
					rs.getInt(5)))
			.list();
	}

	long stickerCount() {
		return this.jdbc.sql("select count(*) from stickers").query(Long.class).single();
	}

	/**
	 * Back-dated audit rows, as if the user uploaded {@code count} times {@code ago}. Dated by the
	 * database's clock, which the hourly window is measured with, not the test JVM's.
	 */
	void insertAudit(long discordId, String outcome, String reason, int count, Duration ago) {
		for (int i = 0; i < count; i++) {
			boolean accepted = "accepted".equals(outcome);
			this.jdbc.sql("""
					insert into upload_audit (occurred_at, discord_id, outcome, reason, original_sha256, original_bytes, sticker_public_id)
					values (now() - make_interval(secs => ?), ?, ?, ?, ?, 100, ?)""")
				.params((double) ago.toSeconds(), discordId, outcome, reason, sha256(new byte[] { (byte) i }),
						accepted ? "AAAAAAAAAAAAAAAAAAAAA" + "AQgw".charAt(i % 4) : null)
				.update();
		}
	}

	void ban(long discordId) {
		this.jdbc.sql("update users set banned_at = now() where discord_id = ?").param(discordId).update();
	}

	static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** A browser signed in as a fresh, old-enough Discord user, holding the CSRF token. */
	static final class UploadClient {

		final LoginFlow flow;

		final FakeDiscord.User user;

		UploadClient(int port, FakeDiscord discord) {
			this.flow = new LoginFlow(port, discord);
			this.user = FakeDiscord.User.createdAgo(Duration.ofDays(30));
			this.flow.signIn(this.user);
			// Signing in replaces the CSRF token; any API response sets the new cookie.
			this.flow.get("/api/me");
		}

		long discordId() {
			return this.user.id();
		}

		String csrfToken() {
			return this.flow.cookie("XSRF-TOKEN");
		}

		HttpResponse<String> upload(byte[] content) {
			return upload("sticker.png", "image/png", content);
		}

		HttpResponse<String> upload(String filename, String contentType, byte[] content) {
			return this.flow.postMultipart("/api/stickers", List.of(FormPart.file("file", filename, contentType, content)),
					"X-XSRF-TOKEN", csrfToken());
		}

		HttpResponse<String> uploadParts(List<FormPart> parts) {
			return this.flow.postMultipart("/api/stickers", parts, "X-XSRF-TOKEN", csrfToken());
		}

	}

}
