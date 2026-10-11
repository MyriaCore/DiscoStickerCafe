package cafe.discosticker.api.stickers;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cafe.discosticker.api.stickers.image.DecodeLimiter;
import cafe.discosticker.api.stickers.image.StickerImages;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UploadProperties.class)
class StickersConfig {

	@Bean
	DecodeLimiter decodeLimiter(UploadProperties upload) {
		return new DecodeLimiter(upload.decodePermits(), upload.decodeWait());
	}

	/** Creating the pipeline also turns off ImageIO's temp-file cache, at startup. */
	@Bean
	StickerImages stickerImages(UploadProperties upload, DecodeLimiter decodeLimiter) {
		return new StickerImages(upload.stickerSize(), upload.maxSide(), upload.maxPixels(), decodeLimiter);
	}

}
