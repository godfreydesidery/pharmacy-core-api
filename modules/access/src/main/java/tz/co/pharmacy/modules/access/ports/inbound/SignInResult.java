package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;

public record SignInResult(String token, Instant expiresAt, Principal principal) {}
