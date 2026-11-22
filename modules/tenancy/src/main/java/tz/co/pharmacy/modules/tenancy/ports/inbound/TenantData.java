package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;

/**
 * A pharmacy's data as a whole: taking it out (portability) and erasing it (Tanzania's Personal
 * Data Protection Act 2022, the end of a closed account). Bound in composition, since it spans
 * every module's tables and the file store.
 */
public interface TenantData {

  /**
   * Writes the pharmacy's data to {@code out} as a ZIP: a CSV per table (one consistent snapshot),
   * its people ({@code access/users.csv}), its files ({@code files/...}) and {@code manifest.json}
   * with the row counts. Secrets (password and code hashes, tokens, provider keys) are left out.
   */
  void export(UUID tenantUid, OutputStream out);

  /**
   * Erases a closed pharmacy whose cooling-off has ended and which is not under a legal hold: every
   * row of every table, its files and its own staff logins, in one transaction. Its tenancy row
   * stays as a tombstone, with the platform's billing records. {@code confirmCode} must be the
   * pharmacy's code. Returns the rows erased per table. 422 erase_refused with the reason
   * otherwise.
   */
  Map<String, Long> erase(UUID tenantUid, String confirmCode);
}
