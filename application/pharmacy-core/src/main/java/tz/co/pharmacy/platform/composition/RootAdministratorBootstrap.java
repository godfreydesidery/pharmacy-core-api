package tz.co.pharmacy.platform.composition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.inbound.NewUserCommand;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.ExecutionContext.Frame;

/**
 * Creates the first root administrator when the platform has none and {@code
 * pharmacy.bootstrap.root-email} and {@code root-password} are set; it does nothing once one
 * exists, so the setting can stay in place.
 */
@Component
class RootAdministratorBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(RootAdministratorBootstrap.class);

  private final UserAdministration users;
  private final String email;
  private final String password;
  private final String name;

  RootAdministratorBootstrap(
      UserAdministration users,
      @Value("${pharmacy.bootstrap.root-email:}") String email,
      @Value("${pharmacy.bootstrap.root-password:}") String password,
      @Value("${pharmacy.bootstrap.root-name:Root Administrator}") String name) {
    this.users = users;
    this.email = email;
    this.password = password;
    this.name = name;
  }

  @EventListener(ApplicationReadyEvent.class)
  void bootstrap() {
    if (email.isBlank() || password.isBlank()) {
      return;
    }
    ExecutionContext.callAs(
        Frame.service("bootstrap", null, null),
        () -> {
          if (!users.anyRootAdmin()) {
            users.createRootAdmin(new NewUserCommand(name, email, password));
            LOG.info("Created the first root administrator {}", email);
          }
          return null;
        });
  }
}
