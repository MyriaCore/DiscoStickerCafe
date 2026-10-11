package cafe.discosticker.api.stickers;

import static cafe.discosticker.api.stickers.StickerUploadIT.assertErrorBody;
import static cafe.discosticker.api.stickers.StickerUploadIT.decode;
import static cafe.discosticker.api.stickers.StickerUploadIT.validPng;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
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
import cafe.discosticker.api.stickers.image.TestImages;
import cafe.discosticker.api.storage.S3Properties;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Uploads are checked against the configured settings, not fixed numbers (spec "Upload limits
 * endpoint": values follow the settings).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
@TestPropertySource(properties = { "app.upload.max-file-size=1MB", "app.upload.uploads-per-hour=2",
		"app.upload.sticker-size=96" })
class StickerUploadSettingsIT {

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

	@BeforeEach
	void storage() {
		new UploadTestSupport(this.jdbc, this.s3, this.s3Properties).ensureBucket();
	}

	@Test
	void sizeLimitFollowsTheSetting() {
		UploadClient client = new UploadClient(this.port, this.discord);

		assertErrorBody(client.upload(TestImages.paddedPng(1_572_864)), 413, "too_large");
	}

	@Test
	void hourlyLimitFollowsTheSetting() {
		UploadClient client = new UploadClient(this.port, this.discord);

		assertThat(client.upload(validPng()).statusCode()).isEqualTo(201);
		assertThat(client.upload(validPng()).statusCode()).isEqualTo(201);
		assertErrorBody(client.upload(validPng()), 429, "rate_limited");
	}

	@Test
	void stickerSizeFollowsTheSetting() {
		UploadClient client = new UploadClient(this.port, this.discord);
		UploadTestSupport support = new UploadTestSupport(this.jdbc, this.s3, this.s3Properties);

		assertThat(client.upload(TestImages.png(TestImages.noise(200, 100, 51))).statusCode()).isEqualTo(201);

		String key = support.stickers(client.discordId()).getLast().objectKey();
		var image = decode(support.object(key));
		assertThat(image.getWidth()).isEqualTo(96);
		assertThat(image.getHeight()).isEqualTo(48);
	}

}
