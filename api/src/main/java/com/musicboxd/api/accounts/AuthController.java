package com.musicboxd.api.accounts;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.musicboxd.api.ratelimit.RateLimited;
import com.musicboxd.api.ratelimit.RateLimits;

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
	private final RefreshTokenService refreshTokens;
	private final RefreshTokenProperties refreshProps;
	private final EmailVerificationService verifications;
	private final RateLimits rateLimits;

	AuthController(AccountService accounts, TokenService tokens, RefreshTokenService refreshTokens,
			RefreshTokenProperties refreshProps, EmailVerificationService verifications, RateLimits rateLimits) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.refreshTokens = refreshTokens;
		this.refreshProps = refreshProps;
		this.verifications = verifications;
		this.rateLimits = rateLimits;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@RateLimited(policy = "register-per-ip")
	@ApiResponse(responseCode = "201", description = "Account and profile created")
	@ApiResponse(responseCode = "400", description = "Invalid email, password or username")
	@ApiResponse(responseCode = "409", description = "Email or username already taken (type urn:musicboxd:problem:email-taken or urn:musicboxd:problem:username-taken)")
	@ApiResponse(responseCode = "429", description = "Too many sign-ups for this email or from this caller; see Retry-After")
	public AccountView register(@Valid @RequestBody RegisterRequest request) {
		// Before anything is written: a throttled sign-up creates no account and sends no email.
		rateLimits.check("register-per-email", EmailRateLimitKey.of(request.email()));
		return accounts.register(request.email(), request.password(), request.username());
	}

	@PostMapping("/login")
	@RateLimited(policy = "login-per-ip")
	@ApiResponse(responseCode = "200",
			description = "A short-lived bearer access token; the refresh token is set as an HttpOnly cookie")
	@ApiResponse(responseCode = "401", description = "Unknown email or wrong password (indistinguishable)")
	@ApiResponse(responseCode = "403",
			description = "Right password, email not verified yet (type urn:musicboxd:problem:email-not-verified)")
	@ApiResponse(responseCode = "429",
			description = "Too many attempts for this email or from this caller, right password or not; see Retry-After")
	public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
		// Before the password check: a throttled attempt never reaches bcrypt and learns nothing.
		rateLimits.check("login-per-email", EmailRateLimitKey.of(request.email()));
		UUID accountId = accounts.authenticate(request.email(), request.password());
		return withSession(accountId, refreshTokens.issue(accountId));
	}

	/** The browser sends the cookie on its own; the SPA calls this when an access token expires or on page load. */
	@PostMapping("/refresh")
	@ApiResponse(responseCode = "200", description = "A new access token; the refresh cookie is rotated")
	@ApiResponse(responseCode = "401",
			description = "Missing, expired, revoked or reused refresh token (type urn:musicboxd:problem:invalid-refresh-token)")
	public ResponseEntity<TokenResponse> refresh(
			@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidRefreshTokenException();
		}
		var rotation = refreshTokens.rotate(refreshToken);
		return withSession(rotation.accountId(), rotation.refreshToken());
	}

	/** Logout. The cookie is the credential, so it works after the access token expired; it always answers 204. */
	@DeleteMapping("/refresh")
	@ApiResponse(responseCode = "204",
			description = "This session's refresh token is revoked (if there was one) and the cookie is cleared")
	public ResponseEntity<Void> logout(
			@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
		if (refreshToken != null && !refreshToken.isBlank()) {
			refreshTokens.revoke(refreshToken);
		}
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString()).build();
	}

	/** Called by the SPA page the emailed link opens, /verificar-email (MBD-63). */
	@GetMapping("/verify")
	@ApiResponse(responseCode = "200", description = "Email verified (also when the link was already used)")
	@ApiResponse(responseCode = "400", description = "Missing, unknown, replaced or expired token")
	public VerificationResponse verify(@RequestParam @NotBlank @Size(max = 128) String token) {
		verifications.verify(token);
		return new VerificationResponse("verified");
	}

	@PostMapping("/verification-email")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@RateLimited(policy = "verification-email-per-ip")
	@ApiResponse(responseCode = "202", description = "If an unverified account has this email, a new link was sent")
	@ApiResponse(responseCode = "400", description = "Invalid email")
	@ApiResponse(responseCode = "429",
			description = "Too many requests for this email or from this caller (same answer whether or not the account exists); see Retry-After")
	public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
		// Keyed on the address, not the account, so known and unknown emails are throttled alike.
		rateLimits.check("verification-email-per-email", EmailRateLimitKey.of(request.email()));
		verifications.resend(request.email());
	}

	private ResponseEntity<TokenResponse> withSession(UUID accountId, String refreshToken) {
		// The current role, from the database: a promotion or demotion shows up at the next login or refresh.
		var access = tokens.issue(accountId, accounts.roleOf(accountId));
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, RefreshCookies.issue(refreshToken, refreshProps.ttl()).toString())
			.body(new TokenResponse(access.value(), "Bearer", access.expiresInSeconds()));
	}
}
