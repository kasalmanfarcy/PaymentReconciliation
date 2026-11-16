package com.reconflow.paymentreconflow.shared.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * The permission a controller method needs: {@code @RequiresPermission(Permission.MEMBER_MANAGE)}.
 * A meta-annotation over {@code @PreAuthorize("hasAuthority('{value}')")}, so call sites name a
 * typed {@link Permission}, never a role and never a hand-written SpEL string. Security's
 * AnnotationTemplateExpressionDefaults bean fills in {@code {value}}.
 *
 * <p>{@code hasAuthority}, not {@code hasRole}: authorities here are permissions, with no
 * {@code ROLE_} prefix. Like any {@code @PreAuthorize}, it only works on a call through the Spring
 * proxy, so it belongs on controller methods, never on a private or self-invoked one.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('{value}')")
public @interface RequiresPermission {

    Permission value();
}
