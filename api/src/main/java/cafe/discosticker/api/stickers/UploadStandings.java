package cafe.discosticker.api.stickers;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A signed-in account's standing for uploading: its internal key, whether it is banned, and its
 * accepted uploads in the last 60 minutes, counted from the upload audit (design D8). The window
 * is measured with the database's clock.
 */
@Repository
class UploadStandings {

	/**
	 * @param retryAfterSeconds seconds until the oldest counted upload is 60 minutes old (at least
	 * 1), or null when none is counted
	 */
	record Standing(long userId, boolean banned, int acceptedLastHour, Long retryAfterSeconds) {
	}

	private static final String STANDING = """
			select u.id, u.banned_at is not null, count(a.id),
			       case when count(a.id) > 0 then greatest(1,
			           ceil(extract(epoch from min(a.occurred_at) + interval '60 minutes' - now())))::bigint end
			from users u
			left join upload_audit a on a.discord_id = u.discord_id and a.outcome = 'accepted'
			                         and a.occurred_at > now() - interval '60 minutes'
			where u.discord_id = ?
			group by u.id, u.banned_at""";

	private final JdbcClient jdbc;

	UploadStandings(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The standing without a lock: the cheap check before any decoding. Null if there's no account row. */
	Standing current(long discordId) {
		return this.jdbc.sql(STANDING)
			.param(discordId)
			.query((rs, i) -> new Standing(rs.getLong(1), rs.getBoolean(2), rs.getInt(3), (Long) rs.getObject(4)))
			.optional()
			.orElse(null);
	}

	/**
	 * Locks the account row until the current transaction ends, then reads the standing. Concurrent
	 * uploads by the same user wait here, so they can't both take the last slot. Must run inside a
	 * transaction. Null if there's no account row.
	 */
	Standing locked(long discordId) {
		boolean exists = this.jdbc.sql("select id from users where discord_id = ? for update")
			.param(discordId)
			.query(Long.class)
			.optional()
			.isPresent();
		return exists ? current(discordId) : null;
	}

}
