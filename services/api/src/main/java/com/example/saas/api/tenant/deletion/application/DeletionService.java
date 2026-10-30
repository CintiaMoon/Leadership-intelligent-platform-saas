package com.example.saas.api.tenant.deletion.application;

import java.time.Clock;
import java.util.UUID;

import com.example.saas.api.ratelimit.RateLimitStoreUnavailableException;
import com.example.saas.api.ratelimit.RateLimiter;
import com.example.saas.api.security.AuthenticatedUser;
import com.example.saas.api.security.RecentAuthentication;
import com.example.saas.api.tenant.application.CompanyAuthorizationService;
import com.example.saas.api.tenant.domain.Permission;
import org.springframework.stereotype.Service;

import static com.example.saas.api.auth.application.AuthRateLimits.requireWithin;

/**
 * Requesting and cancelling the deletion of a company (SPEC-DEL). This class does everything that must happen
 * <em>before</em> the company lock: it answers an outsider with 404 and a member who is not the Owner with 403
 * (AC-DEL-01), requires a login or step-up within 10 minutes for a request but not for a cancellation (AC-DEL-13,
 * AC-DEL-17), and applies the limits (AC-DEL-28). The changes themselves are in {@link DeletionTransactions}, which
 * decides the state, the role and the deadline again under the lock.
 */
@Service
public class DeletionService {

	private final CompanyAuthorizationService authorization;

	private final RecentAuthentication recentAuthentication;

	private final RateLimiter rateLimiter;

	private final DeletionTransactions transactions;

	private final Clock clock;

	DeletionService(CompanyAuthorizationService authorization, RecentAuthentication recentAuthentication,
			RateLimiter rateLimiter, DeletionTransactions transactions, Clock clock) {
		this.authorization = authorization;
		this.recentAuthentication = recentAuthentication;
		this.rateLimiter = rateLimiter;
		this.transactions = transactions;
		this.clock = clock;
	}

	/**
	 * Only an ACTIVE Owner, with a recent authentication. Cancels a pending ownership transfer, records the request and the
	 * deadline, audits it and queues the emails.
	 *
	 * @throws DeletionConflictException the company is not ACTIVE or is flagged for ownership resolution (409)
	 */
	public DeletionView request(AuthenticatedUser actor, UUID companyId) {
		authorization.require(actor.userId(), companyId, Permission.REQUEST_COMPANY_DELETION);
		recentAuthentication.require(actor);
		requireWithinLimits(actor, companyId);
		DeletionView view = transactions.request(actor, companyId);
		countHit(actor, companyId);
		return view;
	}

	/**
	 * Only an ACTIVE Owner, and no recent authentication (D-37). Restores ACTIVE while the deletion is pending and its
	 * deadline has not passed.
	 *
	 * @throws DeletionConflictException the deletion is not pending any more or its deadline has passed (409)
	 */
	public DeletionView cancel(AuthenticatedUser actor, UUID companyId) {
		authorization.require(actor.userId(), companyId, Permission.CANCEL_COMPANY_DELETION);
		requireWithinLimits(actor, companyId);
		DeletionView view = transactions.cancel(actor, companyId);
		countHit(actor, companyId);
		return view;
	}

	private void requireWithinLimits(AuthenticatedUser actor, UUID companyId) {
		requireWithin(rateLimiter.current(DeletionRateLimits.PER_USER, actor.userId().toString()), clock);
		requireWithin(rateLimiter.current(DeletionRateLimits.PER_COMPANY, companyId.toString()), clock);
	}

	/**
	 * Counts the operation after it committed. If the counter store fails here the change already exists, so the failure is
	 * not turned into an error response; the next request's read fails closed.
	 */
	private void countHit(AuthenticatedUser actor, UUID companyId) {
		try {
			rateLimiter.hit(DeletionRateLimits.PER_USER, actor.userId().toString());
			rateLimiter.hit(DeletionRateLimits.PER_COMPANY, companyId.toString());
		}
		catch (RateLimitStoreUnavailableException unavailable) {
			// Fail closed applies to the next request.
		}
	}

}
