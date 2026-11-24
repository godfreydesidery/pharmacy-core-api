package tz.co.pharmacy.modules.notifications.application;

import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.crypto.Keyring;
import tz.co.pharmacy.platform.crypto.Purpose;

/**
 * Seals the text of an account message (a reset, invitation or recovery link, a code) while it
 * waits in the outbox, with the platform {@link Keyring} under the {@code messages} purpose, and
 * opens it only to send it (adversarial review AUTH-8). The outbox, a backup or a copy of the
 * database then never holds a live link or code: sent, the text is forgotten; given up on, it is
 * forgotten too.
 *
 * <p>Not a {@code SealedColumn}: a message is sealed for minutes, not years, so the key re-seal
 * leaves it alone. A message sealed under a key that has left the keyring cannot be opened; it is
 * given up on (the person asks for a new link).
 */
@Component
class MessageSeal {

  private final Keyring keyring;

  MessageSeal(Keyring keyring) {
    this.keyring = keyring;
  }

  String seal(String plain) {
    return plain == null ? null : keyring.seal(Purpose.MESSAGES, plain);
  }

  /** Throws {@link IllegalStateException} when no key in the keyring opens the value. */
  String open(String sealed) {
    return sealed == null ? null : keyring.openText(Purpose.MESSAGES, sealed);
  }
}
