package tz.co.pharmacy.delivery.api;

/** What a route needs from the request before its permission is checked. */
public enum AccessScope {
  /** No session, such as sign-in. */
  PUBLIC,
  /** A session, no tenant, such as /auth/me. */
  AUTHENTICATED,
  /** A root administrator's session, no tenant: /platform. */
  PLATFORM,
  /** A session and X-Tenant-Id. */
  TENANT,
  /** A session, X-Tenant-Id and X-Branch-Id. */
  BRANCH,
  /** A session and X-Tenant-Id; X-Branch-Id optional (reports over "all my branches"). */
  TENANT_OPTIONAL_BRANCH
}
