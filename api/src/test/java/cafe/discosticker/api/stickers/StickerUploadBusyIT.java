package cafe.discosticker.api.stickers;

import static cafe.discosticker.api.stickers.StickerUploadIT.assertErrorBody;
import static cafe.discosticker.api.stickers.StickerUploadIT.validPng;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

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
import cafe.discosticker.api.stickers.image.DecodeLimiter;
import cafe.discosticker.api.storage.S3Properties;
import software.amazon.awssdk.services.s3.S3Client;

/** An upload that can't get a decode permit in time is rejected as busy (spec "Bounded concurrent decoding"). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
@TestPropertySource(properties = "app.upload.decode-wait=200ms")
class StickerUploadBusyIT {

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

	@Autowired
	DecodeLimiter decodeLimiter;

	@Test
	void busyWhenEveryDecodePermitIsHeld() {
		UploadTestSupport support = new UploadTestSupport(this.jdbc, this.s3, this.s3Properties);
		support.ensureBucket();
		UploadClient client = new UploadClient(this.port, this.discord);
		byte[] png = validPng();
		int keysBefore = support.keys().size();

		HttpResponse<String> response;
		try (DecodeLimiter.Permit _ = this.decodeLimiter.acquire(); DecodeLimiter.Permit _ = this.decodeLimiter.acquire()) {
			response = client.upload(png);
		}

		assertErrorBody(response, 503, "busy");
		assertThat(support.keys()).hasSize(keysBefore);
		assertThat(support.stickers(client.discordId())).isEmpty();
		assertThat(support.audit(client.discordId())).singleElement().satisfies((row) -> {
			assertThat(row.outcome()).isEqualTo("rejected");
			assertThat(row.reason()).isEqualTo("busy");
			assertThat(row.originalSha256()).isEqualTo(UploadTestSupport.sha256(png));
		});
		// Once a permit is free again, uploads go through.
		assertThat(client.upload(png).statusCode()).isEqualTo(201);
	}

}
