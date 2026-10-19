package com.example.saas.api.ratelimit;

import java.sql.SQLException;
import java.time.Clock;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

	/**
	 * The counter pool connects to the same database as the primary pool. Connection details are read from
	 * the primary pool so they also hold when a test container supplies them.
	 */
	@Bean(destroyMethod = "close")
	RateLimitStore rateLimitStore(DataSource primary, RateLimitProperties properties) throws SQLException {
		HikariDataSource hikari = primary.unwrap(HikariDataSource.class);
		return new RateLimitStore(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword(), properties);
	}

	@Bean
	JdbcRateLimiter rateLimiter(RateLimitStore store, RateLimitProperties properties, Clock clock) {
		return new JdbcRateLimiter(store, new KeyHasher(properties.hmacKey()), clock);
	}

	@Bean
	LoginThrottle loginThrottle(RateLimitStore store, RateLimitProperties properties, Clock clock) {
		return new LoginThrottle(store, new KeyHasher(properties.hmacKey()), clock);
	}

}

/** Removes expired counters. Safe to run on every instance: the deletes are idempotent. */
@Component
class RateLimitSweeper {

	private static final Logger log = LoggerFactory.getLogger(RateLimitSweeper.class);

	private final JdbcRateLimiter limiter;

	private final LoginThrottle loginThrottle;

	RateLimitSweeper(JdbcRateLimiter limiter, LoginThrottle loginThrottle) {
		this.limiter = limiter;
		this.loginThrottle = loginThrottle;
	}

	@Scheduled(fixedDelayString = "${app.rate-limit.sweep-interval:5m}", initialDelayString = "${app.rate-limit.sweep-interval:5m}")
	void sweep() {
		try {
			int removed = limiter.sweepExpired() + loginThrottle.sweepExpired();
			if (removed > 0) {
				log.debug("Removed {} expired rate-limit rows", removed);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Rate-limit sweep failed: {}", ex.getClass().getName());
		}
	}

}
