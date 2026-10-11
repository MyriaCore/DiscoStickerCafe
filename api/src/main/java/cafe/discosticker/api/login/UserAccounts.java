package cafe.discosticker.api.login;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The minimal account record: Discord ID, first-seen and last-login times, and the ban marker.
 * Bans are set by hand in the database for now.
 */
@Repository
public class UserAccounts {

	private final JdbcClient jdbc;

	public UserAccounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public boolean isBanned(long discordId) {
		return this.jdbc
			.sql("select exists (select 1 from users where discord_id = ? and banned_at is not null)")
			.param(discordId)
			.query(Boolean.class)
			.single();
	}

	/** Creates the account on first login, otherwise updates its last-login time. */
	public void recordLogin(long discordId) {
		this.jdbc.sql("""
				insert into users (discord_id, last_login_at) values (?, now())
				on conflict (discord_id) do update set last_login_at = excluded.last_login_at""")
			.param(discordId)
			.update();
	}

}
