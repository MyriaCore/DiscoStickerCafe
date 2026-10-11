package cafe.discosticker.api.login;

import java.io.IOException;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import cafe.discosticker.api.TestcontainersConfiguration;

/**
 * Login test support: the fake Discord endpoints the api is pointed at, and the session
 * recorder. Import together with {@link TestcontainersConfiguration}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LoginTestConfiguration {

	@Bean(destroyMethod = "close")
	FakeDiscord fakeDiscord() throws IOException {
		return new FakeDiscord(TestcontainersConfiguration.DISCORD_CLIENT_ID,
				TestcontainersConfiguration.DISCORD_CLIENT_SECRET);
	}

	@Bean
	DynamicPropertyRegistrar fakeDiscordProperties(FakeDiscord fakeDiscord) {
		return registry -> {
			registry.add("spring.security.oauth2.client.provider.discord.token-uri", fakeDiscord::tokenUri);
			registry.add("spring.security.oauth2.client.provider.discord.user-info-uri", fakeDiscord::userInfoUri);
		};
	}

	@Bean
	SessionRecorder sessionRecorder() {
		return new SessionRecorder();
	}

}
