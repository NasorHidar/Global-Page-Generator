package com.globalpagegenerator.web;

import com.globalpagegenerator.security.AppUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test annotation that injects an {@link AppUserPrincipal} into the
 * {@link SecurityContextHolder}, the same way the production
 * {@code BearerTokenAuthenticationFilter} does at runtime.
 *
 * <p>Using a custom annotation (over {@code @WithMockUser}) is necessary because
 * {@code @AuthenticationPrincipal AppUserPrincipal principal} on
 * {@link ExecutionController#execute} requires the resolved principal to
 * <em>be</em> an {@code AppUserPrincipal} — Spring will not perform a
 * conversion from a generic {@code UserDetails}.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockAppUser.Factory.class)
public @interface WithMockAppUser {

    /** Login ID stored in {@link AppUserPrincipal#userId()}. */
    String userId() default "test-operator";

    /** Security token carried in {@link AppUserPrincipal#securityToken()}. */
    String securityToken() default "test-bearer-token";

    /**
     * Factory that converts an {@link WithMockAppUser} annotation into a
     * populated {@link SecurityContext} carrying an {@link AppUserPrincipal}.
     */
    class Factory implements WithSecurityContextFactory<WithMockAppUser> {

        @Override
        public SecurityContext createSecurityContext(WithMockAppUser annotation) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();

            AppUserPrincipal principal = new AppUserPrincipal(
                    annotation.userId(),
                    annotation.securityToken());

            Authentication auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    /* credentials = */ "N/A",
                    /* authorities = */ principal.getAuthorities());

            context.setAuthentication(auth);
            return context;
        }
    }
}