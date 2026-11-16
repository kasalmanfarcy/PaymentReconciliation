package com.reconflow.paymentreconflow.tenancy;

import java.util.List;

/**
 * Implemented by one {@code @Component} per module, in that module's test sources, next to its
 * other tests. CrossTenantIT runs every registered case; CrossTenantCoverageIT fails the build when
 * an endpoint has no case and isn't explicitly exempt.
 */
public interface CrossTenantCases {

    List<CrossTenantCase> cases();
}
