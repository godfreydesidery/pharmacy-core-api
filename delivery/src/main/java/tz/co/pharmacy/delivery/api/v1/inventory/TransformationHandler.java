package tz.co.pharmacy.delivery.api.v1.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService.FormulaItemCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService.FormulaItemView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService.PackLinkView;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransformationType;
import tz.co.pharmacy.modules.inventory.ports.inbound.Transformations;
import tz.co.pharmacy.modules.inventory.ports.inbound.Transformations.InputCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.Transformations.TransformCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.Transformations.TransformationView;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Repackaging and compounding: break bulk, repack into own packs, compound from a formula; and the
 * pack links and formulas they work from.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TransformationHandler {

  private final Transformations transformations;
  private final PackagingService packaging;

  @PostMapping("/stock/transformations")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<TransformationView> transform(@RequestBody TransformCommand body) {
    return ApiResponse.created(
        transformations.transform(withRecipe(body)), "Transformation recorded");
  }

  /**
   * Applies the product's recipe: a compounded product's formula, or the pack link of an own pack
   * (so many bulk units per pack), scaled by the quantity made. Inputs left out are filled from the
   * recipe; inputs given must cover it (422 inputs_below_formula).
   */
  private TransformCommand withRecipe(TransformCommand body) {
    if (body == null
        || body.output() == null
        || body.output().productUid() == null
        || body.output().quantity() == null
        || body.output().quantity() <= 0) {
      return body;
    }
    UUID output = body.output().productUid();
    int made = body.output().quantity();
    Map<UUID, Integer> required = new LinkedHashMap<>();
    Map<UUID, String> names = new LinkedHashMap<>();
    if (body.type() == TransformationType.COMPOUND) {
      for (FormulaItemView item : packaging.formula(output)) {
        required.put(
            item.ingredientUid(),
            item.quantity()
                .multiply(BigDecimal.valueOf(made))
                .setScale(0, RoundingMode.CEILING)
                .intValueExact());
        names.put(item.ingredientUid(), item.ingredientName());
      }
    } else if (body.type() == TransformationType.REPACK) {
      packaging.packLinks().stream()
          .filter(link -> link.packProductUid().equals(output))
          .findFirst()
          .ifPresent(
              link -> {
                required.put(link.unitProductUid(), link.unitsPerPack() * made);
                names.put(link.unitProductUid(), link.unitProductName());
              });
    }
    if (required.isEmpty()) {
      return body;
    }
    if (body.inputs() == null || body.inputs().isEmpty()) {
      List<InputCommand> inputs = new ArrayList<>();
      required.forEach((product, quantity) -> inputs.add(new InputCommand(product, quantity)));
      return new TransformCommand(body.type(), inputs, body.output(), body.note());
    }
    Map<UUID, Integer> given = new LinkedHashMap<>();
    for (InputCommand input : body.inputs()) {
      if (input != null && input.productUid() != null && input.quantity() != null) {
        given.merge(input.productUid(), input.quantity(), Integer::sum);
      }
    }
    required.forEach(
        (product, quantity) -> {
          int have = given.getOrDefault(product, 0);
          if (have < quantity) {
            throw Refusal.invalid(
                "inputs_below_formula",
                "Making "
                    + made
                    + " needs "
                    + quantity
                    + " of "
                    + names.getOrDefault(product, "an ingredient")
                    + " by its recipe; "
                    + have
                    + " given.",
                "inputs");
          }
        });
    return body;
  }

  @GetMapping("/stock/transformations")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<Page<TransformationView>> list(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        transformations.list(Pages.of(cursor, limit)), "Transformations retrieved");
  }

  @GetMapping("/pack-links")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<PackLinkView>> packLinks() {
    return ApiResponse.ok(packaging.packLinks(), "Pack links retrieved");
  }

  public record PackLinkRequest(UUID packProductUid, UUID unitProductUid, Integer unitsPerPack) {}

  @PutMapping("/pack-links")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PackLinkView> savePackLink(@RequestBody PackLinkRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return ApiResponse.ok(
        packaging.savePackLink(body.packProductUid(), body.unitProductUid(), body.unitsPerPack()),
        "Pack link saved");
  }

  @DeleteMapping("/pack-links/{unitProductUid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<Void> deletePackLink(@PathVariable UUID unitProductUid) {
    packaging.deletePackLink(unitProductUid);
    return ApiResponse.ok(null, "Pack link removed");
  }

  @GetMapping("/products/{uid}/formula")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<FormulaItemView>> formula(@PathVariable UUID uid) {
    return ApiResponse.ok(packaging.formula(uid), "Formula retrieved");
  }

  @PutMapping("/products/{uid}/formula")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<List<FormulaItemView>> saveFormula(
      @PathVariable UUID uid, @RequestBody List<FormulaItemCommand> body) {
    return ApiResponse.ok(packaging.saveFormula(uid, body), "Formula saved");
  }
}
