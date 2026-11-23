package tz.co.pharmacy.delivery.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A POST that moves money or stock: a client that sends {@code Idempotency-Key} (IETF draft "The
 * Idempotency-Key HTTP Header Field", at most 255 characters) may retry it safely after a timeout
 * or a dropped connection.
 *
 * <ul>
 *   <li>The first request with a key reserves it, runs, and keeps its 2xx response for 24 hours.
 *   <li>A retry with the same key, route and body gets that response again, with {@code
 *       Idempotent-Replayed: true}; the handler does not run a second time.
 *   <li>The same key with another body (or route, or user): 422 {@code idempotency_key_reused}.
 *   <li>A retry while the first is still running: 409 {@code idempotency_in_progress}.
 *   <li>A first request that failed (not 2xx) frees the key: the client may retry with it.
 *   <li>No header: the route behaves as before.
 * </ul>
 *
 * Keys are per pharmacy and stored in {@code platform.idempotency_keys}, so every instance sees
 * them. The access checks run first: a caller who may not use the route never reaches a stored
 * response. See {@code IdempotencyFilter} and {@code IdempotencyInterceptor} in the edge.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {}
