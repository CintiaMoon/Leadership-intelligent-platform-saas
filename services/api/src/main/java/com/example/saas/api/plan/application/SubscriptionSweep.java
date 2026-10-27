package com.example.saas.api.plan.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.example.saas.api.plan.persistence.CompanySubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records the stored transitions of trials whose time has passed (Q-06), hourly. It is idempotent and not needed for
 * correctness (AC-PLAN-10, 11): it only keeps the stored state and the audit trail close to the effective state.
 */
@Component
public class SubscriptionSweep {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionSweep.class);

	static final int BATCH = 100;

	private final CompanySubscriptionRepository subscriptions;

	private final SubscriptionTransactions transactions;

	private final Clock clock;

	SubscriptionSweep(CompanySubscriptionRepository subscriptions, SubscriptionTransactions transactions,
			Clock clock) {
		this.subscriptions = subscriptions;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.plan.sweep-interval:1h}", initialDelayString = "${app.plan.sweep-interval:1h}")
	void scheduled() {
		try {
			int recorded = runOnce();
			if (recorded > 0) {
				log.info("Subscription sweep recorded {} transitions", recorded);
			}
		}
		catch (RuntimeException ex) {
			// Retry at the next run; never take the scheduler thread down.
			log.warn("Subscription sweep failed: {}", ex.getClass().getName());
		}
	}

	/** @return how many transitions this run recorded */
	public int runOnce() {
		List<UUID> due = subscriptions.findDueForTransition(clock.instant(), PageRequest.ofSize(BATCH));
		int recorded = 0;
		for (UUID companyId : due) {
			try {
				recorded += transactions.recordDue(companyId);
			}
			catch (RuntimeException ex) {
				// A lock that was not granted in time, for example: try again at the next run.
				log.warn("Subscription of company {} not updated this run: {}", companyId, ex.getClass().getSimpleName());
			}
		}
		return recorded;
	}

}
