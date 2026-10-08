package com.musicboxd.api.accounts;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param ttl refresh-token lifetime; sliding, each rotation starts a new one
 * @param grace how long a rotated-out token still refreshes (parallel tabs, lost responses; AD-8)
 */
@ConfigurationProperties("musicboxd.auth.refresh-token")
public record RefreshTokenProperties(@DefaultValue("30d") Duration ttl, @DefaultValue("10s") Duration grace) {
}
