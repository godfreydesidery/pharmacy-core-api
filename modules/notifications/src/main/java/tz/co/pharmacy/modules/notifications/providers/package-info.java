/**
 * The message providers, one bean per port chosen by configuration:
 *
 * <ul>
 *   <li>{@code pharmacy.notifications.email.provider}: {@code log} (default) or {@code smtp};
 *   <li>{@code pharmacy.notifications.sms.provider}: {@code log} (default), {@code africastalking},
 *       {@code twilio} or {@code beem};
 *   <li>{@code pharmacy.notifications.whatsapp.provider}: {@code log} (default) or {@code twilio}.
 * </ul>
 *
 * <p>HTTP providers build their request with a pure function (tested without a network) and send it
 * through {@link tz.co.pharmacy.modules.notifications.providers.ProviderHttp}: 10 second timeouts,
 * and no secret in any log line or error.
 */
package tz.co.pharmacy.modules.notifications.providers;
