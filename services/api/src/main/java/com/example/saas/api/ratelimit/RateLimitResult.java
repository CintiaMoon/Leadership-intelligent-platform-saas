package com.example.saas.api.ratelimit;

import java.time.Instant;

/** Outcome of one hit. {@code count} includes this hit; {@code resetsAt} is the end of the window. */
public record RateLimitResult(boolean allowed, int count, int limit, Instant resetsAt) {
}
