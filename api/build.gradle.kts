plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
}

group = "com.musicboxd"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
	// Per-user rate limiting (AD-10): token buckets, held in a bounded per-policy cache.
	implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
	implementation("com.github.ben-manes.caffeine:caffeine")
	// Persistence: plain JDBC, one Flyway instance per module schema (spine: Migrations).
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	// Compile scope, not runtimeOnly: Constraints reads PSQLException's constraint name.
	implementation("org.postgresql:postgresql")
	// BCrypt (AD-8). Only the crypto module: the full security starter arrives with the filter chain in Task 4.
	implementation("org.springframework.security:spring-security-crypto")
	// JWT encode/decode (Nimbus). Brought in alone so Boot's web security auto-config stays off until Task 4.
	implementation("org.springframework.security:spring-security-oauth2-jose")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

openApi {
	apiDocsUrl.set("http://localhost:8080/api/v1/api-docs")
}
