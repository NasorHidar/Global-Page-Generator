package com.globalpagegenerator.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ConstraintValidator} for {@link ValidNid}.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Reject {@code null} — nullness is delegated to {@code @NotBlank} /
 *       {@code @NotNull} so this validator owns only the <em>format</em> rule.</li>
 *   <li>Confirm length is one of the three allowed values via a
 *       {@code switch} expression — branchless and JIT-friendly.</li>
 *   <li>Confirm every character is a decimal digit using a single-pass
 *       {@link Character#isDigit(char)} scan that also rejects the
 *       "fullwidth" Unicode digits (e.g. ０-９) by additionally checking
 *       {@link Character#getType(char)} is {@code DECIMAL_DIGIT_NUMBER}.</li>
 * </ol>
 *
 * <p>The class is stateless and thread-safe — a single instance is shared
 * across all threads by the Bean Validation engine.
 */
public class NidValidator implements ConstraintValidator<ValidNid, String> {

    /** Set of permitted NID lengths. */
    private static final int[] ALLOWED_LENGTHS = { 10, 13, 17 };

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null is invalid here — @NotBlank on the field handles the "required" case
        if (value == null) {
            return false;
        }

        // Length must be one of the three allowed values
        final int len = value.length();
        boolean lengthOk = false;
        for (int allowed : ALLOWED_LENGTHS) {
            if (len == allowed) {
                lengthOk = true;
                break;
            }
        }
        if (!lengthOk) {
            return false;
        }

        // Every character must be an ASCII decimal digit (0-9).
        // Character.isDigit accepts Unicode digit categories (e.g. Arabic-Indic
        // digits); we add an explicit ASCII-range check to keep the rule strict.
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}