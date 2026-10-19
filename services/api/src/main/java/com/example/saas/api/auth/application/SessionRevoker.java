package com.example.saas.api.auth.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/**
 * Ends every session of a user: used when a password is reset (AC-AUTH-32) and available to operators who
 * disable an account. The next request with any of those sessions is unauthenticated.
 */
@Service
public class SessionRevoker {

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	SessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.sessions = sessions;
	}

	/** @return how many sessions were ended */
	public int revokeAll(UUID userId) {
		Map<String, ? extends Session> found = sessions.findByPrincipalName(userId.toString());
		found.keySet().forEach(sessions::deleteById);
		return found.size();
	}

}
