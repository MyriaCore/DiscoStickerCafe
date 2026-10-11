package cafe.discosticker.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import cafe.discosticker.api.TestcontainersConfiguration;

/**
 * The account and login-audit tables hold only what the login change needs: no email, name,
 * avatar, token or IP column, and no session tables (sessions live in memory). The sticker and
 * upload-audit tables hold only what upload needs, with no IP column, and their checks keep
 * public IDs canonical and audit rows consistent.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaIT {

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Flyway flyway;

	@Test
	void usersHasOnlyTheMinimalColumns() {
		assertThat(columns("users")).containsExactlyInAnyOrder("id", "discord_id", "created_at", "last_login_at",
				"banned_at");
	}

	@Test
	void loginAuditHasOnlyTheMinimalColumns() {
		assertThat(columns("login_audit")).containsExactlyInAnyOrder("id", "occurred_at", "discord_id", "outcome",
				"reason");
	}

	@Test
	void discordIdIsUnique() {
		long discordId = randomDiscordId();
		jdbc.sql("insert into users (discord_id) values (?)").param(discordId).update();

		assertThatThrownBy(() -> jdbc.sql("insert into users (discord_id) values (?)").param(discordId).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void auditAcceptsTheThreeIdentifiedOutcomes() {
		long discordId = randomDiscordId();
		insertAudit(discordId, "accepted", null);
		insertAudit(discordId, "rejected", "banned");
		insertAudit(discordId, "rejected", "too_new");

		assertThat(jdbc.sql("select count(*) from login_audit where discord_id = ?")
			.param(discordId)
			.query(Long.class)
			.single()).isEqualTo(3);
	}

	@Test
	void auditRejectsUnknownOutcome() {
		assertThatThrownBy(() -> insertAudit(randomDiscordId(), "pending", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void auditRejectsUnknownReason() {
		assertThatThrownBy(() -> insertAudit(randomDiscordId(), "rejected", "provider_error"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void auditRejectsAcceptedWithReason() {
		assertThatThrownBy(() -> insertAudit(randomDiscordId(), "accepted", "banned"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void auditRejectsRejectedWithoutReason() {
		assertThatThrownBy(() -> insertAudit(randomDiscordId(), "rejected", null))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void noSessionTables() {
		assertThat(jdbc.sql("select table_name from information_schema.tables "
				+ "where table_schema = 'public' and table_name ilike '%session%'")
			.query(String.class)
			.list()).isEmpty();
	}

	@Test
	void noPendingMigrations() {
		assertThat(flyway.info().pending()).isEmpty();
	}

	@Test
	void stickersHasOnlyTheMinimalColumns() {
		assertThat(columns("stickers")).containsExactlyInAnyOrder("id", "public_id", "owner_id", "object_key", "width",
				"height", "png_bytes", "created_at");
	}

	@Test
	void uploadAuditHasOnlyTheMinimalColumns() {
		assertThat(columns("upload_audit")).containsExactlyInAnyOrder("id", "occurred_at", "discord_id", "outcome",
				"reason", "original_sha256", "original_bytes", "sticker_public_id");
	}

	@Test
	void stickerAcceptsACanonicalPublicId() {
		long owner = insertUser();
		String publicId = canonicalPublicId();

		insertSticker(publicId, owner, publicId + ".png");

		assertThat(jdbc.sql("select count(*) from stickers where public_id = ?").param(publicId).query(Long.class).single())
			.isEqualTo(1);
	}

	@Test
	void stickerRejectsNonCanonicalPublicIds() {
		long owner = insertUser();
		String canonical = canonicalPublicId();
		String body = canonical.substring(0, 21);
		for (String publicId : new String[] { body, canonical + "A", "+" + canonical.substring(1),
				"/" + canonical.substring(1), body + "B" }) {
			assertThatThrownBy(() -> insertSticker(publicId, owner, canonicalPublicId() + ".png")).as(publicId)
				.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test
	void stickerPublicIdAndObjectKeyAreUnique() {
		long owner = insertUser();
		String publicId = canonicalPublicId();
		String objectKey = publicId + ".png";
		insertSticker(publicId, owner, objectKey);

		assertThatThrownBy(() -> insertSticker(publicId, owner, canonicalPublicId() + ".png"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertSticker(canonicalPublicId(), owner, objectKey))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void stickerOwnerMustBeAnAccount() {
		assertThatThrownBy(() -> insertSticker(canonicalPublicId(), Long.MAX_VALUE, canonicalPublicId() + ".png"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void uploadAuditAcceptsConsistentRows() {
		long discordId = randomDiscordId();
		insertUploadAudit(discordId, "accepted", null, sha256(), canonicalPublicId());
		insertUploadAudit(discordId, "rejected", "unsupported_type", sha256(), null);
		insertUploadAudit(discordId, "rejected", "too_large", null, null);
		insertUploadAudit(discordId, "failed", "error", sha256(), null);

		assertThat(jdbc.sql("select count(*) from upload_audit where discord_id = ?")
			.param(discordId)
			.query(Long.class)
			.single()).isEqualTo(4);
	}

	@Test
	void uploadAuditRejectsInconsistentRows() {
		long discordId = randomDiscordId();
		// accepted: no reason, a public ID and a SHA-256
		assertInvalidUploadAudit(discordId, "accepted", "banned", sha256(), canonicalPublicId());
		assertInvalidUploadAudit(discordId, "accepted", null, sha256(), null);
		assertInvalidUploadAudit(discordId, "accepted", null, null, canonicalPublicId());
		// rejected: a reason other than error, no public ID
		assertInvalidUploadAudit(discordId, "rejected", null, sha256(), null);
		assertInvalidUploadAudit(discordId, "rejected", "error", sha256(), null);
		assertInvalidUploadAudit(discordId, "rejected", "unreadable", sha256(), canonicalPublicId());
		// failed: reason error only
		assertInvalidUploadAudit(discordId, "failed", "busy", sha256(), null);
		assertInvalidUploadAudit(discordId, "failed", null, sha256(), null);
		// unknown values; a SHA-256 is 32 bytes
		assertInvalidUploadAudit(discordId, "pending", "error", sha256(), null);
		assertInvalidUploadAudit(discordId, "rejected", "provider_error", sha256(), null);
		assertInvalidUploadAudit(discordId, "rejected", "unreadable", new byte[31], null);
	}

	@Test
	void acceptedUploadsAreIndexedByUserAndTime() {
		String definition = jdbc.sql("select indexdef from pg_indexes where indexname = 'upload_audit_accepted_recent'")
			.query(String.class)
			.single();

		assertThat(definition).contains("(discord_id, occurred_at)").contains("WHERE (outcome = 'accepted'::text)");
	}

	private void assertInvalidUploadAudit(long discordId, String outcome, String reason, byte[] sha256,
			String publicId) {
		assertThatThrownBy(() -> insertUploadAudit(discordId, outcome, reason, sha256, publicId))
			.as("%s / %s / sha %s / id %s", outcome, reason, (sha256 != null) ? sha256.length : null, publicId)
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insertUser() {
		return jdbc.sql("insert into users (discord_id) values (?) returning id")
			.param(randomDiscordId())
			.query(Long.class)
			.single();
	}

	private void insertSticker(String publicId, long ownerId, String objectKey) {
		jdbc.sql("insert into stickers (public_id, owner_id, object_key, width, height, png_bytes) values (?, ?, ?, 160, 120, 1234)")
			.params(publicId, ownerId, objectKey)
			.update();
	}

	private void insertUploadAudit(long discordId, String outcome, String reason, byte[] sha256, String publicId) {
		jdbc.sql("""
				insert into upload_audit (discord_id, outcome, reason, original_sha256, original_bytes, sticker_public_id)
				values (?, ?, ?, ?, 4096, ?)""")
			.params(discordId, outcome, reason, sha256, publicId)
			.update();
	}

	private static String canonicalPublicId() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static byte[] sha256() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	private List<String> columns(String table) {
		return jdbc.sql("select column_name from information_schema.columns "
				+ "where table_schema = 'public' and table_name = ?")
			.param(table)
			.query(String.class)
			.list();
	}

	private void insertAudit(long discordId, String outcome, String reason) {
		jdbc.sql("insert into login_audit (discord_id, outcome, reason) values (?, ?, ?)")
			.params(discordId, outcome, reason)
			.update();
	}

	private static long randomDiscordId() {
		return RANDOM.nextLong(1L << 40, Long.MAX_VALUE);
	}

}
