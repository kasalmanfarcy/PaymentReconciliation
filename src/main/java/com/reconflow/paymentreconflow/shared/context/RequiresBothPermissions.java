package com.reconflow.paymentreconflow.shared.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Like {@link RequiresPermission}, for an endpoint that needs two permissions at once (M8): the
 * reconciliation report and the exception export show exception data, so they need
 * {@code EXCEPTION_READ} on top of {@code REPORT_READ} (api-design.md §8.1). Every role holds both
 * today, but the check stays right if a role ever gets one without the other.
 *
 * <p>A second annotation rather than making {@code RequiresPermission.value} an array: Spring
 * Security allows one {@code @PreAuthorize} per method, and the template fills a placeholder
 * with one attribute's value, so "all of these" is spelled out as two attributes.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('{value}') and hasAuthority('{and}')")
public @interface RequiresBothPermissions {

    Permission value();

    Permission and();
}
