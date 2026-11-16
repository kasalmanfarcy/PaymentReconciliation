package com.reconflow.paymentreconflow.security.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

/** Where an authentication request came from, for its audit row. Never part of a decision. */
public record ClientInfo(String ip, @Nullable String userAgent) {

    private static final int MAX_USER_AGENT = 256; // attacker-controlled; keep the audit row small

    public static ClientInfo of(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent != null && agent.length() > MAX_USER_AGENT) {
            agent = agent.substring(0, MAX_USER_AGENT);
        }
        return new ClientInfo(request.getRemoteAddr(), agent);
    }

    public static ClientInfo unknown() {
        return new ClientInfo("unknown", null);
    }
}
