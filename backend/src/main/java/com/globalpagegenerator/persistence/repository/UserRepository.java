package com.globalpagegenerator.persistence.repository;

import com.globalpagegenerator.persistence.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUserId(String userId);

    /**
     * Resolves a raw Bearer token string to a {@link User}.
     * Called on every authenticated request by {@code BearerTokenAuthenticationFilter}.
     */
    Optional<User> findBySecurityToken(String securityToken);

    /**
     * Fetches just the security token without loading the full entity.
     * Used by {@code ExecutionService} to avoid an unnecessary object allocation.
     */
    @Query("SELECT u.securityToken FROM User u WHERE u.id = :id")
    Optional<String> findSecurityTokenById(@Param("id") Long id);
}
