package com.example.saas.api.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-83: the session tables come from our Flyway migration, not from the library. If an upgrade of Spring Session
 * changes its PostgreSQL schema, this fails so the migration is revisited on purpose instead of drifting silently.
 */
class SessionSchemaDriftTest {

	@Test
	void ourMigrationMatchesTheSchemaShippedWithTheLibrary() throws IOException {
		String library = normalize(read("org/springframework/session/jdbc/schema-postgresql.sql"));
		String migration = normalize(read("db/migration/V6__create_spring_session.sql"));

		assertThat(library).isNotEmpty().contains("create table spring_session");
		assertThat(migration).isEqualTo(library);
	}

	private static String read(String path) throws IOException {
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Drops comments, case, and whitespace differences. */
	private static String normalize(String sql) {
		StringBuilder out = new StringBuilder();
		for (String line : sql.split("\\R")) {
			String trimmed = line.strip();
			if (!trimmed.startsWith("--")) {
				out.append(trimmed).append(' ');
			}
		}
		return out.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

}
