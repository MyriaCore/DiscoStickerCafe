package cafe.discosticker.api.stickers;

import java.io.InputStream;

import org.springframework.stereotype.Component;

import cafe.discosticker.api.storage.S3Properties;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** Sticker PNGs in the private bucket. Only re-encoded PNGs are ever written here. */
@Component
public class StickerStorage {

	private final S3Client s3;

	private final String bucket;

	StickerStorage(S3Client s3, S3Properties properties) {
		this.s3 = s3;
		this.bucket = properties.bucket();
	}

	void put(String key, byte[] png) {
		this.s3.putObject((b) -> b.bucket(this.bucket).key(key).contentType("image/png").contentLength((long) png.length),
				RequestBody.fromBytes(png));
	}

	void delete(String key) {
		this.s3.deleteObject((b) -> b.bucket(this.bucket).key(key));
	}

	/** Streams the object; the caller closes it. */
	public InputStream open(String key) {
		return this.s3.getObject((b) -> b.bucket(this.bucket).key(key));
	}

}
