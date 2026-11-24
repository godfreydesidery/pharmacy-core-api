package tz.co.pharmacy.platform.composition;

import jakarta.persistence.EntityManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.partners.ports.inbound.DataSubjectRequestView;
import tz.co.pharmacy.modules.partners.ports.inbound.DataSubjectRequests;
import tz.co.pharmacy.modules.partners.ports.inbound.PersonalData;
import tz.co.pharmacy.modules.partners.ports.inbound.RequestKind;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.CellText;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * One customer's personal data across the modules' tables (STD-5), as {@link TenantDataService}
 * reads one whole pharmacy: by SQL under the pharmacy's row-level security, in the caller's
 * transaction. The anonymisation writes with SQL of its own on purpose: an entity change would put
 * the person's old values into the automatic audit trail.
 *
 * <p>Both are small (one person) and synchronous: the export is built in memory and returned, the
 * anonymisation is one transaction. Verify with counsel (C10) that anonymisation answers an erasure
 * request while the sales, claims and ledgers are kept for the law (C8).
 */
@Component
class PersonalDataService implements PersonalData {

  /** What replaces a customer's name, and the patient on their prescriptions and claims. */
  static final String CUSTOMER_PLACEHOLDER = "Anonymised customer";

  static final String PATIENT_PLACEHOLDER = "Anonymised patient";

  /** Internal keys and secrets never leave, as in the pharmacy's own export. */
  private static final Pattern HIDDEN =
      Pattern.compile("(?i)(id|tenant_uid|.*_id|.*(password|secret|token|_hash|credential).*)");

  private final JdbcTemplate jdbc;
  private final EntityManager entityManager;
  private final DataSubjectRequests requests;
  private final TenantService tenants;
  private final AuditLog audit;
  private final TenantCalendar calendar;
  private final Clock clock;
  private final String defaultCountryCode;
  private final JsonMapper json =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  PersonalDataService(
      DataSource dataSource,
      EntityManager entityManager,
      DataSubjectRequests requests,
      TenantService tenants,
      AuditLog audit,
      TenantCalendar calendar,
      Clock clock,
      @Value("${pharmacy.notifications.default-country-code:255}") String defaultCountryCode) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.entityManager = entityManager;
    this.requests = requests;
    this.tenants = tenants;
    this.audit = audit;
    this.calendar = calendar;
    this.clock = clock;
    this.defaultCountryCode = defaultCountryCode;
  }

  /** One kind of record of the person: its name in the ZIP, and its query. */
  private record Dataset(String name, String sql, Object... args) {}

  @Override
  @Transactional
  public Export export(UUID customerUid, UUID requestUid) {
    Map<String, Object> customer = customer(customerUid);
    requests.requireOpen(requestUid, RequestKind.ACCESS, customerUid);
    Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
    for (Dataset dataset : datasets(customerUid, addresses(customer))) {
      data.put(dataset.name(), rows(dataset));
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    data.forEach((name, rows) -> counts.put(name, rows.size()));
    String code = String.valueOf(customer.get("code"));
    audit.record(
        "DATA_SUBJECT_EXPORT", "Customer", customerUid, null, Map.of("code", code, "rows", counts));
    DataSubjectRequestView request =
        requests.complete(
            requestUid,
            RequestKind.ACCESS,
            customerUid,
            "Copy of the personal data exported ("
                + counts.values().stream().mapToInt(Integer::intValue).sum()
                + " records).");
    byte[] zip = zip(customerUid, data, request);
    LocalDate today = calendar.today();
    return new Export(
        code.replaceAll("[^A-Za-z0-9_-]", "_") + "-personal-data-" + today + ".zip", zip, request);
  }

  @Override
  @Transactional
  public DataSubjectRequestView anonymise(UUID customerUid, UUID requestUid, String note) {
    Map<String, Object> customer = customer(customerUid);
    if (customer.get("anonymised_at") != null) {
      throw Refusal.conflict(
          "customer_anonymised", "This customer's personal data is already anonymised.");
    }
    requests.requireOpen(requestUid, RequestKind.ERASURE, customerUid);
    UUID tenant = TenantContext.requireTenant();
    if (tenants.find(tenant).map(TenantView::legalHold).orElse(false)) {
      throw Refusal.invalid(
          "subject_under_legal_hold",
          "The pharmacy is under a legal hold: no personal data may be erased until it is lifted.",
          "customerUid");
    }
    BigDecimal balance =
        jdbc.queryForObject(
            "select coalesce(sum(amount), 0) from receivables.entries where customer_uid = ?",
            BigDecimal.class,
            customerUid);
    if (balance != null && balance.signum() != 0) {
      // The rule: an account that owes, or is owed, is settled first (a payment, a refund or a
      // write-off), so that no open debt or credit loses the person it belongs to.
      throw Refusal.invalid(
              "subject_has_balance",
              "The customer's account has a balance of "
                  + balance.toPlainString()
                  + "; settle it (payment, refund or write-off) before anonymising.",
              "customerUid")
          .with("balance", balance);
    }
    Set<String> addresses = addresses(customer);
    // Whatever the persistence context holds is written first, then forgotten, so no entity read
    // before can be written back over the anonymised rows, nor answer with the old values.
    entityManager.flush();
    Instant now = clock.instant();
    String by = ExecutionContext.actor().reference();
    Map<String, Integer> rows = new LinkedHashMap<>();
    rows.put(
        "partners.customers",
        jdbc.update(
            "update partners.customers set name = ?, phone = null, email = null, address = null,"
                + " insurance_provider = null, insurance_number = null, active = false,"
                + " anonymised_at = ?, anonymised_by = ?, updated_at = ?, updated_by = ?,"
                + " version = version + 1 where uid = ? and anonymised_at is null",
            CUSTOMER_PLACEHOLDER,
            Timestamp.from(now),
            by,
            Timestamp.from(now),
            by,
            customerUid));
    rows.put(
        "sales.sales",
        jdbc.update(
            "update sales.sales set customer_name = ?,"
                + " patient_name = case when patient_name is null then null else ? end,"
                + " updated_at = ?, updated_by = ?, version = version + 1 where customer_uid = ?",
            CUSTOMER_PLACEHOLDER,
            PATIENT_PLACEHOLDER,
            Timestamp.from(now),
            by,
            customerUid));
    rows.put(
        "insurance.members",
        jdbc.update(
            "update insurance.members set customer_name = ?, member_number = 'ANON-' || uid::text,"
                + " principal_name = null, active = false, updated_at = ?, updated_by = ?,"
                + " version = version + 1 where customer_uid = ?",
            CUSTOMER_PLACEHOLDER,
            Timestamp.from(now),
            by,
            customerUid));
    rows.put(
        "insurance.claims",
        jdbc.update(
            "update insurance.claims set member_number = 'ANON-' || member_uid::text,"
                + " patient_name = case when patient_name is null then null else ? end,"
                + " updated_at = ?, updated_by = ?, version = version + 1 where customer_uid = ?",
            PATIENT_PLACEHOLDER,
            Timestamp.from(now),
            by,
            customerUid));
    rows.put(
        "receivables.accounts",
        jdbc.update(
            "update receivables.accounts set customer_name = ?, updated_at = ?, updated_by = ?,"
                + " version = version + 1 where customer_uid = ?",
            CUSTOMER_PLACEHOLDER,
            Timestamp.from(now),
            by,
            customerUid));
    rows.put(
        "catalogue.price_lists",
        jdbc.update(
            "update catalogue.price_lists set customer_name = ?, updated_at = ?, updated_by = ?,"
                + " version = version + 1 where customer_uid = ?",
            CUSTOMER_PLACEHOLDER,
            Timestamp.from(now),
            by,
            customerUid));
    rows.put(
        "inventory.adverse_reactions",
        jdbc.update(
            "update inventory.adverse_reactions set patient_name = null, updated_at = ?,"
                + " updated_by = ?, version = version + 1"
                + " where customer_uid = ? and patient_name is not null",
            Timestamp.from(now),
            by,
            customerUid));
    rows.put(
        "notifications.messages",
        addresses.isEmpty()
            ? 0
            : jdbc.update(
                "update notifications.messages set to_address = '[anonymised]',"
                    + " recipient_name = null, subject = null, body = '[removed]',"
                    + " html_body = null, updated_at = ?, updated_by = ?, version = version + 1"
                    + " where to_address in ("
                    + placeholders(addresses)
                    + ")",
                withAddresses(addresses, Timestamp.from(now), by)));
    entityManager.clear();
    // No personal value goes into the trail: the code, the rows changed and the request.
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("code", customer.get("code"));
    after.put("rows", rows);
    after.put("note", note == null ? "" : note.strip());
    audit.record("DATA_SUBJECT_ERASURE", "Customer", customerUid, null, after);
    return requests.complete(
        requestUid,
        RequestKind.ERASURE,
        customerUid,
        "Anonymised: name, phone, email, address, insurance number and the patient on"
            + " prescriptions, claims and reports replaced; sales, payments, stock and ledgers kept"
            + " for the law."
            + (note == null || note.isBlank() ? "" : " " + note.strip()));
  }

  private Map<String, Object> customer(UUID customerUid) {
    List<Map<String, Object>> found =
        jdbc.queryForList(
            "select uid, code, phone, email, anonymised_at from partners.customers where uid = ?",
            customerUid);
    if (found.isEmpty()) {
      throw Refusal.notFound("customer_not_found", "No such customer.");
    }
    return found.get(0);
  }

  /** The person's phone (as stored and as E.164, the form messages are sent to) and email. */
  private Set<String> addresses(Map<String, Object> customer) {
    Set<String> addresses = new LinkedHashSet<>();
    Object phone = customer.get("phone");
    if (phone instanceof String p && !p.isBlank()) {
      addresses.add(p.strip());
      String e164 = e164(p, defaultCountryCode);
      if (e164 != null) {
        addresses.add(e164);
      }
    }
    Object email = customer.get("email");
    if (email instanceof String e && !e.isBlank()) {
      addresses.add(e.strip());
      addresses.add(e.strip().toLowerCase(java.util.Locale.ROOT));
    }
    return addresses;
  }

  private static String placeholders(Set<String> addresses) {
    return String.join(", ", java.util.Collections.nCopies(addresses.size(), "?"));
  }

  private static Object[] withAddresses(Set<String> addresses, Object... first) {
    List<Object> args = new ArrayList<>(List.of(first));
    args.addAll(addresses);
    return args.toArray();
  }

  private List<Dataset> datasets(UUID uid, Set<String> addresses) {
    List<Dataset> sets = new ArrayList<>();
    sets.add(new Dataset("customer", "select * from partners.customers where uid = ?", uid));
    sets.add(
        new Dataset(
            "consents",
            "select * from partners.customer_consents where customer_uid = ? order by id",
            uid));
    sets.add(
        new Dataset(
            "sales",
            "select * from sales.sales where customer_uid = ? order by created_at, id",
            uid));
    sets.add(
        new Dataset(
            "sale_lines",
            "select s.number as sale_number, l.* from sales.sale_lines l"
                + " join sales.sales s on s.id = l.sale_id"
                + " where s.customer_uid = ? order by s.created_at, l.id",
            uid));
    sets.add(
        new Dataset(
            "sale_payments",
            "select s.number as sale_number, p.* from sales.sale_payments p"
                + " join sales.sales s on s.id = p.sale_id"
                + " where s.customer_uid = ? order by s.created_at, p.id",
            uid));
    sets.add(
        new Dataset(
            "prescriptions",
            "select number as sale_number, created_at as sold_at, prescriber_name,"
                + " prescriber_registration, prescription_reference, patient_name,"
                + " prescription_date, dispensed_by from sales.sales"
                + " where customer_uid = ? and prescriber_name is not null"
                + " order by created_at, id",
            uid));
    sets.add(
        new Dataset(
            "sale_returns",
            "select r.* from sales.sale_returns r join sales.sales s on s.uid = r.sale_uid"
                + " where s.customer_uid = ? order by r.created_at, r.id",
            uid));
    sets.add(
        new Dataset(
            "insurance_memberships",
            "select * from insurance.members where customer_uid = ? order by id",
            uid));
    sets.add(
        new Dataset(
            "insurance_claims",
            "select * from insurance.claims where customer_uid = ? order by created_at, id",
            uid));
    sets.add(
        new Dataset(
            "receivable_account",
            "select * from receivables.accounts where customer_uid = ?",
            uid));
    sets.add(
        new Dataset(
            "receivable_entries",
            "select * from receivables.entries where customer_uid = ? order by entry_date, id",
            uid));
    sets.add(
        new Dataset(
            "adverse_reactions",
            "select * from inventory.adverse_reactions where customer_uid = ? order by id",
            uid));
    sets.add(
        new Dataset(
            "files",
            "select uid, name, content_type, size_bytes, owner_type, purpose, created_at"
                + " from files.files where status = 'COMPLETE'"
                + " and ((owner_type = 'CUSTOMER' and owner_uid = ?)"
                + " or (owner_type = 'SALE' and owner_uid in"
                + " (select uid from sales.sales where customer_uid = ?)))"
                + " order by id",
            uid,
            uid));
    sets.add(
        new Dataset(
            "requests",
            "select * from partners.data_subject_requests where customer_uid = ? order by id",
            uid));
    if (addresses.isEmpty()) {
      sets.add(new Dataset("messages", "select null as uid where false"));
    } else {
      sets.add(
          new Dataset(
              "messages",
              "select uid, template_key, channel, to_address, recipient_name, subject, body,"
                  + " status, sent_at, created_at from notifications.messages"
                  + " where to_address in ("
                  + placeholders(addresses)
                  + ") order by created_at, id",
              withAddresses(addresses)));
    }
    return sets;
  }

  /** The dataset's rows, internal keys and secrets left out, values as JSON-friendly text. */
  private List<Map<String, Object>> rows(Dataset dataset) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> row : jdbc.queryForList(dataset.sql(), dataset.args())) {
      Map<String, Object> kept = new LinkedHashMap<>();
      row.forEach(
          (column, value) -> {
            if (!HIDDEN.matcher(column).matches()) {
              kept.put(column, plain(value));
            }
          });
      rows.add(kept);
    }
    return rows;
  }

  private static Object plain(Object value) {
    if (value == null || value instanceof Boolean || value instanceof Integer) {
      return value;
    }
    if (value instanceof Long number) {
      return number;
    }
    if (value instanceof BigDecimal amount) {
      return amount.toPlainString();
    }
    if (value instanceof Timestamp at) {
      return at.toInstant().toString();
    }
    if (value instanceof java.sql.Date day) {
      return day.toLocalDate().toString();
    }
    if (value instanceof java.time.OffsetDateTime at) {
      return at.toInstant().toString();
    }
    return value.toString();
  }

  private byte[] zip(
      UUID customerUid,
      Map<String, List<Map<String, Object>>> data,
      DataSubjectRequestView request) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("customerUid", customerUid.toString());
      subject.put("generatedAt", clock.instant().toString());
      subject.put("request", request.number());
      subject.put(
          "about",
          "Everything this pharmacy holds about the person in Pharmacy Core. Files (such as"
              + " prescription scans) are listed, not included: ask the pharmacy for a copy. The"
              + " audit trail of who changed what is kept apart and is not included.");
      subject.putAll(data);
      zip.putNextEntry(new ZipEntry("subject.json"));
      zip.write(json.writeValueAsBytes(subject));
      zip.closeEntry();
      for (Map.Entry<String, List<Map<String, Object>>> dataset : data.entrySet()) {
        zip.putNextEntry(new ZipEntry(dataset.getKey() + ".csv"));
        zip.write(csv(dataset.getValue()).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /** RFC 4180, every text cell neutralised against spreadsheet formulas. */
  private static String csv(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    List<String> columns = new ArrayList<>(rows.get(0).keySet());
    line(out, new ArrayList<>(columns));
    for (Map<String, Object> row : rows) {
      List<String> cells = new ArrayList<>(columns.size());
      for (String column : columns) {
        Object value = row.get(column);
        cells.add(
            value == null
                ? null
                : value instanceof Number || value instanceof Boolean
                    ? value.toString()
                    : CellText.neutralised(value.toString()));
      }
      line(out, cells);
    }
    return out.toString();
  }

  private static void line(StringBuilder out, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      String cell = cells.get(i);
      if (cell == null) {
        continue;
      }
      if (cell.indexOf(',') >= 0
          || cell.indexOf('"') >= 0
          || cell.indexOf('\n') >= 0
          || cell.indexOf('\r') >= 0) {
        out.append('"').append(cell.replace("\"", "\"\"")).append('"');
      } else {
        out.append(cell);
      }
    }
    out.append("\r\n");
  }

  /** As the notifications module writes a phone it sends to (E.164), or null. */
  static String e164(String raw, String defaultCountryCode) {
    String digits = raw.replaceAll("[\\s().-]", "");
    if (digits.startsWith("00")) {
      digits = "+" + digits.substring(2);
    }
    if (digits.startsWith("+")) {
      String rest = digits.substring(1);
      return rest.matches("\\d{8,15}") ? "+" + rest : null;
    }
    if (digits.startsWith("0") && digits.length() >= 9) {
      String national = digits.substring(1);
      return national.matches("\\d{8,12}") ? "+" + defaultCountryCode + national : null;
    }
    return digits.matches("\\d{10,15}") ? "+" + digits : null;
  }
}
