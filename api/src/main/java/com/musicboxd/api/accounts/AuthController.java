package com.musicboxd.api.accounts;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	/** Password min 8 chars; username 3-20 of [A-Za-z0-9_] (user decision, 2026-09-27). */
	public record RegisterRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotNull @Size(min = 8, message = "must be at least 8 characters") String password,
			@NotNull @Pattern(regexp = "[A-Za-z0-9_]{3,20}",
					message = "must be 3-20 letters, digits or underscores") String username) {
	}

	public record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	private final AccountService accounts;
	private final TokenService tokens;

	AuthController(AccountService accounts, TokenService tokens) {
		this.accounts = accounts;
		this.tokens = tokens;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@ApiResponse(responseCode = "201", description = "Account and profile created")
	@ApiResponse(responseCode = "400", description = "Invalid email, password or username")
	@ApiResponse(responseCode = "409", description = "Email or username already taken")
	public AccountView register(@Valid @RequestBody RegisterRequest request) {
		return accounts.register(request.email(), request.password(), request.username());
	}

	@PostMapping("/login")
	@ApiResponse(responseCode = "200", description = "A short-lived bearer access token")
	@ApiResponse(responseCode = "401", description = "Unknown email or wrong password (indistinguishable)")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		UUID accountId = accounts.authenticate(request.email(), request.password());
		var token = tokens.issue(accountId);
		return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
	}
}
