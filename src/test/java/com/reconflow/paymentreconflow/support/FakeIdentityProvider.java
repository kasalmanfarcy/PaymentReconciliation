package com.reconflow.paymentreconflow.support;

import com.reconflow.paymentreconflow.security.idp.IdentityProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory identity provider for integration tests that aren't about Keycloak (M10.1): it
 * records what the application asked for, so member-management tests can assert "created with a
 * temporary password", "disabled", "deleted after a rollback". KeycloakIT runs the real client
 * against a real Keycloak.
 */
public class FakeIdentityProvider implements IdentityProvider {

    public record FakeAccount(String subject, String email, String displayName, boolean enabled, String password) {}

    private final Map<String, FakeAccount> accounts = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public Account createUser(String email, String displayName, String temporaryPassword) {
        for (FakeAccount existing : accounts.values()) {
            if (existing.email().equalsIgnoreCase(email)) {
                calls.add("create-existing:" + existing.subject());
                return new Account(existing.subject(), false);
            }
        }
        String subject = UUID.randomUUID().toString();
        accounts.put(subject, new FakeAccount(subject, email, displayName, true, temporaryPassword));
        calls.add("create:" + subject);
        return new Account(subject, true);
    }

    @Override
    public void resetPassword(String subject, String temporaryPassword) {
        accounts.computeIfPresent(
                subject,
                (k, a) -> new FakeAccount(a.subject(), a.email(), a.displayName(), a.enabled(), temporaryPassword));
        calls.add("reset:" + subject);
    }

    @Override
    public void disable(String subject) {
        accounts.computeIfPresent(
                subject, (k, a) -> new FakeAccount(a.subject(), a.email(), a.displayName(), false, a.password()));
        calls.add("disable:" + subject);
    }

    @Override
    public void delete(String subject) {
        accounts.remove(subject);
        calls.add("delete:" + subject);
    }

    public FakeAccount account(String subject) {
        return accounts.get(subject);
    }

    public boolean exists(String subject) {
        return accounts.containsKey(subject);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }
}
