/**
 * Shared: cross-cutting building blocks every module may use. UUIDv7 IDs ({@code id}),
 * JSON settings ({@code json}), errors and ProblemDetail ({@code error}), web infrastructure
 * such as the request ID ({@code web}), typed configuration ({@code config}), the job queue
 * ({@code jobs}, owns the {@code job} table), and the TenantContext / CurrentUser /
 * TenantScope / Permission interfaces that {@code security} implements.
 *
 * <p>Depends on no other module.
 */
package com.reconflow.paymentreconflow.shared;
