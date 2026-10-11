package cafe.discosticker.api.login;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user, for the frontend. Signed-out requests never get here (401 JSON). */
@RestController
class MeController {

	/** Only the Discord ID (a string, since snowflakes overflow JavaScript numbers) and display name. */
	record Me(String discordId, String displayName) {
	}

	@GetMapping("/api/me")
	Me me(@AuthenticationPrincipal OAuth2User user) {
		return new Me(user.getName(), user.getAttribute(DiscordOAuth2UserService.NAME));
	}

}
