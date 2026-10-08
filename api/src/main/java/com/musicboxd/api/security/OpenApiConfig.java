package com.musicboxd.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** Declares the bearer scheme so the generated TypeScript client knows which calls need a token. */
@Configuration
public class OpenApiConfig {

	public static final String BEARER = "bearer";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI().components(new Components().addSecuritySchemes(BEARER,
				new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}
}
