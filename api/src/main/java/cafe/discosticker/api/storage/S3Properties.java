package cafe.discosticker.api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Object storage settings. Locally these point at SeaweedFS; in production, at S3.
 * Missing values fail startup rather than falling back to anonymous access.
 */
@Validated
@ConfigurationProperties("app.s3")
public record S3Properties(
		@NotBlank String endpoint,
		@NotBlank String region,
		@NotBlank String accessKey,
		@NotBlank String secretKey,
		@NotBlank String bucket,
		boolean pathStyleAccess) {
}
