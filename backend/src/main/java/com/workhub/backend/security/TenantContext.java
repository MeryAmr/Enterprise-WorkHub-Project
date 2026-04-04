package com.workhub.backend.security;

import java.util.UUID;

/**
 * Thread-local holder for the current request's tenant ID.
 * Populated by JwtAuthenticationFilter from the JWT "tenantId" claim.
 * Cleared after every request to prevent context leaking across threads.
 */
public class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    public static void setTenantId(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
