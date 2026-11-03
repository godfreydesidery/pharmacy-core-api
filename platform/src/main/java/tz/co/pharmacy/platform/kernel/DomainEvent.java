package tz.co.pharmacy.platform.kernel;

/** A past-tense fact a module publishes; listeners receive it after the transaction commits. */
public interface DomainEvent {

  EventMetadata metadata();
}
