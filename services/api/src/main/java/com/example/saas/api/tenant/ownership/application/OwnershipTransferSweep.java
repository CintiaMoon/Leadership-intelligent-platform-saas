package com.example.saas.api.tenant.ownership.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks overdue pending transfers EXPIRED and queues the expiry emails (D-64, D-184). Acceptance, cancellation and reads
 * already treat an overdue transfer as expired, so the sweep only makes the stored state, the audit trail and the emails
 * catch up. It lists candidates as plain values without locks, then expires each one in its own short transaction that
 * locks the company row first and re-checks, so it keeps the lock order of every other transfer operation and two
 * instances running it at once cannot expire a transfer twice. A transfer whose lock is not granted is left for the next
 * run.
 */
@Component
public class OwnershipTransferSweep {

	private static final Logger log = LoggerFactory.getLogger(OwnershipTransferSweep.class);

	static final int BATCH = 100;

	private final JdbcClient jdbc;

	private final OwnershipTransferTransactions transactions;

	private final Clock clock;

	OwnershipTransferSweep(JdbcClient jdbc, OwnershipTransferTransactions transactions, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.ownership.sweep-interval:1h}",
			initialDelayString = "${app.ownership.sweep-interval:1h}")
	void scheduled() {
		try {
			int expired = runOnce();
			if (expired > 0) {
				log.info("Ownership transfer sweep expired {} transfers", expired);
			}
		}
		catch (RuntimeException ex) {
			// Retry at the next run; never take the scheduler thread down.
			log.warn("Ownership transfer sweep failed: {}", ex.getClass().getName());
		}
	}

	/** @return how many transfers this run expired */
	public int runOnce() {
		List<UUID[]> overdue = jdbc
			.sql("SELECT company_id, id FROM ownership_transfer WHERE status = 'PENDING' AND expires_at <= :now "
					+ "ORDER BY expires_at LIMIT :batch")
			.param("now", Timestamp.from(clock.instant()))
			.param("batch", BATCH)
			.query((rows, n) -> new UUID[] { rows.getObject(1, UUID.class), rows.getObject(2, UUID.class) })
			.list();
		int expired = 0;
		for (UUID[] ids : overdue) {
			try {
				if (transactions.expireOne(ids[0], ids[1])) {
					expired++;
				}
			}
			catch (RuntimeException ex) {
				// A lock that was not granted in time, for example: try again at the next run.
				log.warn("Ownership transfer {} not expired this run: {}", ids[1], ex.getClass().getSimpleName());
			}
		}
		return expired;
	}

}
