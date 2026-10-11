package cafe.discosticker.api.stickers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

import cafe.discosticker.api.TestcontainersConfiguration;
import cafe.discosticker.api.login.LoginTestConfiguration;

/**
 * The size settings keep their relationships (design D1, D2): Spring's file limit is the upload
 * limit, the request limit leaves 64 KiB for multipart framing, parts are parsed only when the
 * upload controller asks, and Tomcat swallows more than the gateway ever forwards (11 MiB).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, LoginTestConfiguration.class })
class UploadSettingsIT {

	@Autowired
	Environment environment;

	@Autowired
	MultipartProperties multipart;

	@Autowired
	TomcatServerProperties tomcat;

	@Test
	void multipartFileLimitIsTheUploadLimit() {
		DataSize uploadLimit = Binder.get(this.environment)
			.bind("app.upload.max-file-size", DataSize.class)
			.orElse(null);

		assertThat(uploadLimit).isEqualTo(DataSize.ofMegabytes(10));
		assertThat(this.multipart.getMaxFileSize()).isEqualTo(uploadLimit);
	}

	@Test
	void multipartRequestLimitLeaves64KiBForFraming() {
		assertThat(this.multipart.getMaxRequestSize().toBytes())
			.isEqualTo(this.multipart.getMaxFileSize().toBytes() + DataSize.ofKilobytes(64).toBytes());
	}

	@Test
	void multipartBodiesAreParsedLazily() {
		assertThat(this.multipart.isResolveLazily()).isTrue();
	}

	@Test
	void tomcatSwallowsMoreThanTheGatewayForwards() {
		assertThat(this.tomcat.getMaxSwallowSize().toBytes()).isGreaterThanOrEqualTo(DataSize.ofMegabytes(12).toBytes());
	}

}
