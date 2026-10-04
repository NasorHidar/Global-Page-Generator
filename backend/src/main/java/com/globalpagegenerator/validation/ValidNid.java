package com.globalpagegenerator.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint that asserts a string is a syntactically valid
 * Bangladeshi National ID (NID).
 *
 * <h2>Accepted formats</h2>
 * <ul>
 *   <li><b>Legacy (10 digits)</b> — pre-2006 paper NID cards.</li>
 *   <li><b>Smart (13 digits)</b> — current laminated smart card.</li>
 *   <li><b>New (17 digits)</b> — biometric enrollment number assigned since 2010.</li>
 * </ul>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Length is strictly one of {@code 10}, {@code 13}, or {@code 17}.</li>
 *   <li>Every character is a decimal digit {@code [0-9]}. Whitespace,
 *       dashes, and other punctuation are rejected.</li>
 *   <li>{@code null} values are rejected — pair with {@code @NotBlank} if
 *       a separate "must be present" check is also desired.</li>
 * </ul>
 *
 * <p>This constraint does <em>not</em> validate that the NID is actually
 * registered with the Election Commission — that check happens upstream.
 *
 * <h2>Example</h2>
 * <pre>{@code
 *   public record ExecutionRequest(
 *       @NotNull @Positive Long serviceId,
 *       @ValidNid String nid
 *   ) { }
 * }</pre>
 */
@Documented
@Constraint(validatedBy = NidValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidNid {

    /**
     * Default violation message. May be overridden on a per-annotation basis
     * to support localisation packs.
     */
    String message() default "NID must be 10, 13, or 17 digits (digits only)";

    /** Bean Validation groups — unused for now but required by the spec. */
    Class<?>[] groups() default {};

    /** Bean Validation metadata payload — required by the spec. */
    Class<? extends Payload>[] payload() default {};
}