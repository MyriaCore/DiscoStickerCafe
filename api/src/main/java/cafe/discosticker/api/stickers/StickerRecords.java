package cafe.discosticker.api.stickers;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code stickers} table. Only the public ID ever leaves the api; {@code id} and {@code owner_id} never do. */
@Repository
public class StickerRecords {

	/** What serving a sticker needs. */
	public record StoredSticker(String objectKey, int pngBytes) {
	}

	private final JdbcClient jdbc;

	StickerRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(String publicId, long ownerId, String objectKey, int width, int height, int pngBytes) {
		this.jdbc.sql("""
				insert into stickers (public_id, owner_id, object_key, width, height, png_bytes)
				values (?, ?, ?, ?, ?, ?)""")
			.params(publicId, ownerId, objectKey, width, height, pngBytes)
			.update();
	}

	/** By the canonical public ID, compared as a string, never decoded. */
	public Optional<StoredSticker> find(String publicId) {
		return this.jdbc.sql("select object_key, png_bytes from stickers where public_id = ?")
			.param(publicId)
			.query((rs, i) -> new StoredSticker(rs.getString(1), rs.getInt(2)))
			.optional();
	}

}
