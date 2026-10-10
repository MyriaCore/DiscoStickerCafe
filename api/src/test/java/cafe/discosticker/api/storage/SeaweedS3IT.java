package cafe.discosticker.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.SecureRandom;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import cafe.discosticker.api.TestcontainersConfiguration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeaweedS3IT {

	@Autowired
	S3Client s3;

	@Autowired
	S3Properties props;

	@Test
	void storesAndReadsBackObject() {
		ensureBucket();
		byte[] bytes = new byte[4096];
		new SecureRandom().nextBytes(bytes);

		s3.putObject(b -> b.bucket(props.bucket()).key("round-trip"), RequestBody.fromBytes(bytes));
		byte[] read = s3.getObjectAsBytes(b -> b.bucket(props.bucket()).key("round-trip")).asByteArray();

		assertThat(read).isEqualTo(bytes);
	}

	@Test
	void rejectsAnonymousRequest() {
		ensureBucket();
		try (S3Client anonymous = clientWith(AnonymousCredentialsProvider.create())) {
			assertThatThrownBy(() -> anonymous.getObject(b -> b.bucket(props.bucket()).key("round-trip")))
				.isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
		}
	}

	@Test
	void rejectsWrongSecretKey() {
		ensureBucket();
		var wrongSecret = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey() + "x"));
		try (S3Client wrong = clientWith(wrongSecret)) {
			assertThatThrownBy(() -> wrong.getObject(b -> b.bucket(props.bucket()).key("round-trip")))
				.isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
		}
	}

	@Test
	void doesNotAutoCreateBucket() {
		String missing = "missing-" + UUID.randomUUID();

		assertThatThrownBy(() -> s3.putObject(b -> b.bucket(missing).key("k"), RequestBody.fromString("x")))
			.isInstanceOf(S3Exception.class);
		assertThatThrownBy(() -> s3.headBucket(b -> b.bucket(missing)))
			.isInstanceOf(NoSuchBucketException.class);
	}

	private S3Client clientWith(AwsCredentialsProvider credentials) {
		return S3Client.builder()
			.endpointOverride(URI.create(props.endpoint()))
			.region(Region.of(props.region()))
			.credentialsProvider(credentials)
			.forcePathStyle(true)
			.build();
	}

	private void ensureBucket() {
		try {
			s3.headBucket(b -> b.bucket(props.bucket()));
		}
		catch (NoSuchBucketException e) {
			s3.createBucket(b -> b.bucket(props.bucket()));
		}
	}

}
