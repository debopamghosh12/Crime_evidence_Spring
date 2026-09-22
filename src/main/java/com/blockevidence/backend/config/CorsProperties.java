package com.blockevidence.backend.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Frontend integration: the browser-based frontend (frontend/, a separate origin on the Next.js
 * dev server) needs an explicit CORS allow-list to call this API - without it, the browser blocks
 * every cross-origin fetch/axios call before it reaches Spring Security at all. Defaults to the
 * frontend's dev-server origin only; production deployment should override with its real origin(s).
 */
@ConfigurationProperties("blockevidence.cors")
public record CorsProperties(@DefaultValue("http://localhost:3000") List<String> allowedOrigins) {
}
