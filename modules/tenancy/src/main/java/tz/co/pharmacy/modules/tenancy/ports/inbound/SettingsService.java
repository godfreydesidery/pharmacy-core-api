package tz.co.pharmacy.modules.tenancy.ports.inbound;

/** The current tenant's settings. */
public interface SettingsService {

  SettingsView current();

  SettingsView change(ChangeSettingsCommand command);
}
