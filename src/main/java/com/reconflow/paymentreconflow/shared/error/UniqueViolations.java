package com.reconflow.paymentreconflow.shared.error;

import java.sql.SQLException;
import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Which unique constraint a failed insert violated, if any. Used where a race is expected and the
 * constraint is the real guarantee (the duplicate upload of M5.9): the code catches the violation,
 * checks it is <em>that</em> constraint, and answers with a domain error instead of a 500.
 *
 * <p>By SQL state {@code 23505} plus the constraint name PostgreSQL reports in the structured error
 * fields, never by matching message text, which changes between versions and server locales. Any
 * other violation is a real bug and is rethrown by the caller.
 */
public final class UniqueViolations {

    static final String UNIQUE_VIOLATION = "23505";

    private UniqueViolations() {}

    public static Optional<String> constraintName(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql && UNIQUE_VIOLATION.equals(psql.getSQLState())) {
                ServerErrorMessage server = psql.getServerErrorMessage();
                return Optional.ofNullable(server == null ? null : server.getConstraint());
            }
            if (t instanceof SQLException sql && sql.getNextException() != null) {
                Optional<String> next = constraintName(sql.getNextException());
                if (next.isPresent()) {
                    return next;
                }
            }
        }
        return Optional.empty();
    }

    public static boolean isViolationOf(Throwable failure, String constraint) {
        return constraintName(failure).filter(constraint::equals).isPresent();
    }
}
