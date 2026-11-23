package tz.co.pharmacy.platform.persistence;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The name of this running instance of the API, for the job runs it records and the signals it
 * sends to the others: {@code PHARMACY_INSTANCE_ID} when set, otherwise the host name (an ECS
 * task's or a container's) with a short random suffix, so two processes on one host differ too.
 */
@Component
public class InstanceName {

  private final String name;

  public InstanceName(@Value("${pharmacy.instance-id:}") String configured) {
    this.name =
        configured == null || configured.isBlank()
            ? host() + "-" + UUID.randomUUID().toString().substring(0, 8)
            : configured.trim();
  }

  public String get() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }

  private static String host() {
    for (String variable : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
      String value = System.getenv(variable);
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    try {
      String host = InetAddress.getLocalHost().getHostName();
      return host == null || host.isBlank() ? "api" : host;
    } catch (UnknownHostException | RuntimeException unknown) {
      return "api";
    }
  }
}
