package com.example.saas.api.plan;

import java.sql.PreparedStatement;
import java.sql.Statement;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests move a company through time by rewriting its trial dates. V16 refuses that for every statement (the dates never
 * change once set), so this helper switches the user triggers off for the one statement only, as a superuser can
 * ({@code session_replication_role = replica}), and always switches them back on. Nothing else in a test should use it.
 */
public final class TrialDateRewrite {

	private TrialDateRewrite() {
	}

	/** Runs one UPDATE of {@code company_subscription} with the triggers off, on the connection of the current transaction. */
	public static int update(JdbcTemplate jdbc, String sql, Object... args) {
		return jdbc.execute((ConnectionCallback<Integer>) connection -> {
			try (Statement set = connection.createStatement()) {
				set.execute("SET session_replication_role = replica");
			}
			try (PreparedStatement update = connection.prepareStatement(sql)) {
				for (int i = 0; i < args.length; i++) {
					update.setObject(i + 1, args[i]);
				}
				return update.executeUpdate();
			}
			finally {
				try (Statement reset = connection.createStatement()) {
					reset.execute("RESET session_replication_role");
				}
			}
		});
	}

}
