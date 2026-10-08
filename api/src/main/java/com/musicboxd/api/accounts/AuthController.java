package com.musicboxd.api.accounts;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.musicboxd.api.ratelimit.RateLimited;

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

		/** Validation sees the stripped email, matching what the service stores. */
		public RegisterRequest {
			email = email == null ? null : email.strip();
		}
	}

	public record LoginRequest(@NotBlank String email, @NotBlank String password) {

		public LoginRequest {
			email = email == null ? null : email.strip();
		}
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	public record ResendVerificationRequest(@NotBlank @Size(max = 254) String email) {

		public ResendVerificationRequest {
			email = email == null ? null : email.strip();
		}
	}

	public record VerificationResponse(String status) {
	}

	private final AccountService accounts;
	private final TokenService tokens;
	private final EmailVerificationService verifications;

	AuthController(AccountService accounts, TokenService tokens, EmailVerificationService verifications) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.verifications = verifications;
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
	@ApiResponse(responseCode = "403",
			description = "Right password, email not verified yet (type urn:musicboxd:problem:email-not-verified)")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		UUID accountId = accounts.authenticate(request.email(), request.password());
		var token = tokens.issue(accountId);
		return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
	}

	/** The emailed link points here. MBD-22 may move the link to an SPA page that calls this same endpoint. */
	@GetMapping("/verify")
	@ApiResponse(responseCode = "200", description = "Email verified (also when the link was already used)")
	@ApiResponse(responseCode = "400", description = "Missing, unknown, replaced or expired token")
	public VerificationResponse verify(@RequestParam @NotBlank @Size(max = 128) String token) {
		verifications.verify(token);
		return new VerificationResponse("verified");
	}

	@PostMapping("/verification-email")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@RateLimited(policy = "verification-email")
	@ApiResponse(responseCode = "202", description = "If an unverified account has this email, a new link was sent")
	@ApiResponse(responseCode = "400", description = "Invalid email")
	@ApiResponse(responseCode = "429", description = "Too many requests from this caller; see Retry-After")
	public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
		verifications.resend(request.email());
	}
}
