package tz.co.pharmacy.delivery.api.v1.inventory;

import java.util.List;
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
import tz.co.pharmacy.modules.inventory.ports.inbound.Transformations;
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
    return ApiResponse.created(transformations.transform(body), "Transformation recorded");
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
