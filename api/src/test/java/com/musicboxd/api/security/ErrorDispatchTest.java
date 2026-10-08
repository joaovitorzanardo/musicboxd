package com.musicboxd.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import com.musicboxd.api.TestcontainersConfiguration;

/** On a real server, the container's ERROR dispatch must not turn a 404 into a 401. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ErrorDispatchTest {

	@LocalServerPort
	private int port;

	@Test
	void unknownPublicRouteIs404NotMaskedAs401() {
		HttpStatus status = RestClient.create("http://localhost:" + port).get()
			.uri("/api/v1/no-such-route")
			.exchange((request, response) -> HttpStatus.valueOf(response.getStatusCode().value()));
		assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
	}
}
