package com.example.saas.api.ratelimit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A small dedicated connection pool for counter writes (ADR-004), so a login flood can exhaust at most
 * this pool and never the pool that serves business traffic. It is deliberately not exposed as a
 * {@code DataSource} bean: that would switch off the primary datasource auto-configuration.
 */
public final class RateLimitStore implements AutoCloseable {

	private final HikariDataSource pool;

	private final JdbcTemplate jdbc;

	public RateLimitStore(String url, String username, String password, RateLimitProperties properties) {
		HikariConfig config = new HikariConfig();
		config.setPoolName("rate-limit");
		config.setJdbcUrl(url);
		config.setUsername(username);
		config.setPassword(password);
		config.setMaximumPoolSize(properties.poolSize());
		config.setMinimumIdle(0);
		config.setConnectionTimeout(properties.connectionTimeout().toMillis());
		// Do not fail application start when the database is briefly down; counters then fail closed.
		config.setInitializationFailTimeout(-1);
		this.pool = new HikariDataSource(config);
		this.jdbc = new JdbcTemplate(pool);
		this.jdbc.setQueryTimeout((int) Math.max(1, properties.queryTimeout().toSeconds()));
	}

	JdbcTemplate jdbc() {
		return jdbc;
	}

	@Override
	public void close() {
		pool.close();
	}

}
