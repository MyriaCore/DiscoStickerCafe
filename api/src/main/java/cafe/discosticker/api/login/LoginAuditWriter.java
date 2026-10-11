package cafe.discosticker.api.login;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes one audit row per login attempt whose identity Discord confirmed. Nothing else is
 * audited: unidentified failures are only logged, so anonymous requests can't fill the table.
 */
@Repository
public class LoginAuditWriter {

	private final JdbcClient jdbc;

	public LoginAuditWriter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void accepted(long discordId) {
		this.jdbc.sql("insert into login_audit (discord_id, outcome) values (?, 'accepted')").param(discordId).update();
	}

	public void rejected(long discordId, RejectionReason reason) {
		this.jdbc.sql("insert into login_audit (discord_id, outcome, reason) values (?, 'rejected', ?)")
			.params(discordId, reason.code())
			.update();
	}

}
