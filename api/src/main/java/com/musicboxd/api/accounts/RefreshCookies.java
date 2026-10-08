package com.musicboxd.api.accounts;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/**
 * The refresh cookie (AD-8): HttpOnly so scripts cannot read it, SameSite=Lax so cross-site POSTs do not
 * carry it, and sent only to the refresh path (MBD-20's logout is DELETE on the same path).
 */
final class RefreshCookies {

	static final String NAME = "musicboxd_refresh";
	static final String PATH = "/api/v1/auth/refresh";

	private RefreshCookies() {
	}

	static ResponseCookie issue(String rawToken, Duration maxAge) {
		return base(rawToken).maxAge(maxAge).build();
	}

	static ResponseCookie clear() {
		return base("").maxAge(0).build();
	}

	private static ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Lax").path(PATH);
	}
}
