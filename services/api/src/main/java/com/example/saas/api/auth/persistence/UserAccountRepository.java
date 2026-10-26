package com.example.saas.api.auth.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	Optional<UserAccount> findByEmailNormalized(String emailNormalized);

	/**
	 * Reads the account holding a shared row lock until the transaction ends, so that an operator disabling it cannot
	 * commit between a check of its status and the change that depends on it (ownership transfer, AC-OWN-08). The
	 * disable procedure updates this row, so it waits for the lock, and this read waits for an update still in flight.
	 */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select u from UserAccount u where u.id = :id")
	Optional<UserAccount> lockSharedById(@Param("id") UUID id);

	/** The per-request check that keeps a session alive: the account is ACTIVE and its email is verified. */
	boolean existsByIdAndStatusAndEmailVerifiedAtIsNotNullAndCredentialsVersion(UUID id, UserAccount.Status status,
			long credentialsVersion);

	/**
	 * Stores a re-hashed password only if the stored hash is still the one that was verified, so an upgrade at login
	 * can never overwrite a password that was reset in the meantime.
	 *
	 * @return 1 if the hash was replaced, 0 if the password had changed
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update UserAccount u set u.passwordHash = :newHash, u.updatedAt = :now "
			+ "where u.id = :id and u.passwordHash = :verifiedHash")
	int upgradePasswordHash(@Param("id") UUID id, @Param("verifiedHash") String verifiedHash,
			@Param("newHash") String newHash, @Param("now") Instant now);

}
