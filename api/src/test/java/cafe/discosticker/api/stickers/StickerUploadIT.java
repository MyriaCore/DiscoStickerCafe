package cafe.discosticker.api.stickers;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.jayway.jsonpath.JsonPath;

import cafe.discosticker.api.TestcontainersConfiguration;
import cafe.discosticker.api.login.FakeDiscord;
import cafe.discosticker.api.login.LoginFlow;
import cafe.discosticker.api.login.LoginFlow.FormPart;
import cafe.discosticker.api.login.LoginTestConfiguration;
import cafe.discosticker.api.stickers.UploadTestSupport.AuditRow;
import cafe.discosticker.api.stickers.UploadTestSupport.StickerRow;
import cafe.discosticker.api.stickers.UploadTestSupport.UploadClient;
import cafe.discosticker.api.stickers.image.TestImages;
import cafe.discosticker.api.storage.S3Properties;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code POST /api/stickers} end to end against the real Tomcat, Postgres and S3-compatible storage
 * (spec sticker-upload; design D2, D3, D8, D9). Every case checks what reached storage, the
 * {@code stickers} table and the upload audit, for that test's own Discord account.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
@ExtendWith(OutputCaptureExtension.class)
class StickerUploadIT {

	static final Pattern STICKER_PATH = Pattern.compile("^/s/([A-Za-z0-9_-]{21}[AQgw])\\.png$");

	static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

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

	UploadTestSupport support;

	@BeforeEach
	void storage() {
		this.support = new UploadTestSupport(this.jdbc, this.s3, this.s3Properties);
		this.support.ensureBucket();
	}

	UploadClient signIn() {
		return new UploadClient(this.port, this.discord);
	}

	// ---- Sign-in and CSRF: nothing stored, nothing audited ----

	@Test
	void signedOutWithAValidCsrfTokenGets401Json() {
		LoginFlow anonymous = new LoginFlow(this.port, this.discord);
		anonymous.get("/api/me");
		Snapshot before = snapshot();

		HttpResponse<String> response = anonymous.postMultipart("/api/stickers", List.of(validFile()), "X-XSRF-TOKEN",
				anonymous.cookie("XSRF-TOKEN"));

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("application/json"));
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	void signedOutWithoutACsrfTokenGets403() {
		LoginFlow anonymous = new LoginFlow(this.port, this.discord);
		Snapshot before = snapshot();

		HttpResponse<String> response = anonymous.postMultipart("/api/stickers", List.of(validFile()));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(snapshot()).isEqualTo(before);
	}

	@Test
	void signedInWithoutACsrfTokenGets403AndIsNotAudited() {
		UploadClient client = signIn();
		Snapshot before = snapshot();

		HttpResponse<String> response = client.flow.postMultipart("/api/stickers", List.of(validFile()));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(snapshot()).isEqualTo(before);
		assertThat(this.support.audit(client.discordId())).isEmpty();
	}

	@Test
	void signedInWithTheTokenOnlyInAMultipartFieldGets403() {
		UploadClient client = signIn();
		Snapshot before = snapshot();
		String token = client.csrfToken();

		for (String value : new String[] { token, LoginFlow.xorMaskedCsrfToken(token) }) {
			HttpResponse<String> response = client.flow.postMultipart("/api/stickers",
					List.of(FormPart.field("_csrf", value), validFile()));

			assertThat(response.statusCode()).as(value).isEqualTo(403);
		}
		assertThat(snapshot()).isEqualTo(before);
		assertThat(this.support.audit(client.discordId())).isEmpty();
	}

	// ---- Ban ----

	@Test
	void bannedAfterSigningInIsRejectedAndAudited() {
		UploadClient client = signIn();
		this.support.ban(client.discordId());
		byte[] png = validPng();

		assertRejected(client, () -> client.upload(png), 403, "banned", png);
	}

	// ---- Type from content ----

	@Test
	void textNamedAsPngIsUnsupportedAndAuditedWithItsHash() {
		UploadClient client = signIn();
		byte[] text = TestImages.text();

		assertRejected(client, () -> client.upload("sticker.png", "image/png", text), 415, "unsupported_type", text);
	}

	@Test
	void otherFormatsAreUnsupportedWhateverTheirName() {
		UploadClient client = signIn();
		for (byte[] other : List.of(TestImages.gif(), TestImages.webp(), TestImages.bmp(), TestImages.svg())) {
			Snapshot before = snapshot();

			HttpResponse<String> response = client.upload("sticker.png", "image/png", other);

			assertErrorBody(response, 415, "unsupported_type");
			assertThat(snapshot().keysAndStickers()).isEqualTo(before.keysAndStickers());
		}
		assertThat(this.support.audit(client.discordId())).hasSize(4)
			.allSatisfy((row) -> assertThat(row.reason()).isEqualTo("unsupported_type"));
	}

	@Test
	void contentDecidesNotTheNameOrContentType() {
		UploadClient client = signIn();
		byte[] png = validPng();

		assertAccepted(client, () -> client.upload("photo.txt", "text/plain", png), png);
	}

	@Test
	void dataAppendedToAValidPngIsDropped() {
		UploadClient client = signIn();
		byte[] polyglot = TestImages.pngWithAppendedHtml();

		Accepted accepted = assertAccepted(client, () -> client.upload(polyglot), polyglot);

		assertThat(TestImages.contains(accepted.stored(), TestImages.APPENDED_HTML)).isFalse();
	}

	// ---- Size ----

	@Test
	void oneByteOverTheLimitIsTooLargeAndAuditedWithoutAHash() {
		UploadClient client = signIn();
		Snapshot before = snapshot();

		HttpResponse<String> response = client.upload(TestImages.paddedPng(10_485_761));

		assertErrorBody(response, 413, "too_large");
		assertThat(snapshot().keysAndStickers()).isEqualTo(before.keysAndStickers());
		assertThat(this.support.audit(client.discordId())).singleElement().satisfies((row) -> {
			assertThat(row.outcome()).isEqualTo("rejected");
			assertThat(row.reason()).isEqualTo("too_large");
			assertThat(row.originalSha256()).isNull();
			assertThat(row.originalBytes()).isNull();
			assertThat(row.stickerPublicId()).isNull();
		});
	}

	@Test
	void exactlyTheLimitIsAccepted() {
		UploadClient client = signIn();
		byte[] png = TestImages.paddedPng(10_485_760);

		assertAccepted(client, () -> client.upload(png), png);
	}

	// ---- Dimensions, damage, color ----

	@Test
	void headerOnlyBombsAreRejected() {
		UploadClient client = signIn();

		assertRejected(client, () -> client.upload(TestImages.pngBomb()), 422, "dimensions_too_large",
				TestImages.pngBomb());
		assertRejected(client, () -> client.upload("bomb.jpg", "image/jpeg", TestImages.jpegBomb()), 422,
				"dimensions_too_large", TestImages.jpegBomb());
	}

	@Test
	void truncatedFilesAreUnreadable() {
		UploadClient client = signIn();

		assertRejected(client, () -> client.upload(TestImages.truncatedPng()), 422, "unreadable",
				TestImages.truncatedPng());
		assertRejected(client, () -> client.upload("cut.jpg", "image/jpeg", TestImages.truncatedJpeg()), 422,
				"unreadable", TestImages.truncatedJpeg());
	}

	@Test
	void cmykJpegIsUnsupportedColor() {
		UploadClient client = signIn();
		byte[] cmyk = TestImages.cmykJpeg();

		assertRejected(client, () -> client.upload("cmyk.jpg", "image/jpeg", cmyk), 422, "unsupported_color", cmyk);
	}

	// ---- Request shape ----

	@Test
	void requestsWithoutExactlyOneFilePartAreInvalid() {
		UploadClient client = signIn();
		byte[] png = validPng();

		List<HttpResponse<String>> responses = List.of(
				client.uploadParts(List.of(FormPart.file("image", "sticker.png", "image/png", png))),
				client.uploadParts(List.of(FormPart.file("file", "a.png", "image/png", png),
						FormPart.file("file", "b.png", "image/png", png))),
				client.flow.post("/api/stickers", HttpRequest.BodyPublishers.ofByteArray(png), "X-XSRF-TOKEN",
						client.csrfToken(), "Content-Type", "application/octet-stream"));

		for (HttpResponse<String> response : responses) {
			assertErrorBody(response, 400, "invalid_request");
		}
		assertThat(this.support.stickers(client.discordId())).isEmpty();
		assertThat(this.support.audit(client.discordId())).hasSize(3).allSatisfy((row) -> {
			assertThat(row.outcome()).isEqualTo("rejected");
			assertThat(row.reason()).isEqualTo("invalid_request");
		});
	}

	// ---- Output ----

	@Test
	void largerPhotoIsShrunkToFitAndReencodedAsPng() {
		UploadClient client = signIn();
		byte[] jpeg = TestImages.jpeg(TestImages.uprightMarked(4000, 3000));

		Accepted accepted = assertAccepted(client, () -> client.upload("photo.jpg", "image/jpeg", jpeg), jpeg);

		assertThat(accepted.size()).containsExactly(160, 120);
	}

	@Test
	void imagesAtOrBelowTheStickerSizeKeepTheirDimensions() {
		UploadClient client = signIn();
		for (BufferedImage image : List.of(TestImages.noise(160, 160, 41), TestImages.noise(1, 1, 42))) {
			byte[] png = TestImages.png(image);

			Accepted accepted = assertAccepted(client, () -> client.upload(png), png);

			assertThat(accepted.size()).containsExactly(image.getWidth(), image.getHeight());
		}
		byte[] jpeg = TestImages.jpeg(TestImages.noise(100, 50, 43));

		Accepted accepted = assertAccepted(client, () -> client.upload("small.jpg", "image/jpeg", jpeg), jpeg);

		assertThat(accepted.size()).containsExactly(100, 50);
	}

	@Test
	void exifOrientationIsAppliedBeforeKeepingDimensions() {
		UploadClient client = signIn();
		BufferedImage stored = TestImages.storedFor(TestImages.uprightMarked(20, 40), 6);
		byte[] jpeg = TestImages.jpegWithApp1(stored, TestImages.exifOrientation(6, ByteOrder.LITTLE_ENDIAN));

		Accepted accepted = assertAccepted(client, () -> client.upload("side.jpg", "image/jpeg", jpeg), jpeg);

		assertThat(accepted.size()).containsExactly(20, 40);
		int topLeft = accepted.image().getRGB(4, 4);
		assertThat((topLeft >> 16) & 0xFF).isGreaterThan(180);
		assertThat(topLeft & 0xFF).isLessThan(80);
	}

	@Test
	void transparencyIsKept() {
		UploadClient client = signIn();
		byte[] png = TestImages.png(TestImages.redSquareOnTransparentGreen(400));

		Accepted accepted = assertAccepted(client, () -> client.upload(png), png);

		assertThat(accepted.image().getRGB(0, 0) >>> 24).isZero();
		assertThat(accepted.image().getRGB(80, 80)).isEqualTo(0xFFFF0000);
	}

	@Test
	void locationDataIsNotCarriedIntoTheSticker() {
		UploadClient client = signIn();
		byte[] jpeg = TestImages.jpegWithApp1(TestImages.noise(64, 64, 44), TestImages.exifWithGps());

		Accepted accepted = assertAccepted(client, () -> client.upload("gps.jpg", "image/jpeg", jpeg), jpeg);

		List<String> chunks = TestImages.pngChunkTypes(accepted.stored());
		assertThat(chunks.getFirst()).isEqualTo("IHDR");
		assertThat(chunks.getLast()).isEqualTo("IEND");
		assertThat(chunks.subList(1, chunks.size() - 1)).containsOnly("IDAT");
		assertThat(TestImages.contains(accepted.stored(), "2026:10:11".getBytes(StandardCharsets.US_ASCII))).isFalse();
	}

	// ---- Hourly limit ----

	@Test
	void twentyFirstUploadWithinTheHourIsRateLimited() {
		UploadClient client = signIn();
		this.support.insertAudit(client.discordId(), "accepted", null, 20, Duration.ofMinutes(59));
		byte[] png = validPng();

		HttpResponse<String> response = assertRejected(client, () -> client.upload(png), 429, "rate_limited", png);
		int retryAfter = Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow());
		assertThat(retryAfter).isBetween(1, 60);
	}

	@Test
	void uploadsOlderThanAnHourLeaveTheWindow() {
		UploadClient client = signIn();
		this.support.insertAudit(client.discordId(), "accepted", null, 20, Duration.ofMinutes(61));
		byte[] png = validPng();

		assertAccepted(client, () -> client.upload(png), png);
	}

	@Test
	void rejectedAttemptsDoNotCount() {
		UploadClient client = signIn();
		this.support.insertAudit(client.discordId(), "accepted", null, 19, Duration.ofMinutes(10));
		this.support.insertAudit(client.discordId(), "rejected", "unreadable", 5, Duration.ofMinutes(5));
		byte[] png = validPng();

		assertAccepted(client, () -> client.upload(png), png);
	}

	@Test
	void twoConcurrentUploadsCannotBothTakeTheLastSlot() throws Exception {
		UploadClient client = signIn();
		this.support.insertAudit(client.discordId(), "accepted", null, 19, Duration.ofMinutes(10));
		byte[] png = validPng();
		CyclicBarrier start = new CyclicBarrier(2);
		Callable<HttpResponse<String>> upload = () -> {
			start.await();
			return client.upload(png);
		};

		List<Integer> statuses;
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<HttpResponse<String>>> results = pool.invokeAll(List.of(upload, upload));
			statuses = results.stream().map((result) -> {
				try {
					return result.get().statusCode();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}).sorted().toList();
		}

		assertThat(statuses).containsExactly(201, 429);
		assertThat(this.support.stickers(client.discordId())).hasSize(1);
		assertThat(this.support.audit(client.discordId()).subList(19, 21)).extracting(AuditRow::reason)
			.containsExactlyInAnyOrder(null, "rate_limited");
	}

	// ---- Logs ----

	@Test
	void eachUploadLogsOneLineAndNeverTheFileName(CapturedOutput output) {
		UploadClient client = signIn();

		client.upload("secret-name-7c1f.png", "image/png", validPng());
		client.upload("other-secret-9d2e.png", "image/png", TestImages.text());

		assertThat(output.getOut().lines().filter((line) -> line.contains("Upload accepted"))).hasSize(1);
		assertThat(output.getOut().lines().filter((line) -> line.contains("Upload rejected unsupported_type")))
			.hasSize(1);
		assertThat(output.getAll()).doesNotContain("secret-name-7c1f").doesNotContain("other-secret-9d2e");
	}

	// ---- Helpers ----

	/** What an accepted upload left behind. */
	record Accepted(String publicId, byte[] stored, BufferedImage image) {

		int[] size() {
			return new int[] { this.image.getWidth(), this.image.getHeight() };
		}

	}

	/** Counts that must not change when an upload is refused. */
	record Snapshot(int keys, long stickers, long audit) {

		List<Long> keysAndStickers() {
			return List.of((long) this.keys, this.stickers);
		}

	}

	Snapshot snapshot() {
		return new Snapshot(this.support.keys().size(), this.support.stickerCount(), this.support.auditCount());
	}

	/**
	 * Runs the upload; expects a 201 with exactly path, width and height, one new PNG object in
	 * storage, a sticker row, and an accepted audit row with the original's hash and size.
	 */
	Accepted assertAccepted(UploadClient client, Supplier<HttpResponse<String>> upload, byte[] original) {
		Snapshot before = snapshot();
		HttpResponse<String> response = upload.get();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		Map<String, Object> json = JsonPath.read(response.body(), "$");
		assertThat(json).containsOnlyKeys("path", "width", "height");
		String path = (String) json.get("path");
		var matcher = STICKER_PATH.matcher(path);
		assertThat(matcher.matches()).as(path).isTrue();
		assertThat(response.headers().firstValue("Location")).contains(path);
		String publicId = matcher.group(1);

		StickerRow row = this.support.stickers(client.discordId()).getLast();
		assertThat(row.publicId()).isEqualTo(publicId);
		assertThat(row.objectKey()).isEqualTo(publicId + ".png");
		byte[] stored = this.support.object(row.objectKey());
		assertThat(java.util.Arrays.copyOf(stored, 8)).isEqualTo(PNG_SIGNATURE);
		if (!java.util.Arrays.equals(java.util.Arrays.copyOf(original, 8), PNG_SIGNATURE)) {
			// A plain PNG written by the JDK re-encodes to the same bytes (same deterministic writer),
			// so the inequality only means something for other inputs. The polyglot, transparency and
			// GPS cases show PNG uploads are re-encoded.
			assertThat(stored).isNotEqualTo(original);
		}
		assertThat(row.pngBytes()).isEqualTo(stored.length);
		BufferedImage image = decode(stored);
		assertThat(List.of(row.width(), row.height())).containsExactly(image.getWidth(), image.getHeight());
		assertThat(List.of(((Number) json.get("width")).intValue(), ((Number) json.get("height")).intValue()))
			.containsExactly(image.getWidth(), image.getHeight());

		assertThat(this.support.audit(client.discordId()).getLast()).satisfies((audit) -> {
			assertThat(audit.outcome()).isEqualTo("accepted");
			assertThat(audit.reason()).isNull();
			assertThat(audit.originalSha256()).isEqualTo(UploadTestSupport.sha256(original));
			assertThat(audit.originalBytes()).isEqualTo((long) original.length);
			assertThat(audit.stickerPublicId()).isEqualTo(publicId);
		});
		Snapshot after = snapshot();
		assertThat(after.keys()).isEqualTo(before.keys() + 1);
		assertThat(after.stickers()).isEqualTo(before.stickers() + 1);
		return new Accepted(publicId, stored, image);
	}

	/**
	 * Runs the upload; expects the fixed error body, nothing new in storage or {@code stickers}, and
	 * exactly one new audit row: rejected, with the code and the original's hash and size.
	 */
	HttpResponse<String> assertRejected(UploadClient client, Supplier<HttpResponse<String>> upload, int status,
			String code, byte[] original) {
		Snapshot before = snapshot();
		HttpResponse<String> response = upload.get();
		assertErrorBody(response, status, code);
		Snapshot after = snapshot();
		assertThat(after.keysAndStickers()).isEqualTo(before.keysAndStickers());
		assertThat(after.audit()).isEqualTo(before.audit() + 1);
		assertThat(this.support.audit(client.discordId()).getLast()).satisfies((audit) -> {
			assertThat(audit.outcome()).isEqualTo("rejected");
			assertThat(audit.reason()).isEqualTo(code);
			assertThat(audit.originalSha256()).isEqualTo(UploadTestSupport.sha256(original));
			assertThat(audit.originalBytes()).isEqualTo((long) original.length);
			assertThat(audit.stickerPublicId()).isNull();
		});
		return response;
	}

	static void assertErrorBody(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("application/json"));
		Map<String, Object> json = JsonPath.read(response.body(), "$");
		assertThat(json).containsOnlyKeys("error").containsEntry("error", code);
	}

	static byte[] validPng() {
		return TestImages.png(TestImages.solid(48, 48, Color.ORANGE));
	}

	static FormPart validFile() {
		return FormPart.file("file", "sticker.png", "image/png", validPng());
	}

	static BufferedImage decode(byte[] png) {
		try {
			return ImageIO.read(new ByteArrayInputStream(png));
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
