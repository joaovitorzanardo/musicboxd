package com.musicboxd.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSender;

import com.musicboxd.api.TestcontainersConfiguration;

import io.awspring.cloud.ses.SimpleEmailServiceMailSender;

/** Exactly one MailSender in each mode; production (the default) gets the SES one. */
class MailConfigTest {

	@Nested
	@SpringBootTest(properties = "spring.cloud.aws.ses.enabled=true")
	@Import(TestcontainersConfiguration.class)
	class SesEnabledByDefaultInProduction {

		@Autowired
		private MailSender mailSender;

		@Test
		void mailGoesThroughSesWithoutNeedingCredentialsAtStartup() {
			assertThat(mailSender).isInstanceOf(SimpleEmailServiceMailSender.class);
		}
	}

	@Nested
	@SpringBootTest
	@Import(TestcontainersConfiguration.class)
	class SesDisabledInDevAndTests {

		@Autowired
		private MailSender mailSender;

		@Test
		void mailIsLoggedInstead() {
			assertThat(mailSender).isInstanceOf(LoggingMailSender.class);
		}
	}
}
