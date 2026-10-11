package cafe.discosticker.api.login;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

import jakarta.servlet.DispatcherType;

/**
 * Deny by default. Signed-out requests get 401 JSON and are never redirected to a login page;
 * sign-in starts only from the frontend's link to {@code /oauth2/authorization/discord}.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	static final String SIGN_IN_PATH = "/oauth2/authorization/discord";

	private static final byte[] UNAUTHORIZED_BODY = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, DiscordOAuth2UserService userService,
			LoginSuccessHandler successHandler, LoginFailureHandler failureHandler,
			@Value("${server.servlet.session.cookie.name}") String sessionCookieName) {
		http.authorizeHttpRequests((requests) -> requests
			// Boot's error page renders JSON error bodies (404, 500) for permitted requests.
			.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
			.requestMatchers("/api/health", "/s/**").permitAll()
			.requestMatchers("/api/**").authenticated()
			.anyRequest().denyAll())
			.exceptionHandling((exceptions) -> exceptions.authenticationEntryPoint((request, response, ex) -> {
				response.setStatus(HttpStatus.UNAUTHORIZED.value());
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				// Bytes, not getWriter(): the writer would add the servlet default charset (ISO-8859-1).
				response.getOutputStream().write(UNAUTHORIZED_BODY);
			}))
			// Signed-out requests don't create a session to save themselves in, and no saved
			// request can choose where a login lands.
			.requestCache((cache) -> cache.requestCache(new NullRequestCache()))
			// XSRF-TOKEN cookie readable by the page, X-XSRF-TOKEN header on unsafe requests.
			.csrf((csrf) -> csrf.spa())
			.oauth2Login((login) -> login
				// A custom login page turns off Spring's generated one; the entry point above
				// already stops anything from redirecting here on its own.
				.loginPage(SIGN_IN_PATH)
				.userInfoEndpoint((userInfo) -> userInfo.userService(userService))
				.authorizedClientRepository(new DiscardingAuthorizedClientRepository())
				.successHandler(successHandler)
				.failureHandler(failureHandler))
			.logout((logout) -> logout.logoutUrl("/api/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
				.deleteCookies(sessionCookieName));
		return http.build();
	}

}
