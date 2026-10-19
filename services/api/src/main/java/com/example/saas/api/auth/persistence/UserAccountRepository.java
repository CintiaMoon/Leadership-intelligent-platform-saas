package com.example.saas.api.auth.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	Optional<UserAccount> findByEmailNormalized(String emailNormalized);

	/** The per-request check that keeps a session alive: the account is ACTIVE and its email is verified. */
	boolean existsByIdAndStatusAndEmailVerifiedAtIsNotNull(UUID id, UserAccount.Status status);

}
