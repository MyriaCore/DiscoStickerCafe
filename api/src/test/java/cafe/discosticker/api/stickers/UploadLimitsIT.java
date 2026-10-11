package cafe.discosticker.api.stickers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;

import cafe.discosticker.api.TestcontainersConfiguration;
import cafe.discosticker.api.login.FakeDiscord;
import cafe.discosticker.api.login.LoginFlow;
import cafe.discosticker.api.login.LoginTestConfiguration;

/**
 * {@code GET /api/stickers/limits}: what the upload form shows, taken from the same settings the
 * upload checks enforce.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
class UploadLimitsIT {

	@LocalServerPort
	int port;

	@Autowired
	FakeDiscord discord;

	LoginFlow flow;

	@BeforeEach
	void newBrowser() {
		this.flow = new LoginFlow(this.port, this.discord);
	}

	@Test
	void signedOutGets401Json() {
		HttpResponse<String> response = this.flow.get("/api/stickers/limits");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("application/json"));
	}

	@Test
	void signedInGetsTheDefaultLimits() {
		this.flow.signIn(FakeDiscord.User.createdAgo(Duration.ofDays(30)));

		HttpResponse<String> response = this.flow.get("/api/stickers/limits");

		assertThat(response.statusCode()).isEqualTo(200);
		assertLimits(response.body(), 10_485_760, 20, 160);
	}

	static void assertLimits(String body, long maxBytes, int uploadsPerHour, int stickerSize) {
		Map<String, Object> json = JsonPath.read(body, "$");
		assertThat(json).containsOnlyKeys("acceptedTypes", "maxBytes", "uploadsPerHour", "stickerSize");
		assertThat(JsonPath.<List<String>>read(body, "$.acceptedTypes")).containsExactly("image/jpeg", "image/png");
		assertThat(((Number) json.get("maxBytes")).longValue()).isEqualTo(maxBytes);
		assertThat(((Number) json.get("uploadsPerHour")).intValue()).isEqualTo(uploadsPerHour);
		assertThat(((Number) json.get("stickerSize")).intValue()).isEqualTo(stickerSize);
	}

	@Nested
	@TestPropertySource(properties = { "app.upload.max-file-size=5MB", "app.upload.uploads-per-hour=7",
			"app.upload.sticker-size=96" })
	class WithOtherSettings {

		@LocalServerPort
		int port;

		@Autowired
		FakeDiscord discord;

		@Test
		void reportsTheConfiguredValues() {
			LoginFlow flow = new LoginFlow(this.port, this.discord);
			flow.signIn(FakeDiscord.User.createdAgo(Duration.ofDays(30)));

			HttpResponse<String> response = flow.get("/api/stickers/limits");

			assertThat(response.statusCode()).isEqualTo(200);
			assertLimits(response.body(), 5_242_880, 7, 96);
		}

	}

}
