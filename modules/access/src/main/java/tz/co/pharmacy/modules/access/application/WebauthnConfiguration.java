package tz.co.pharmacy.modules.access.application;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The WebAuthn relying party. {@code pharmacy.access.webauthn.rp-id} is the domain passkeys are
 * bound to (localhost locally; the web app's domain in production) and {@code rp-origins} the exact
 * origins the web app is served from.
 */
@Configuration(proxyBeanMethods = false)
class WebauthnConfiguration {

  @Bean
  RelyingParty relyingParty(
      PasskeyCredentials credentials,
      @Value("${pharmacy.access.webauthn.rp-id:localhost}") String rpId,
      @Value("${pharmacy.access.webauthn.rp-name:Pharmacy Core}") String rpName,
      @Value("${pharmacy.access.webauthn.rp-origins:http://localhost:4200,http://localhost:4300}")
          String origins) {
    Set<String> allowed = new LinkedHashSet<>();
    Arrays.stream(origins.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isEmpty())
        .forEach(allowed::add);
    return RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(rpId).name(rpName).build())
        .credentialRepository(credentials)
        .origins(allowed)
        .build();
  }
}
