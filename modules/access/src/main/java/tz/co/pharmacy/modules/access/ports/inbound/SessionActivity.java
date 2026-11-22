package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;

/**
 * The session after the person used the app: its inactivity limit in minutes and when it ends
 * without further use (both null without a limit), and when it ends regardless.
 */
public record SessionActivity(Integer idleMinutes, Instant idleEndsAt, Instant expiresAt) {}
