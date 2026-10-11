package cafe.discosticker.api.stickers;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cafe.discosticker.api.stickers.image.ImageType;

/** The limits the upload form shows, from the same settings the upload checks enforce. Signed in only. */
@RestController
class StickerLimitsController {

	record Limits(List<String> acceptedTypes, long maxBytes, int uploadsPerHour, int stickerSize) {
	}

	private final Limits limits;

	StickerLimitsController(UploadProperties upload) {
		this.limits = new Limits(Arrays.stream(ImageType.values()).map(ImageType::mediaType).toList(),
				upload.maxFileSize().toBytes(), upload.uploadsPerHour(), upload.stickerSize());
	}

	@GetMapping("/api/stickers/limits")
	Limits limits() {
		return this.limits;
	}

}
