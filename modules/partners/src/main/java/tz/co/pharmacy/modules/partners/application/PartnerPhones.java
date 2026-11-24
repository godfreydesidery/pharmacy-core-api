package tz.co.pharmacy.modules.partners.application;

import org.springframework.data.jpa.domain.Specification;
import tz.co.pharmacy.platform.kernel.PhoneNumbers;
import tz.co.pharmacy.platform.persistence.Specs;

/**
 * Customers' and suppliers' phones are kept in E.164 (STD-19; {@link PhoneNumbers#forStorage}), so
 * a search by a number as people write it looks for its digits too.
 */
final class PartnerPhones {

  private PartnerPhones() {}

  /** A phone to store, a local number taking the pharmacy's country code. */
  static String phone(String given, String current, String country) {
    return PhoneNumbers.forStorage(given, current, country, "phone");
  }

  /**
   * The search of {@code q} over {@code attributes}, also finding a stored phone when {@code q} is
   * a number as people write it ("0754 000" finds +255754000111).
   */
  static <E> Specification<E> search(String q, String... attributes) {
    Specification<E> text = Specs.containsAny(q, attributes);
    String digits = PhoneNumbers.searchDigits(q);
    return digits == null ? text : text.or(Specs.containsAny(digits, "phone"));
  }
}
