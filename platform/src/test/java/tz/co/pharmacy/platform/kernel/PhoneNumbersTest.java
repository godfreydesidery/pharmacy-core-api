package tz.co.pharmacy.platform.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** STD-19: every phone in E.164, a local number taking the pharmacy's country code. */
class PhoneNumbersTest {

  @ParameterizedTest(name = "{0} in {1} is {2}")
  @CsvSource({
    "0754 000 111, TZ, +255754000111",
    "0754-000-111, TZ, +255754000111",
    "754000111, TZ, +255754000111",
    "655000111, TZ, +255655000111",
    "255754000111, TZ, +255754000111",
    "00255754000111, TZ, +255754000111",
    "+255754000111, TZ, +255754000111",
    "+255 (0)754 000 111, TZ, +255754000111",
    "(022) 211 0000, TZ, +255222110000",
    "0712 345 678, KE, +254712345678",
    "+254712345678, TZ, +254712345678",
    "0772 123456, UG, +256772123456",
    "+44 20 7946 0000, TZ, +442079460000",
    "0754 000 111, XX, +255754000111",
  })
  void normalises(String raw, String country, String expected) {
    assertThat(PhoneNumbers.e164(raw, country)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "\"{0}\" is not a phone")
  @ValueSource(
      strings = {
        "",
        "   ",
        "call the shop",
        "0754 000 111 / 0655 000 222",
        "12345",
        "+2557540001",
        "+25575400011122",
        "07540001",
        "+0754000111",
        "ext 12"
      })
  void refuses(String raw) {
    assertThat(PhoneNumbers.e164(raw, "TZ")).isNull();
  }

  @Test
  @DisplayName("a phone to store: blank is none, unchanged legacy text stays, new junk is 422")
  void forStorage() {
    assertThat(PhoneNumbers.forStorage(" ", null, "TZ", "phone")).isNull();
    assertThat(PhoneNumbers.forStorage("0754000111", null, "TZ", "phone"))
        .isEqualTo("+255754000111");
    assertThat(PhoneNumbers.forStorage("Ask for Juma", "Ask for Juma", "TZ", "phone"))
        .isEqualTo("Ask for Juma");
    assertThatThrownBy(() -> PhoneNumbers.forStorage("Ask for Juma", null, "TZ", "phone"))
        .isInstanceOf(Refusal.class)
        .hasFieldOrPropertyWithValue("reason", "invalid_phone");
  }

  @Test
  @DisplayName("a search by a number as people write it looks for its digits")
  void searchDigits() {
    assertThat(PhoneNumbers.searchDigits("0754 000")).isEqualTo("754000");
    assertThat(PhoneNumbers.searchDigits("+255754")).isEqualTo("255754");
    assertThat(PhoneNumbers.searchDigits("Juma")).isNull();
    assertThat(PhoneNumbers.searchDigits("07")).isNull();
  }
}
