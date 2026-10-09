package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.musicboxd.api.TestcontainersConfiguration;

/** A policy a controller names but the config lacks is a 500 at request time; catch it here. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateLimitPoliciesTest {

	@Autowired
	private RateLimitProperties properties;

	@Test
	void everyAuthPolicyIsConfigured() {
		assertThat(properties.policies()).containsKeys(
			"login-per-ip", "login-per-email",
			"register-per-ip", "register-per-email",
			"verification-email-per-ip", "verification-email-per-email");
		assertThat(properties.policies()).doesNotContainKey("verification-email");
	}
}
