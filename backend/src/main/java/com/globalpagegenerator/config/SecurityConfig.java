package com.globalpagegenerator.config;

import com.globalpagegenerator.security.BearerTokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration for the Global Page Generator API.
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Stateless:</b> {@code SessionCreationPolicy.STATELESS} — no
 *       {@code HttpSession} is created. Each request must carry its own Bearer token.</li>
 *   <li><b>CSRF disabled:</b> Safe for a REST API consumed by a single-origin SPA
 *       that never uses cookie-based authentication.</li>
 *   <li><b>CORS:</b> Vite dev server ({@code localhost:5173}) is the only allowed
 *       origin in development. Override via environment config in production.</li>
 * </ul>
 *
 * <h2>Authorization rules</h2>
 * <pre>
 *   GET  /api/v1/layout/**    → permitAll   (layout is safe to read without auth)
 *   GET  /api/v1/services     → permitAll   (service list for the UI dropdown)
 *   POST /api/v1/execute      → authenticated (requires valid Bearer token)
 *   All others                → authenticated
 * </pre>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity          // Enables @PreAuthorize / @Secured on service methods
public class SecurityConfig {

    private final BearerTokenAuthenticationFilter bearerTokenFilter;

    public SecurityConfig(BearerTokenAuthenticationFilter bearerTokenFilter) {
        this.bearerTokenFilter = bearerTokenFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ── Disable session-based state ──────────────────────────────────────
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── Disable CSRF (safe: stateless REST API, no cookies) ──────────────
            .csrf(AbstractHttpConfigurer::disable)

            // ── CORS ─────────────────────────────────────────────────────────────
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // ── Authorization rules ──────────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                    // Layout endpoint is intentionally public — reads only DB config,
                    // contains no user PII, and is required before login completes.
                    .requestMatchers(HttpMethod.GET,  "/api/v1/layout/**").permitAll()
                    // Service list is also public — needed to populate the UI dropdown.
                    .requestMatchers(HttpMethod.GET,  "/api/v1/services").permitAll()
                    // Actuator health check (if added later) — no auth needed
                    .requestMatchers(HttpMethod.GET,  "/actuator/health").permitAll()
                    // Everything else (including POST /api/v1/execute) requires auth
                    .anyRequest().authenticated()
            )

            // ── Disable default form login and HTTP Basic ────────────────────────
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)

            // ── Custom 401 entry point ───────────────────────────────────────────
            // Without this, unauthenticated requests receive 403 (the default
            // AccessDeniedHandler). A REST API should return 401 instead so the
            // frontend can distinguish "no credentials" from "credentials valid
            // but lack permission".
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(SecurityConfig.this.unauthorizedEntryPoint()))

            // ── Plug in our Bearer token filter before Spring's default auth filter ─
            .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt encoder bean — used when hashing passwords at registration time.
     * Not used for the token-based authentication flow but required if any
     * service needs to verify {@code User.password_hash}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 401 entry point used when an unauthenticated request reaches a
     * {@code .authenticated()}-protected endpoint.
     *
     * <p>Returns {@code 401 Unauthorized} with an empty body — the frontend's
     * API client inspects the status code to decide whether to redirect to the
     * login screen ({@code 401}) or to display an "access denied" message
     * ({@code 403}).
     */
    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
    }

    /**
     * CORS policy for local development.
     * In production, set {@code allowed-origins} from an environment variable.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:5173",   // Vite dev server
                "http://localhost:4173"    // Vite preview
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);   // No cookies — Bearer token only
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
