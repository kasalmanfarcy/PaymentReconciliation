package com.reconflow.paymentreconflow.security.idp;

/**
 * The identity provider's user directory, as ReconFlow needs it (M10.1). Keycloak owns passwords,
 * lockout, MFA and sessions; ReconFlow only asks it to create, reset, disable or remove an account.
 * An interface so tests that aren't about Keycloak run against an in-memory fake, and so a different
 * IdP would replace one class.
 *
 * <p>Every call crosses a network boundary and can't join the database transaction. Callers order
 * their work accordingly and compensate on failure (see MemberService).
 */
public interface IdentityProvider {

    /**
     * Creates an account with a temporary password that must be changed at first login.
     *
     * @return the account's subject ({@code sub}) and whether it was created now ({@code false}: an
     *     account with this email already existed in the IdP and was left untouched)
     */
    Account createUser(String email, String displayName, String temporaryPassword);

    /** Sets a temporary password (changed at next login) and ends every session of the account. */
    void resetPassword(String subject, String temporaryPassword);

    /** Disables the account and ends every session: no new tokens, no refresh. */
    void disable(String subject);

    /** Removes an account: the compensating action when the database half of a creation fails. */
    void delete(String subject);

    record Account(String subject, boolean created) {}
}
