package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Member reads, always for one organization: every query has {@code organization_id = :org} and the
 * organization comes from the caller's TenantContext, never from the request.
 */
@Component
public class MemberQueries {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    /**
     * Sort fields are whitelisted and mapped to columns here (api-design.md §4): user input never
     * reaches SQL as a column name.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdAt", "m.created_at",
            "email", "lower(u.email)",
            "displayName", "lower(u.display_name)",
            "role", "m.role");

    private static final String SELECT = """
            SELECT u.id, u.email, u.display_name, m.role, u.status, m.created_at
              FROM membership m
              JOIN app_user u ON u.id = m.user_id
             WHERE m.organization_id = :org
            """;

    private final JdbcClient jdbc;

    MemberQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MemberView> find(UUID organizationId, UUID userId) {
        return jdbc.sql(SELECT + " AND m.user_id = :userId")
                .param("org", organizationId)
                .param("userId", userId)
                .query(MemberQueries::map)
                .optional();
    }

    /** Page {@code page} (0-based) of {@code size}, clamped to 200; default order newest first. */
    public Page<MemberView> list(UUID organizationId, int page, int size, List<String> sort) {
        int pageNumber = Math.max(page, 0);
        int pageSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE); // larger is clamped, not rejected
        String orderBy = orderBy(sort);
        List<MemberView> content = jdbc.sql(SELECT + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset")
                .param("org", organizationId)
                .param("limit", pageSize)
                .param("offset", (long) pageNumber * pageSize)
                .query(MemberQueries::map)
                .list();
        long total = jdbc.sql("SELECT count(*) FROM membership WHERE organization_id = :org")
                .param("org", organizationId)
                .query(Long.class)
                .single();
        return new PageImpl<>(content, PageRequest.of(pageNumber, pageSize), total);
    }

    /**
     * {@code sort=createdAt,desc} parameters, repeatable. Spring splits a single {@code List}
     * parameter on commas ({@code [createdAt, desc]}), so the tokens are walked in order: a field,
     * optionally followed by its direction. Always ends with a unique tiebreaker.
     */
    static String orderBy(List<String> sort) {
        List<String> tokens = new ArrayList<>();
        for (String s : sort == null ? List.<String>of() : sort) {
            for (String token : s.split(",")) {
                if (!token.isBlank()) {
                    tokens.add(token.trim());
                }
            }
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String column = SORT_COLUMNS.get(tokens.get(i));
            if (column == null) {
                throw new ValidationFailedException(
                        "sort",
                        "unsupported-sort-field",
                        "Sortable fields: " + String.join(", ", SORT_COLUMNS.keySet()));
            }
            String direction = "ASC";
            if (i + 1 < tokens.size() && isDirection(tokens.get(i + 1))) {
                direction = tokens.get(++i).toUpperCase(java.util.Locale.ROOT);
            }
            parts.add(column + " " + direction);
        }
        if (parts.isEmpty()) {
            parts.add("m.created_at DESC");
        }
        parts.add("u.id DESC"); // stable pages: no row shifts or repeats between requests
        return String.join(", ", parts);
    }

    private static boolean isDirection(String token) {
        return token.equalsIgnoreCase("asc") || token.equalsIgnoreCase("desc");
    }

    private static MemberView map(ResultSet rs, int n) throws SQLException {
        return new MemberView(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                UserStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }
}
