package cafe.discosticker.api.stickers;

import static cafe.discosticker.api.stickers.StickerUploadIT.assertErrorBody;
import static cafe.discosticker.api.stickers.StickerUploadIT.validPng;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import cafe.discosticker.api.TestcontainersConfiguration;
import cafe.discosticker.api.login.FakeDiscord;
import cafe.discosticker.api.login.LoginTestConfiguration;
import cafe.discosticker.api.stickers.UploadTestSupport.UploadClient;
import cafe.discosticker.api.storage.S3Properties;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * When storage rejects the PNG write, the upload fails with {@code error}, no sticker row remains,
 * and the attempt is audited as failed (spec "Accepted means stored and audited"; design D9).
 * This context has its own storage container, and nothing here creates the bucket, so every PUT
 * fails.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
@TestPropertySource(properties = "test.context=storage-without-bucket")
class StickerUploadStorageFailureIT {

	@LocalServerPort
	int port;

	@Autowired
	FakeDiscord discord;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	S3Client s3;

	@Autowired
	S3Properties s3Properties;

	@Test
	void failedStorageWriteLeavesNoStickerAndIsAuditedAsFailed() {
		UploadTestSupport support = new UploadTestSupport(this.jdbc, this.s3, this.s3Properties);
		UploadClient client = new UploadClient(this.port, this.discord);
		byte[] png = validPng();

		assertErrorBody(client.upload(png), 500, "error");

		assertThat(support.stickers(client.discordId())).isEmpty();
		assertThat(support.audit(client.discordId())).singleElement().satisfies((row) -> {
			assertThat(row.outcome()).isEqualTo("failed");
			assertThat(row.reason()).isEqualTo("error");
			assertThat(row.originalSha256()).isEqualTo(UploadTestSupport.sha256(png));
			assertThat(row.stickerPublicId()).isNull();
		});
	}

}
