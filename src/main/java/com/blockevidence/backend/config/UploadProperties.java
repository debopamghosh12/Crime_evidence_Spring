package com.blockevidence.backend.config;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * B2 upload policy. The size limit is enforced earlier, by the servlet container, through
 * {@code spring.servlet.multipart.max-file-size} (see application.yml); this holds the type allow-list.
 *
 * <p>Limitation: the content type is what the CLIENT declares. It stops honest mistakes and casual
 * misuse but does not inspect the bytes, so it is not a security boundary. application/octet-stream
 * (forensic disk images) is deliberately not in the default list; add it via configuration if needed.
 */
@Validated
@ConfigurationProperties("blockevidence.upload")
public record UploadProperties(
        @NotEmpty @DefaultValue({
                "image/jpeg", "image/png", "image/gif", "image/webp", "image/tiff",
                "application/pdf", "text/plain",
                "audio/mpeg", "audio/wav", "audio/x-wav",
                "video/mp4", "video/quicktime",
                "application/zip"
        }) List<String> allowedContentTypes) {
}
