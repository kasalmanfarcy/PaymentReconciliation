package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A JSON Merge Patch (RFC 7396) of a case: fields present are set, absent ones are left alone, and
 * {@code null} clears. Read from a {@link JsonNode}, not a typed DTO, because a record with nullable
 * fields can't tell {@code {"assignedTo": null}} (unassign) from {@code {}} (leave it).
 *
 * @param hasAssignee whether {@code assignedTo} was present
 * @param assignee the new assignee, or null to unassign
 * @param status the target status, OPEN or IN_REVIEW, or null to leave it
 * @param reason why (required for IN_REVIEW → OPEN)
 */
public record ExceptionPatch(
        boolean hasAssignee,
        @Nullable UUID assignee,
        @Nullable ExceptionStatus status,
        @Nullable String reason) {

    static final Set<String> FIELDS = Set.of("assignedTo", "status", "reason");

    public static ExceptionPatch from(@Nullable JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ValidationFailedException("body", "must-be-object", "A merge patch is a JSON object.");
        }
        for (String name : body.propertyNames()) {
            if (!FIELDS.contains(name)) {
                throw new ValidationFailedException(
                        name, "unknown-field", "Only assignedTo, status and reason can be patched.");
            }
        }
        // {} would bump nothing and answer 200, hiding a client bug.
        if (!body.has("assignedTo") && !body.has("status")) {
            throw new ValidationFailedException(
                    "body", "nothing-to-update", "Send assignedTo and/or status; reason only goes with status.");
        }
        UUID assignee = body.has("assignedTo") ? uuidOrNull(body.get("assignedTo")) : null;
        ExceptionStatus status = body.has("status") ? status(body.get("status")) : null;
        String reason = body.has("reason") ? stringOrNull(body.get("reason"), "reason") : null;
        return new ExceptionPatch(body.has("assignedTo"), assignee, status, reason);
    }

    private static @Nullable UUID uuidOrNull(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(stringOrNull(node, "assignedTo"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationFailedException("assignedTo", "invalid-uuid", "assignedTo is a user id or null.");
        }
    }

    private static ExceptionStatus status(JsonNode node) {
        String raw = stringOrNull(node, "status");
        if (ExceptionStatus.RESOLVED.name().equals(raw)) {
            // Resolving needs a code, maybe a note, and its own permission: its own endpoint.
            throw new ValidationFailedException(
                    "status", "use-resolve-endpoint", "To resolve a case, POST /api/v1/exceptions/{id}/resolve.");
        }
        if (ExceptionStatus.OPEN.name().equals(raw)
                || ExceptionStatus.IN_REVIEW.name().equals(raw)) {
            return ExceptionStatus.valueOf(raw);
        }
        throw new ValidationFailedException("status", "invalid-status", "status is OPEN or IN_REVIEW.");
    }

    private static @Nullable String stringOrNull(JsonNode node, String field) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new ValidationFailedException(field, "must-be-string", field + " must be a string.");
        }
        return node.stringValue();
    }
}
