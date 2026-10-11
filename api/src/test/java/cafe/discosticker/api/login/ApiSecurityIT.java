package cafe.discosticker.api.login;

import static cafe.discosticker.api.login.LoginFlow.setCookies;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.jayway.jsonpath.JsonPath;

import cafe.discosticker.api.TestcontainersConfiguration;

/**
 * What the api does for signed-out and signed-in requests: 401 JSON instead of login redirects,
 * the permitted paths, the current-user endpoint, CSRF, and sign-out.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
class ApiSecurityIT {

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
	void signedOutCurrentUserIs401JsonWithoutRedirectOrSession() {
		HttpResponse<String> response = this.flow.get("/api/me");

		assertThat(response.statusCode()).isEqualTo(401);
		assertJson(response);
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertThat(setCookies(response, "SESSION")).isEmpty();
		assertThat(setCookies(response, "JSESSIONID")).isEmpty();
	}

	@Test
	void healthStaysPublic() {
		HttpResponse<String> response = this.flow.get("/api/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("UP");
	}

	@Test
	void unhandledPrefixedPathsWhileSignedOut() {
		HttpResponse<String> oauth2 = this.flow.get("/oauth2/x");
		HttpResponse<String> login = this.flow.get("/login/oauth2/x");
		HttpResponse<String> sticker = this.flow.get("/s/x");

		assertThat(oauth2.statusCode()).isEqualTo(401);
		assertJson(oauth2);
		assertThat(login.statusCode()).isEqualTo(401);
		assertJson(login);
		assertThat(sticker.statusCode()).isEqualTo(404);
		assertJson(sticker);
	}

	@Test
	void pathsOutsideThePermittedSetAreDenied() {
		// Includes Spring's generated login page, which must not exist.
		for (String path : new String[] { "/", "/login", "/logout", "/error/x" }) {
			HttpResponse<String> response = this.flow.get(path);

			assertThat(response.statusCode()).as(path).isEqualTo(401);
			assertJson(response);
			assertThat(response.headers().firstValue("Location")).as(path).isEmpty();
		}
	}

	@Test
	void csrfTokenCookieIsReadableByPageScripts() {
		HttpResponse<String> response = this.flow.get("/api/me");

		assertThat(setCookies(response, "XSRF-TOKEN")).singleElement().satisfies((cookie) -> {
			assertThat(cookie).containsPattern(";\\s*Path=/(;|$)");
			assertThat(cookie).doesNotContainPattern("(?i);\\s*HttpOnly");
		});
	}

	@Test
	void currentUserReturnsOnlyDiscordIdAndDisplayName() {
		FakeDiscord.User user = FakeDiscord.User.createdAgo(Duration.ofDays(30));
		this.flow.signIn(user);

		HttpResponse<String> response = this.flow.get("/api/me");

		assertThat(response.statusCode()).isEqualTo(200);
		Map<String, Object> body = JsonPath.read(response.body(), "$");
		assertThat(body).containsOnlyKeys("discordId", "displayName");
		assertThat(body.get("discordId")).isEqualTo(Long.toString(user.id()));
		assertThat(body.get("displayName")).isEqualTo(user.globalName());
	}

	@Test
	void displayNameFallsBackToUsername() {
		FakeDiscord.User user = FakeDiscord.User.createdAgo(Duration.ofDays(30)).withGlobalName(null);
		this.flow.signIn(user);

		HttpResponse<String> response = this.flow.get("/api/me");

		assertThat(JsonPath.<String>read(response.body(), "$.displayName")).isEqualTo(user.username());
	}

	@Test
	void signOutWithoutCsrfTokenIsRefused() {
		this.flow.signIn(FakeDiscord.User.createdAgo(Duration.ofDays(30)));
		this.flow.get("/api/me");

		HttpResponse<String> logout = this.flow.post("/api/logout");

		assertThat(logout.statusCode()).isEqualTo(403);
		assertThat(this.flow.get("/api/me").statusCode()).isEqualTo(200);
	}

	@Test
	void getDoesNotSignOut() {
		this.flow.signIn(FakeDiscord.User.createdAgo(Duration.ofDays(30)));

		this.flow.get("/api/logout");

		assertThat(this.flow.get("/api/me").statusCode()).isEqualTo(200);
	}

	@Test
	void signOutWithCsrfTokenEndsTheSession() {
		this.flow.signIn(FakeDiscord.User.createdAgo(Duration.ofDays(30)));
		this.flow.get("/api/me");
		String token = this.flow.cookie("XSRF-TOKEN");

		HttpResponse<String> logout = this.flow.post("/api/logout", "X-XSRF-TOKEN", token);

		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(logout.headers().firstValue("Location")).isEmpty();
		this.flow.replayEveryReceivedCookie();
		assertThat(this.flow.get("/api/me").statusCode()).isEqualTo(401);
	}

	private static void assertJson(HttpResponse<String> response) {
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("application/json").doesNotContainIgnoringCase("ISO-8859-1"));
		assertThat(response.body()).startsWith("{");
	}

}
