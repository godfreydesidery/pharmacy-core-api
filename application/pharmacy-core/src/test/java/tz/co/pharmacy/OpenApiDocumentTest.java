package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * {@code docs/openapi.json} is the OpenAPI document the code serves at {@code /v3/api-docs}
 * (adversarial review STD-9): every route with its access rule, headers, bodies and problems. The
 * build fails when the committed file differs from what the code generates, so a change of a route
 * is reviewed with its contract.
 *
 * <p>To refresh it after changing a route, run this test with {@code -Dpharmacy.openapi.write=true}
 * (see the README, "The API contract"), then review and commit the file.
 */
class OpenApiDocumentTest extends IntegrationTest {

  /** From application/pharmacy-core, where the tests run. */
  static final Path DOCUMENT = Path.of("..", "..", "docs", "openapi.json");

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  @Test
  @DisplayName("docs/openapi.json is the document the code generates")
  void committedDocumentIsCurrent() throws IOException {
    String generated = normalised(api().get("/v3/api-docs").expect(200).body());
    if (Boolean.getBoolean("pharmacy.openapi.write")) {
      Files.writeString(DOCUMENT, generated, StandardCharsets.UTF_8);
    }
    assertThat(Files.exists(DOCUMENT))
        .as(
            "docs/openapi.json is missing: run OpenApiDocumentTest with -Dpharmacy.openapi.write=true")
        .isTrue();
    String committed = normalised(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
    assertThat(committed)
        .as(
            "docs/openapi.json differs from the OpenAPI document the code generates. If the change"
                + " of the API is intended, refresh it (run OpenApiDocumentTest with"
                + " -Dpharmacy.openapi.write=true), review the difference and commit it.")
        .isEqualTo(generated);
  }

  /** Keys sorted, two-space indents, LF line ends and a final newline, on every platform. */
  static String normalised(String json) {
    Map<String, Object> document =
        JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
    return JSON.writeValueAsString(document).replace("\r\n", "\n") + "\n";
  }
}
