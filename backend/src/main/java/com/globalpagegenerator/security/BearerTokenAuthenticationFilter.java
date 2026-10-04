package com.globalpagegenerator.security;

import com.globalpagegenerator.persistence.entity.User;
import com.globalpagegenerator.persistence.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that runs <em>once per request</em> and populates the
 * {@link SecurityContextHolder} with an {@link AppUserPrincipal}.
 *
 * <h2>Authentication protocol</h2>
 * <p>Clients send their token in the standard HTTP Authorization header:
 * <pre>Authorization: Bearer &lt;security_token&gt;</pre>
 *
 * <p>The token is looked up directly against {@code User.security_token}
 * in the database. This is appropriate for an internal, firewalled application
 * where tokens are pre-issued and stored by an IdP. For a public API, replace
 * this lookup with a JWT signature verification step.
 *
 * <h2>Why {@link OncePerRequestFilter}?</h2>
 * <p>Spring's filter chain can invoke filters multiple times on a single
 * logical request (e.g., after an async dispatch). {@link OncePerRequestFilter}
 * guarantees exactly-once execution per HTTP exchange.
 *
 * <h2>Thread-safety</h2>
 * <p>{@link SecurityContextHolder} is thread-local by default — no shared
 * mutable state between concurrent requests.
 */
@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserRepository userRepository;

    public BearerTokenAuthenticationFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         filterChain
    ) throws ServletException, IOException {

        String rawHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (!StringUtils.hasText(rawHeader) || !rawHeader.startsWith(BEARER_PREFIX)) {
            // No token present — Spring Security will reject the request if the
            // endpoint requires authentication (via SecurityConfig rules).
            filterChain.doFilter(request, response);
            return;
        }

        String token = rawHeader.substring(BEARER_PREFIX.length()).strip();

        // Skip if the SecurityContext is already populated (e.g., re-entry in the chain)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        userRepository.findBySecurityToken(token).ifPresentOrElse(
                user -> authenticate(user, request),
                ()   -> LOG.warn("Received unknown Bearer token — request will be rejected")
        );

        filterChain.doFilter(request, response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void authenticate(User user, HttpServletRequest request) {
        AppUserPrincipal principal = new AppUserPrincipal(
                user.getUserId(),
                user.getSecurityToken()
        );

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,                        // credentials — not needed post-authentication
                        principal.getAuthorities()
                );
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authToken);
        LOG.debug("Authenticated request for user [{}]", user.getUserId());
    }
}
