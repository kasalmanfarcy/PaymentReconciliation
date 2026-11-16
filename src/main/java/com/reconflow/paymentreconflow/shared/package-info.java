/**
 * Shared: cross-cutting building blocks every module may use. UUIDv7 IDs ({@code id}),
 * JSON settings ({@code json}), errors and ProblemDetail ({@code error}), web infrastructure
 * such as the request ID ({@code web}), typed configuration ({@code config}), the job queue
 * ({@code jobs}, owns the {@code job} table), and the TenantContext / CurrentUser /
 * TenantScope / Permission interfaces that {@code security} implements.
 *
 * <p>Depends on no other module.
 */
// OPEN: every sub-package (id, json, error, web, config, jobs) is usable by all modules, instead of
// marking each one a @NamedInterface. Fine for a module of small building blocks. That it depends
// on no other module is checked by ArchitectureRulesTest.
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package com.reconflow.paymentreconflow.shared;

import org.springframework.modulith.ApplicationModule;
