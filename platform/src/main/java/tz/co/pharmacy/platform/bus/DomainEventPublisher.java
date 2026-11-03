package tz.co.pharmacy.platform.bus;

import java.time.Instant;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Uids;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Publishes a module's event inside its transaction. Spring Modulith's event publication registry
 * stores it with the transaction (the outbox) and delivers it only after commit, so no event leaves
 * for a write that rolled back, and an undelivered one is delivered again after a restart.
 */
@Component
public class DomainEventPublisher {

  private final ApplicationEventPublisher publisher;

  public DomainEventPublisher(ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  /** Publishes {@code type}, such as {@code sale.completed}, built by {@code factory}. */
  @Transactional(propagation = Propagation.MANDATORY)
  public <E extends DomainEvent> E publish(String type, Function<EventMetadata, E> factory) {
    EventMetadata metadata =
        new EventMetadata(
            Uids.newUid(),
            type,
            TenantContext.tenantUid().orElse(null),
            ExecutionContext.branchUid().orElse(null),
            Instant.now(),
            ExecutionContext.actor().reference(),
            ExecutionContext.correlationId().orElse(null));
    E event = factory.apply(metadata);
    publisher.publishEvent(event);
    return event;
  }
}
