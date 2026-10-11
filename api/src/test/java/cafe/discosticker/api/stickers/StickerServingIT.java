package cafe.discosticker.api.stickers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.jayway.jsonpath.JsonPath;

import cafe.discosticker.api.TestcontainersConfiguration;
import cafe.discosticker.api.login.FakeDiscord;
import cafe.discosticker.api.login.LoginFlow;
import cafe.discosticker.api.login.LoginTestConfiguration;
import cafe.discosticker.api.stickers.UploadTestSupport.UploadClient;
import cafe.discosticker.api.storage.S3Properties;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code GET /s/<id>.png} (spec "Stickers served publicly", "Unknown and malformed sticker IDs look
 * the same"; design D13): public, exact headers, no cookies or session effects, and one 404 for
 * every ID that isn't an existing sticker, with malformed IDs never reaching the database.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
class StickerServingIT {

	private static final SecureRandom RANDOM = new SecureRandom();

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

	@MockitoSpyBean
	StickerRecords records;

	UploadTestSupport support;

	UploadClient owner;

	String path;

	byte[] stored;

	@BeforeEach
	void uploadASticker() {
		this.support = new UploadTestSupport(this.jdbc, this.s3, this.s3Properties);
		this.support.ensureBucket();
		this.owner = new UploadClient(this.port, this.discord);
		HttpResponse<String> created = this.owner.upload(StickerUploadIT.validPng());
		assertThat(created.statusCode()).isEqualTo(201);
		this.path = JsonPath.read(created.body(), "$.path");
		this.stored = this.support.object(this.support.stickers(this.owner.discordId()).getLast().objectKey());
	}

	@Test
	void signedOutGetsThePngWithExactHeaders() {
		HttpResponse<byte[]> response = new LoginFlow(this.port, this.discord).getBytes(this.path);

		assertThat(response.statusCode()).isEqualTo(200);
		assertStickerHeaders(response.headers(), this.stored.length);
		assertThat(response.body()).isEqualTo(this.stored);
	}

	@Test
	void signedInViewerKeepsTheirSessionAndGetsNoCookie() {
		String session = this.owner.flow.cookie("SESSION");

		HttpResponse<byte[]> response = this.owner.flow.getBytes(this.path);

		assertThat(response.statusCode()).isEqualTo(200);
		assertStickerHeaders(response.headers(), this.stored.length);
		assertThat(response.body()).isEqualTo(this.stored);
		assertThat(this.owner.flow.cookie("SESSION")).isEqualTo(session);
		assertThat(this.owner.flow.get("/api/me").statusCode()).isEqualTo(200);
	}

	@Test
	void headGivesTheSameHeadersAndNoBody() {
		HttpResponse<String> response = new LoginFlow(this.port, this.discord).head(this.path);

		assertThat(response.statusCode()).isEqualTo(200);
		assertStickerHeaders(response.headers(), this.stored.length);
		assertThat(response.body()).isEmpty();
	}

	@Test
	void malformedAndUnknownIdsGetTheSame404WithoutReachingTheDatabaseWhenMalformed() {
		LoginFlow anyone = new LoginFlow(this.port, this.discord);
		String unknown = canonicalId();
		HttpResponse<String> unknownResponse = anyone.get("/s/" + unknown + ".png");
		Map<String, Object> expected = withoutEchoes(assertJson404(unknownResponse));
		verify(this.records).find(unknown);
		clearInvocations(this.records);

		String id = canonicalId();
		List<String> malformed = List.of("/s/" + id.substring(0, 21) + ".png", // 21 characters
				"/s/" + id + "A.png", // 23 characters
				"/s/+" + id.substring(1) + ".png", // + is not base64url
				"/s/" + id.substring(0, 10) + "/" + id.substring(11) + ".png", // / splits it into two segments
				"/s/" + id.substring(0, 21) + "B.png", // a non-canonical last character
				"/s/" + id + ".jpg", "/s/" + id + ".png/x", "/s/" + id, "/s/x");
		for (String path : malformed) {
			HttpResponse<String> response = anyone.get(path);

			assertThat(withoutEchoes(assertJson404(response))).as(path).isEqualTo(expected);
		}
		verify(this.records, never()).find(anyString());
	}

	private static void assertStickerHeaders(HttpHeaders headers, long length) {
		assertThat(headers.allValues("Content-Type")).containsExactly("image/png");
		assertThat(headers.firstValueAsLong("Content-Length")).hasValue(length);
		assertThat(headers.allValues("Cache-Control")).containsExactly("public, max-age=86400");
		assertThat(headers.firstValue("Set-Cookie")).isEmpty();
		assertThat(headers.firstValue("Pragma")).isEmpty();
		assertThat(headers.firstValue("Expires")).isEmpty();
	}

	private Map<String, Object> assertJson404(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("application/json"));
		return JsonPath.read(response.body(), "$");
	}

	/** The error body without the fields that echo the request: its path, and the time. */
	private static Map<String, Object> withoutEchoes(Map<String, Object> body) {
		Map<String, Object> rest = new HashMap<>(body);
		rest.remove("path");
		rest.remove("timestamp");
		return rest;
	}

	private static String canonicalId() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
