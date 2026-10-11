package cafe.discosticker.api.stickers;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Upload limits: the one source for what the upload checks enforce and what
 * {@code GET /api/stickers/limits} reports to the form. Spring's multipart file limit reads
 * {@code max-file-size} through a placeholder in {@code application.yml}.
 *
 * @param stickerSize the side of the square box larger images are shrunk to fit, in pixels
 * @param maxFileSize the largest accepted file (binary units: 10MB is 10,485,760 bytes)
 * @param uploadsPerHour accepted uploads allowed per user in any 60 minutes
 * @param maxSide the largest width or height read from an image's header
 * @param maxPixels the largest width × height read from an image's header
 * @param decodePermits images decoded at the same time
 * @param decodeWait how long an upload waits for a decode permit before it is rejected as busy
 */
@Validated
@ConfigurationProperties("app.upload")
public record UploadProperties(
		@Positive int stickerSize,
		@NotNull DataSize maxFileSize,
		@Positive int uploadsPerHour,
		@Positive int maxSide,
		@Positive long maxPixels,
		@Positive int decodePermits,
		@NotNull Duration decodeWait) {
}
