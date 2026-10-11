package cafe.discosticker.api.stickers;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Sticker links ({@code /s/**}) get their own filter chain, ahead of the main one (design D13).
 * They are public and cacheable, so the chain:
 * <ul>
 * <li>has no CSRF filter: only GET and HEAD are served, and no {@code XSRF-TOKEN} cookie is set;</li>
 * <li>is stateless: no session is read, created or touched, even for a signed-in viewer;</li>
 * <li>leaves {@code Cache-Control} to the controller, instead of Spring Security's no-store default.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class StickerServingSecurityConfig {

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain stickerServingFilterChain(HttpSecurity http) {
		http.securityMatcher("/s/**")
			.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll())
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((sessions) -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache((cache) -> cache.requestCache(new NullRequestCache()))
			.headers((headers) -> headers.cacheControl((cache) -> cache.disable()));
		return http.build();
	}

}
