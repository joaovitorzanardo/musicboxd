package com.musicboxd.api.accounts;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.musicboxd.api.security.OpenApiConfig;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/** The caller's own account: MBD-17's protected endpoint, and what the SPA (MBD-22) calls after login. */
@RestController
public class AccountController {

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	@GetMapping("/api/v1/accounts/me")
	@SecurityRequirement(name = OpenApiConfig.BEARER)
	@ApiResponse(responseCode = "200", description = "The authenticated caller's account")
	@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token")
	public AccountView me(@AuthenticationPrincipal Jwt jwt) {
		return accounts.describe(UUID.fromString(jwt.getSubject()));
	}
}
