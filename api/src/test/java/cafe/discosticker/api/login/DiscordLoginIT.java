package cafe.discosticker.api.login;

import static cafe.discosticker.api.login.LoginFlow.encode;
import static cafe.discosticker.api.login.LoginFlow.queryParams;
import static cafe.discosticker.api.login.LoginFlow.setCookies;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

import cafe.discosticker.api.TestcontainersConfiguration;

/**
 * The Discord login flow end to end against the real Tomcat, with Discord faked: the
 * authorization redirect, the ban and age rules, what a rejected login leaves behind, the
 * accepted session, token retention, and which attempts are audited or only logged.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
@ExtendWith(OutputCaptureExtension.class)
class DiscordLoginIT {

	private static final String SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";

	private static final String UNIDENTIFIED_FAILURE_LOG = "Login failed before Discord confirmed an identity";

	@LocalServerPort
	int port;

	@Autowired
	FakeDiscord discord;

	@Autowired
	SessionRecorder sessions;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	OAuth2AuthorizedClientService authorizedClients;

	LoginFlow flow;

	@BeforeEach
	void newBrowser() {
		this.flow = new LoginFlow(this.port, this.discord);
	}

	@Test
	void authorizationRedirectAsksDiscordForIdentifyOnly() {
		LoginFlow.Authorization authorization = this.flow.startAuthorization();

		HttpResponse<String> response = authorization.response();
		assertThat(response.statusCode()).isEqualTo(302);
		assertThat(response.headers().firstValue("Location").orElseThrow())
			.startsWith("https://discord.com/oauth2/authorize?");
		Map<String, String> params = authorization.params();
		assertThat(params).containsEntry("response_type", "code")
			.containsEntry("client_id", TestcontainersConfiguration.DISCORD_CLIENT_ID)
			.containsEntry("scope", "identify")
			.containsEntry("redirect_uri", "http://localhost:" + this.port + "/login/oauth2/code/discord");
		assertThat(params.get("state")).isNotBlank();
	}

	@Test
	void preLoginSessionCookieAndIdleTimeout() {
		LoginFlow.Authorization authorization = this.flow.startAuthorization();

		assertThat(setCookies(authorization.response(), "SESSION")).singleElement().satisfies((cookie) -> {
			assertThat(cookie).containsPattern(";\\s*Path=/(;|$)")
				.containsPattern("(?i);\\s*HttpOnly(;|$)")
				.containsPattern("(?i);\\s*SameSite=Lax(;|$)")
				.doesNotContainPattern("(?i);\\s*Secure(;|$)")
				.doesNotContainPattern("(?i);\\s*(Domain|Max-Age|Expires)=");
		});
		SessionRecorder.Recorded preLogin = this.sessions.byId(this.flow.cookie("SESSION")).orElseThrow();
		assertThat(preLogin.maxInactiveAtCreation()).isEqualTo(600);
		assertThat(preLogin.attributeNames()).doesNotContain(SECURITY_CONTEXT);
	}

	@Test
	void forwardedHttpsBuildsTheProductionRedirectUriAndASecureCookie() {
		LoginFlow.Authorization authorization = this.flow.startAuthorization("X-Forwarded-Proto", "https",
				"X-Forwarded-Host", "discosticker.cafe", "X-Forwarded-Port", "443");

		assertThat(authorization.params()).containsEntry("redirect_uri",
				"https://discosticker.cafe/login/oauth2/code/discord");
		assertThat(setCookies(authorization.response(), "SESSION")).singleElement().satisfies((cookie) -> {
			assertThat(cookie).containsPattern("(?i);\\s*Secure(;|$)")
				.containsPattern("(?i);\\s*HttpOnly(;|$)")
				.containsPattern("(?i);\\s*SameSite=Lax(;|$)")
				.containsPattern(";\\s*Path=/(;|$)")
				.doesNotContainPattern("(?i);\\s*(Domain|Max-Age|Expires)=");
		});
	}

	@Test
	void acceptedLoginStartsANewSignedInSession(CapturedOutput output) {
		FakeDiscord.User user = FakeDiscord.User.createdAgo(Duration.ofDays(8));
		LoginFlow.Authorization authorization = this.flow.startAuthorization();
		String preLoginId = this.flow.cookie("SESSION");
		int tokenRequestsBefore = this.discord.tokenRequests().size();

		HttpResponse<String> response = this.flow.callback(callbackQuery(user, authorization));

		assertRedirect(response, "/");
		String signedInId = this.flow.cookie("SESSION");
		assertThat(signedInId).isNotNull().isNotEqualTo(preLoginId);
		SessionRecorder.Recorded signedIn = this.sessions.byId(signedInId).orElseThrow();
		assertThat(signedIn.attributeNames()).contains(SECURITY_CONTEXT);
		assertThat(signedIn.session().getMaxInactiveInterval()).isEqualTo(8 * 60 * 60);

		List<FakeDiscord.TokenRequest> tokenRequests = this.discord.tokenRequests();
		assertThat(tokenRequests).hasSize(tokenRequestsBefore + 1);
		assertThat(tokenRequests.getLast().form()).containsEntry("grant_type", "authorization_code")
			.containsEntry("redirect_uri", "http://localhost:" + this.port + "/login/oauth2/code/discord");

		Map<String, Object> account = this.jdbc.sql("select last_login_at, banned_at from users where discord_id = ?")
			.param(user.id())
			.query()
			.singleRow();
		assertThat(account.get("last_login_at")).isNotNull();
		assertThat(account.get("banned_at")).isNull();
		assertThat(auditRows(user.id())).containsExactly(Map.of("outcome", "accepted"));

		assertThat(this.authorizedClients.<OAuth2AuthorizedClient>loadAuthorizedClient("discord",
				Long.toString(user.id()))).isNull();
		List<String> tokens = this.discord.issuedTokens();
		List<String> thisLoginsTokens = tokens.subList(tokens.size() - 2, tokens.size());
		for (String token : thisLoginsTokens) {
			for (SessionRecorder.Recorded recorded : this.sessions.all()) {
				assertThat(recorded.attributeValues()).noneMatch((value) -> value.contains(token));
			}
			assertThat(output.getAll()).doesNotContain(token);
		}
	}

	@Test
	void bannedAccountIsRejectedWithoutASession() {
		FakeDiscord.User user = FakeDiscord.User.createdAgo(Duration.ofDays(30));
		this.jdbc.sql("insert into users (discord_id, banned_at) values (?, now())").param(user.id()).update();

		LoginFlow.Authorization authorization = this.flow.startAuthorization();
		String preLoginId = this.flow.cookie("SESSION");
		HttpResponse<String> response = this.flow.callback(callbackQuery(user, authorization));

		assertRedirect(response, "/?login=banned");
		assertNoAuthenticatedSession(preLoginId);
		assertThat(auditRows(user.id())).containsExactly(Map.of("outcome", "rejected", "reason", "banned"));
		Map<String, Object> account = this.jdbc.sql("select last_login_at, banned_at from users where discord_id = ?")
			.param(user.id())
			.query()
			.singleRow();
		assertThat(account.get("last_login_at")).isNull();
		assertThat(account.get("banned_at")).isNotNull();
	}

	@Test
	void banOutranksAccountAge() {
		FakeDiscord.User user = FakeDiscord.User.createdAgo(Duration.ofDays(1));
		this.jdbc.sql("insert into users (discord_id, banned_at) values (?, now())").param(user.id()).update();

		HttpResponse<String> response = this.flow.signIn(user);

		assertRedirect(response, "/?login=banned");
		assertThat(auditRows(user.id())).containsExactly(Map.of("outcome", "rejected", "reason", "banned"));
	}

	@Test
	void tooNewAccountIsRejectedWithoutASessionOrAccount() {
		FakeDiscord.User user = FakeDiscord.User.createdAgo(Duration.ofDays(6));

		LoginFlow.Authorization authorization = this.flow.startAuthorization();
		String preLoginId = this.flow.cookie("SESSION");
		HttpResponse<String> response = this.flow.callback(callbackQuery(user, authorization));

		assertRedirect(response, "/?login=too_new");
		assertNoAuthenticatedSession(preLoginId);
		assertThat(this.jdbc.sql("select count(*) from users where discord_id = ?")
			.param(user.id())
			.query(Long.class)
			.single()).isZero();
		assertThat(auditRows(user.id())).containsExactly(Map.of("outcome", "rejected", "reason", "too_new"));
	}

	@Test
	void deniedConsentIsLoggedNotAudited(CapturedOutput output) {
		LoginFlow.Authorization authorization = this.flow.startAuthorization();
		String preLoginId = this.flow.cookie("SESSION");
		String state = authorization.params().get("state");
		int tokenRequestsBefore = this.discord.tokenRequests().size();
		long auditBefore = auditCount();

		HttpResponse<String> response = this.flow.callback("error=access_denied"
				+ "&error_description=The+resource+owner+or+authorization+server+denied+the+request&state="
				+ encode(state));

		assertRedirect(response, "/?login=error");
		assertNoAuthenticatedSession(preLoginId);
		assertThat(this.discord.tokenRequests()).hasSize(tokenRequestsBefore);
		assertThat(auditCount()).isEqualTo(auditBefore);
		assertOneWarning(output);
		assertThat(output.getAll()).doesNotContain(state);
	}

	@Test
	void forgedCallbackWithoutASessionIsLoggedNotAudited(CapturedOutput output) {
		int tokenRequestsBefore = this.discord.tokenRequests().size();
		long auditBefore = auditCount();

		HttpResponse<String> response = this.flow.callback("code=forged-code-123&state=forged-state-456");

		assertRedirect(response, "/?login=error");
		assertThat(setCookies(response, "SESSION")).isEmpty();
		assertThat(this.discord.tokenRequests()).hasSize(tokenRequestsBefore);
		assertThat(auditCount()).isEqualTo(auditBefore);
		assertOneWarning(output);
		assertThat(output.getAll()).doesNotContain("forged-code-123").doesNotContain("forged-state-456");
	}

	@Test
	void forgedCallbackWithAMismatchedStateIsLoggedNotAudited(CapturedOutput output) {
		this.flow.startAuthorization();
		String preLoginId = this.flow.cookie("SESSION");
		int tokenRequestsBefore = this.discord.tokenRequests().size();
		long auditBefore = auditCount();

		HttpResponse<String> response = this.flow.callback("code=forged-code-123&state=forged-state-456");

		assertRedirect(response, "/?login=error");
		assertNoAuthenticatedSession(preLoginId);
		assertThat(this.discord.tokenRequests()).hasSize(tokenRequestsBefore);
		assertThat(auditCount()).isEqualTo(auditBefore);
		assertOneWarning(output);
		assertThat(output.getAll()).doesNotContain("forged-code-123").doesNotContain("forged-state-456");
	}

	private String callbackQuery(FakeDiscord.User user, LoginFlow.Authorization authorization) {
		return "code=" + encode(this.discord.issueCode(user)) + "&state=" + encode(authorization.params().get("state"));
	}

	/** A redirect to exactly this site-relative target: no host, no session ID in the URL. */
	private static void assertRedirect(HttpResponse<String> response, String target) {
		assertThat(response.statusCode()).isEqualTo(302);
		String location = response.headers().firstValue("Location").orElseThrow();
		assertThat(location).isEqualTo(target).doesNotContainIgnoringCase("jsessionid");
		if (!target.equals("/")) {
			assertThat(queryParams(location).get("login")).isIn("banned", "too_new", "error");
		}
	}

	/**
	 * The pre-login session was invalidated without ever holding a signed-in user, and replaying
	 * every cookie the browser received doesn't sign it in.
	 */
	private void assertNoAuthenticatedSession(String preLoginId) {
		assertThat(preLoginId).isNotNull();
		SessionRecorder.Recorded preLogin = this.sessions.byId(preLoginId).orElseThrow();
		assertThat(preLogin.destroyed()).isTrue();
		assertThat(preLogin.attributeNames()).doesNotContain(SECURITY_CONTEXT);
		this.flow.replayEveryReceivedCookie();
		assertThat(this.flow.get("/api/me").statusCode()).isEqualTo(401);
	}

	private void assertOneWarning(CapturedOutput output) {
		List<String> lines = output.getAll().lines().filter((line) -> line.contains(UNIDENTIFIED_FAILURE_LOG)).toList();
		assertThat(lines).singleElement().satisfies((line) -> assertThat(line).contains("WARN"));
	}

	/** This account's audit rows, oldest first, with null columns left out. */
	private List<Map<String, Object>> auditRows(long discordId) {
		return this.jdbc.sql("select outcome, reason from login_audit where discord_id = ? order by id")
			.param(discordId)
			.query()
			.listOfRows()
			.stream()
			.map((row) -> {
				Map<String, Object> present = new java.util.LinkedHashMap<>();
				row.forEach((column, value) -> {
					if (value != null) {
						present.put(column, value);
					}
				});
				return present;
			})
			.toList();
	}

	private long auditCount() {
		return this.jdbc.sql("select count(*) from login_audit").query(Long.class).single();
	}

}
