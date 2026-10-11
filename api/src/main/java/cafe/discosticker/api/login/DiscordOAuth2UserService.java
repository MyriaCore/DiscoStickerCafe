package cafe.discosticker.api.login;

import java.util.Map;
import java.util.Optional;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Reads the Discord user, then applies the login rules before Spring Security creates an
 * authenticated session. A rejected user never becomes a principal. An accepted one becomes a
 * principal holding only the Discord ID and display name, not the rest of Discord's user object.
 */
@Service
public class DiscordOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	static final String ID = "id";

	static final String NAME = "name";

	private final OAuth2UserService<OAuth2UserRequest, OAuth2User> discord = new DefaultOAuth2UserService();

	private final UserAccounts accounts;

	private final LoginPolicy policy;

	public DiscordOAuth2UserService(UserAccounts accounts, LoginPolicy policy) {
		this.accounts = accounts;
		this.policy = policy;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest request) {
		OAuth2User discordUser = this.discord.loadUser(request);
		long discordId = discordId(discordUser);

		Optional<RejectionReason> rejection = this.policy.check(discordId, this.accounts.isBanned(discordId));
		if (rejection.isPresent()) {
			throw new LoginRejectedException(discordId, rejection.get());
		}

		Map<String, Object> attributes = Map.of(ID, Long.toString(discordId), NAME, displayName(discordUser));
		return new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, ID);
	}

	private static long discordId(OAuth2User discordUser) {
		Object id = discordUser.getAttribute(ID);
		try {
			return AccountAge.parseId((id != null) ? id.toString() : null);
		}
		catch (IllegalArgumentException ex) {
			throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response"),
					"Discord user ID is not a snowflake", ex);
		}
	}

	/** Discord's global name, or the username for accounts without one. */
	private static String displayName(OAuth2User discordUser) {
		Object globalName = discordUser.getAttribute("global_name");
		if (globalName != null && !globalName.toString().isBlank()) {
			return globalName.toString();
		}
		Object username = discordUser.getAttribute("username");
		return (username != null) ? username.toString() : "";
	}

}
