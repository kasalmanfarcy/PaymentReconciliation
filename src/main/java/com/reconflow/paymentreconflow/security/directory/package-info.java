/**
 * The user directory: the only part of {@code security} other modules may call (membership
 * and permission checks, display names; used by {@code exceptions} to validate assignees).
 * Everything else in {@code security}, including {@code auth} and {@code token}, is internal.
 */
@NamedInterface("directory")
package com.reconflow.paymentreconflow.security.directory;

import org.springframework.modulith.NamedInterface;
