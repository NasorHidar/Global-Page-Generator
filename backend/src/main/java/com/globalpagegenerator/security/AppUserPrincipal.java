package com.globalpagegenerator.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security principal that wraps the authenticated application {@link com.globalpagegenerator.persistence.entity.User}.
 *
 * <p>This is what {@code @AuthenticationPrincipal} resolves to in controllers.
 * Stored in the {@link org.springframework.security.core.context.SecurityContextHolder}
 * for the lifetime of a single request (stateless — no session).
 *
 * <p>We deliberately carry only the information the rest of the request lifecycle
 * needs: the stable {@code userId} login string and the opaque {@code securityToken}
 * used to hydrate upstream API calls.
 *
 * @param userId        the login ID (e.g., "operator1") — maps to {@code User.user_id}
 * @param securityToken opaque token persisted on the User row; injected into
 *                      upstream API payloads via the {@code {{token}}} placeholder
 */
public record AppUserPrincipal(
        String userId,
        String securityToken
) implements UserDetails {

    // ── UserDetails contract ───────────────────────────────────────────────────

    @Override
    public String getUsername() { return userId; }

    /**
     * Password is not stored here — authentication is token-based.
     * Returning an empty string prevents NPEs in Spring Security internals.
     */
    @Override
    public String getPassword() { return ""; }

    /**
     * No role-based access control in v1. All authenticated requests are
     * treated equally — a ROLE_USER authority satisfies any authenticated() rule.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(() -> "ROLE_USER");
    }

    @Override public boolean isAccountNonExpired()  { return true; }
    @Override public boolean isAccountNonLocked()   { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()            { return true; }
}
