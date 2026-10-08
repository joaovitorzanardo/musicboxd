package com.musicboxd.api.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSender;

/**
 * Spring Cloud AWS supplies the production MailSender (SES API, instance-role credentials). Its bean does
 * not back off for ours, so the dev logger exists only when SES auto-configuration is switched off.
 */
@Configuration
class MailConfig {

	@Bean
	@ConditionalOnProperty(name = "spring.cloud.aws.ses.enabled", havingValue = "false")
	MailSender loggingMailSender() {
		return new LoggingMailSender();
	}
}
