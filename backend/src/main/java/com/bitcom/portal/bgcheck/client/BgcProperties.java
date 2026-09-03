package com.bitcom.portal.bgcheck.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** N3 수치. 값은 application.yml → MEASUREMENTS.md 실측값으로 확정. */
@ConfigurationProperties(prefix = "bgcheck")
public record BgcProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        int postMaxAttempts,
        int postRetryBackoffMs,
        int pollIntervalMs,
        int pollMaxElapsedSeconds,
        int pollMaxAttempts
) {}
