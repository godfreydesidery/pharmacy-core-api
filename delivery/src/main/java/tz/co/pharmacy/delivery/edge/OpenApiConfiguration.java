package tz.co.pharmacy.delivery.edge;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.CountsAgainst;
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.RequiresFeature;
import tz.co.pharmacy.platform.approval.Approvals;

/**
 * The OpenAPI 3.1 document at /v3/api-docs and Swagger UI at /swagger-ui.html, generated from the
 * code (adversarial review STD-9). Every operation carries what the edge enforces for it, read from
 * the handler's annotations:
 *
 * <ul>
 *   <li>{@code x-scope} and {@code x-permission} from {@link Access} (with {@code
 *       x-permission-any-of}, {@code x-permission-all-of} and {@code x-step-up} when it has them),
 *       {@code x-idempotent} from {@link Idempotent}, {@code x-plan-feature} from {@link
 *       RequiresFeature} and {@code x-plan-limit} from {@link CountsAgainst}; the same in words at
 *       the end of the description;
 *   <li>the headers it takes: {@code X-Tenant-Id}, {@code X-Branch-Id}, {@code Idempotency-Key},
 *       {@code X-Approval} (a change a manager may have to approve) and {@code If-Match} (a change
 *       of an edited resource, {@link Preconditions});
 *   <li>its refusals: RFC 9457 problems ({@code application/problem+json}, schema {@code Problem})
 *       with a stable {@code reason}.
 * </ul>
 *
 * {@code docs/openapi.json} in the repository is this document; {@code OpenApiDocumentTest} fails
 * the build when the two differ (see the README to refresh it).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

  static final String PROBLEM = "Problem";
  private static final String PROBLEM_JSON = "application/problem+json";

  @Bean
  OpenAPI pharmacyOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Pharmacy Core API")
                .version("v1")
                .description(
                    "Multi-tenant, multi-branch pharmacy management. Send Authorization: Bearer"
                        + " <token>, X-Tenant-Id and, on branch routes, X-Branch-Id. Successes"
                        + " use the envelope {success, code, message, timestamp, data}; refusals"
                        + " are RFC 9457 problems (application/problem+json) with a stable"
                        + " `reason`. Each operation's x-scope and x-permission say who may call"
                        + " it. The guide is pharmacy-core-docs/contracts/api-v1.md; this"
                        + " document is the complete reference."))
        .servers(List.of(new Server().url("/").description("This server")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "session", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"))
                .addSchemas(PROBLEM, problemSchema())
                .addParameters("tenant", tenantHeader())
                .addParameters("branch", branchHeader(false))
                .addParameters("branchRequired", branchHeader(true))
                .addParameters("idempotencyKey", idempotencyHeader())
                .addParameters("approval", approvalHeader())
                .addParameters("ifMatch", ifMatchHeader())
                .addResponses("Problem", problem("A refusal (RFC 9457); see `reason`.")))
        .addSecurityItem(new SecurityRequirement().addList("session"));
  }

  /** Adds each route's access rule, headers and problems to its operation. */
  @Bean
  OperationCustomizer accessRules() {
    return OpenApiConfiguration::describe;
  }

  static Operation describe(Operation operation, HandlerMethod handler) {
    operation.setOperationId(
        handler.getBeanType().getSimpleName() + "_" + handler.getMethod().getName());
    Access access = handler.getMethodAnnotation(Access.class);
    List<String> rules = new ArrayList<>();
    if (access != null) {
      operation.addExtension("x-scope", access.scope().name());
      operation.addExtension("x-permission", access.permission());
      rules.add("scope " + access.scope().name());
      if (!access.permission().isEmpty()) {
        rules.add("permission `" + access.permission() + "`");
      }
      if (access.anyOf().length > 0) {
        operation.addExtension("x-permission-any-of", List.of(access.anyOf()));
        rules.add("any of " + quoted(access.anyOf()));
      }
      if (access.allOf().length > 0) {
        operation.addExtension("x-permission-all-of", List.of(access.allOf()));
        rules.add("all of " + quoted(access.allOf()));
      }
      if (access.stepUp()) {
        operation.addExtension("x-step-up", true);
        rules.add("a password proved in the last minutes (403 reauthentication_required)");
      }
      if (access.scope() == AccessScope.PUBLIC) {
        operation.setSecurity(List.of());
      }
    }
    RequiresFeature feature =
        AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequiresFeature.class);
    if (feature == null) {
      feature =
          AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RequiresFeature.class);
    }
    if (feature != null) {
      operation.addExtension("x-plan-feature", feature.value().name());
      rules.add("plan feature " + feature.value().name());
    }
    CountsAgainst limit = handler.getMethodAnnotation(CountsAgainst.class);
    if (limit != null) {
      operation.addExtension("x-plan-limit", limit.value().name());
      rules.add("counts against the plan's " + limit.value().name());
    }
    boolean idempotent = handler.hasMethodAnnotation(Idempotent.class);
    if (idempotent) {
      operation.addExtension("x-idempotent", true);
      rules.add("safe to retry with Idempotency-Key");
    }
    boolean conditional = takesIfMatch(operation);
    if (conditional) {
      operation.addExtension("x-if-match", true);
      rules.add("If-Match with the resource's ETag (412 precondition_failed)");
    }
    String rule =
        access == null
            ? "**Access:** none declared; the edge refuses it."
            : "**Access:** " + String.join("; ", rules) + ".";
    operation.setDescription(
        operation.getDescription() == null || operation.getDescription().isBlank()
            ? rule
            : operation.getDescription() + "\n\n" + rule);
    headers(operation, access, idempotent, handler);
    responses(operation, access, conditional);
    return operation;
  }

  private static String quoted(String[] codes) {
    return String.join(", ", Arrays.stream(codes).map(code -> "`" + code + "`").toList());
  }

  private static boolean takesIfMatch(Operation operation) {
    return operation.getParameters() != null
        && operation.getParameters().stream()
            .anyMatch(p -> Preconditions.IF_MATCH.equalsIgnoreCase(p.getName()));
  }

  private static void headers(
      Operation operation, Access access, boolean idempotent, HandlerMethod handler) {
    List<Parameter> parameters = new ArrayList<>();
    if (access != null) {
      switch (access.scope()) {
        case TENANT -> parameters.add(reference("tenant"));
        case TENANT_OPTIONAL_BRANCH -> {
          parameters.add(reference("tenant"));
          parameters.add(reference("branch"));
        }
        case BRANCH -> {
          parameters.add(reference("tenant"));
          parameters.add(reference("branchRequired"));
        }
        default -> {}
      }
    }
    if (idempotent) {
      parameters.add(reference("idempotencyKey"));
    }
    boolean writes = !handler.hasMethodAnnotation(GetMapping.class);
    if (writes
        && access != null
        && access.scope() != AccessScope.PUBLIC
        && access.scope() != AccessScope.AUTHENTICATED) {
      parameters.add(reference("approval"));
    }
    if (operation.getParameters() != null) {
      for (Parameter p : operation.getParameters()) {
        if (Preconditions.IF_MATCH.equalsIgnoreCase(p.getName())) {
          parameters.add(reference("ifMatch"));
        } else {
          parameters.add(p);
        }
      }
    }
    operation.setParameters(parameters);
  }

  private static Parameter reference(String name) {
    return new Parameter().$ref("#/components/parameters/" + name);
  }

  private static void responses(Operation operation, Access access, boolean conditional) {
    ApiResponses responses =
        operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
    if (access != null && access.scope() != AccessScope.PUBLIC) {
      responses.putIfAbsent("401", problem("No valid session (`unauthenticated`)."));
      responses.putIfAbsent(
          "403", problem("Not allowed: the permission, tenant or branch (`forbidden`…)."));
    }
    if (conditional) {
      responses.putIfAbsent(
          "412",
          problem(
              "`precondition_failed`: If-Match names a version the resource no longer has;"
                  + " `currentVersion` is the one it has."));
      responses.putIfAbsent(
          "428",
          problem(
              "`precondition_required`: the server requires If-Match"
                  + " (pharmacy.http.require-if-match)."));
    }
    responses.putIfAbsent("default", new ApiResponse().$ref("#/components/responses/Problem"));
    operation.setResponses(responses);
  }

  private static ApiResponse problem(String description) {
    return new ApiResponse()
        .description(description)
        .content(
            new Content()
                .addMediaType(
                    PROBLEM_JSON,
                    new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
  }

  /** RFC 9457 with the members this API adds. */
  @SuppressWarnings("rawtypes")
  private static Schema problemSchema() {
    return new ObjectSchema()
        .description(
            "RFC 9457 problem details. `reason` is the stable machine code; `field` the JSON path"
                + " of the input it is about; other members (RFC 9457 §3.2) carry values for the"
                + " message, such as `currentVersion`, `action` and `amount`.")
        .addProperty("type", new StringSchema().format("uri"))
        .addProperty("title", new StringSchema())
        .addProperty("status", new IntegerSchema())
        .addProperty("detail", new StringSchema())
        .addProperty("instance", new StringSchema().format("uri"))
        .addProperty("reason", new StringSchema().example("insufficient_stock"))
        .addProperty("field", new StringSchema().example("lines[0].quantity"))
        .addProperty("correlationId", new StringSchema())
        .addProperty("retryAfterSeconds", new IntegerSchema())
        .required(List.of("type", "title", "status", "reason"));
  }

  private static HeaderParameter tenantHeader() {
    return (HeaderParameter)
        new HeaderParameter()
            .name(EdgeFilter.TENANT_HEADER)
            .required(true)
            .description("The pharmacy (tenant uid) the request acts in.")
            .schema(new StringSchema().format("uuid"));
  }

  private static HeaderParameter branchHeader(boolean required) {
    return (HeaderParameter)
        new HeaderParameter()
            .name(EdgeFilter.BRANCH_HEADER)
            .required(required)
            .description(
                required
                    ? "The branch (uid) the request acts in."
                    : "The branch (uid); without it the report covers every branch allowed.")
            .schema(new StringSchema().format("uuid"));
  }

  private static HeaderParameter idempotencyHeader() {
    return (HeaderParameter)
        new HeaderParameter()
            .name(IdempotencyFilter.HEADER)
            .required(false)
            .description(
                "Up to 255 characters. A retry with the same key and body gets the first"
                    + " response again (Idempotent-Replayed: true); kept 24 hours.")
            .schema(new StringSchema().maxLength(255));
  }

  private static HeaderParameter approvalHeader() {
    return (HeaderParameter)
        new HeaderParameter()
            .name(Approvals.HEADER)
            .required(false)
            .description(
                "Only after a 403 approval_required: the manager's approval (pap_…) for that"
                    + " action and amount, from POST /approvals; several, comma-separated.")
            .schema(new StringSchema());
  }

  private static HeaderParameter ifMatchHeader() {
    return (HeaderParameter)
        new HeaderParameter()
            .name(Preconditions.IF_MATCH)
            .required(false)
            .description(
                "The ETag of the resource as read (\"7\"): the change goes ahead only while it"
                    + " still has that version (else 412 precondition_failed).")
            .schema(new StringSchema().example("\"7\""));
  }
}
