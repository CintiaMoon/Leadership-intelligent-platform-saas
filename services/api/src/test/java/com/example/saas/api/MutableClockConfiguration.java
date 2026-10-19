package com.example.saas.api;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application clock with one the test controls. Importing it gives a test its own Spring context. */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfiguration {

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock(Instant.now());
	}

}
