package cafe.discosticker.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
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
 * avatar, token or IP column, and no session tables (sessions live in memory).
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
