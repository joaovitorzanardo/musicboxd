package com.musicboxd.api.mail;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Import next to TestcontainersConfiguration to read the emails the api "sends". */
@TestConfiguration(proxyBeanMethods = false)
public class MailTestConfiguration {

	@Bean
	@Primary
	RecordingMailSender recordingMailSender() {
		return new RecordingMailSender();
	}
}
