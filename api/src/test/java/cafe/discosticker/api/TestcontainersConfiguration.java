package cafe.discosticker.api;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers shared by every integration test. Spring's context cache reuses them across ITs.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final int S3_PORT = 8333;

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
	}

	// Same image tag and `mini` flags as the seaweedfs service in compose.yaml; keep the two in sync.
	@Bean
	GenericContainer<?> seaweedfs() {
		return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.48"))
			.withCommand("mini -dir=/data -webdav=false -admin.ui=false -master.telemetry=false"
					+ " -s3.port.iceberg=0 -s3.port.lance=0 -s3.autoCreateBucket=false")
			// Credentials are generated per run so none appears in source.
			.withEnv("AWS_ACCESS_KEY_ID", randomHex(10))
			.withEnv("AWS_SECRET_ACCESS_KEY", randomHex(20))
			.withExposedPorts(S3_PORT)
			.waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT));
	}

	@Bean
	DynamicPropertyRegistrar s3Properties(GenericContainer<?> seaweedfs) {
		return registry -> {
			registry.add("app.s3.endpoint",
					() -> "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(S3_PORT));
			registry.add("app.s3.region", () -> "us-east-1");
			registry.add("app.s3.access-key", () -> seaweedfs.getEnvMap().get("AWS_ACCESS_KEY_ID"));
			registry.add("app.s3.secret-key", () -> seaweedfs.getEnvMap().get("AWS_SECRET_ACCESS_KEY"));
			registry.add("app.s3.bucket", () -> "stickers");
			registry.add("app.s3.path-style-access", () -> "true");
		};
	}

	private static String randomHex(int bytes) {
		byte[] buf = new byte[bytes];
		new SecureRandom().nextBytes(buf);
		return HexFormat.of().formatHex(buf);
	}

}
