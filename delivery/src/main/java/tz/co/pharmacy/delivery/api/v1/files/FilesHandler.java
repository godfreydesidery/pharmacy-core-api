package tz.co.pharmacy.delivery.api.v1.files;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.files.ports.inbound.DownloadLink;
import tz.co.pharmacy.modules.files.ports.inbound.FileRules;
import tz.co.pharmacy.modules.files.ports.inbound.FileView;
import tz.co.pharmacy.modules.files.ports.inbound.Files;
import tz.co.pharmacy.modules.files.ports.inbound.SignedBlobs;
import tz.co.pharmacy.modules.files.ports.inbound.UploadCommand;
import tz.co.pharmacy.modules.files.ports.inbound.UploadTicket;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.Uids;

/**
 * Files and attachments (contract "Files"). The browser uploads with a presigned PUT: begin, PUT
 * the bytes to {@code uploadUrl}, then complete. Small files may also be posted as multipart.
 *
 * <p>An attachment is as private as what it is attached to. Each owner type names the permission to
 * see its files, to attach one and to delete one ({@link #RULES}): a sale's prescription scan needs
 * {@code sales.view}, and deleting it (evidence of a dispensing) {@code settings.manage}. A sale's
 * files also follow the sale's branch, so a user limited to one branch reaches only its sales'
 * files. The tenant's own files (the logo) are seen by every member.
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FilesHandler {

  private static final String BLOB = "/api/v1/files/blob/";

  /** Who may see, attach and delete the files of each owner type; null: every member. */
  record Rule(String view, String attach, String delete) {}

  static final Map<String, Rule> RULES =
      Map.of(
          "TENANT", new Rule(null, "settings.manage", "settings.manage"),
          "SALE", new Rule("sales.view", "sales.sell", "settings.manage"),
          "PURCHASE_ORDER", new Rule("purchasing.view", "purchasing.manage", "purchasing.manage"),
          "GOODS_RECEIPT", new Rule("purchasing.view", "purchasing.receive", "purchasing.manage"),
          "PRODUCT", new Rule("catalogue.view", "catalogue.manage", "catalogue.manage"),
          "CUSTOMER", new Rule("partners.view", "partners.manage", "partners.manage"),
          "SUPPLIER", new Rule("partners.view", "partners.manage", "partners.manage"),
          "EXPENSE", new Rule("finance.view", "finance.manage", "finance.manage"),
          "OTHER", new Rule("settings.manage", "settings.manage", "settings.manage"));

  private final Files files;
  private final SignedBlobs blobs;
  private final SaleService sales;

  @PostMapping("/uploads")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<UploadTicket> begin(@RequestBody UploadRequest body) {
    String ownerType = FileRules.ownerType(body.ownerType());
    require(ownerType, Rule::attach);
    requireOwner(ownerType, body.ownerUid());
    return ApiResponse.created(files.beginUpload(body.toCommand()), "Upload ready");
  }

  @PostMapping("/uploads/{fileUid}/complete")
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<FileView> complete(@PathVariable UUID fileUid) {
    reachable(fileUid, Rule::attach);
    return ApiResponse.ok(files.completeUpload(fileUid), "File stored");
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<FileView> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam String ownerType,
      @RequestParam(required = false) String ownerUid,
      @RequestParam(required = false) String purpose) {
    String type = FileRules.ownerType(ownerType);
    require(type, Rule::attach);
    UUID owner = Uids.parse(ownerUid, "ownerUid");
    requireOwner(type, owner);
    UploadCommand command =
        new UploadCommand(
            file.getOriginalFilename() == null ? "file" : file.getOriginalFilename(),
            file.getContentType(),
            file.getSize(),
            type,
            owner,
            purpose);
    try (InputStream content = file.getInputStream()) {
      return ApiResponse.created(files.uploadDirect(command, content), "File stored");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @GetMapping
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<List<FileView>> list(
      @RequestParam String ownerType, @RequestParam(required = false) String ownerUid) {
    String type = FileRules.ownerType(ownerType);
    require(type, Rule::view);
    UUID owner = Uids.parse(ownerUid, "ownerUid");
    if ("SALE".equals(type) && owner == null && !ExecutionContext.mayUseBranch(null)) {
      // Every sale's files at once would cross branches: name the sale.
      throw Refusal.invalid("owner_required", "Name the sale with ownerUid.", "ownerUid");
    }
    requireOwner(type, owner);
    return ApiResponse.ok(files.attachments(type, owner), "Files retrieved");
  }

  /** A presigned GET for an {@code <img src>} or a link, valid five minutes. */
  @GetMapping("/{fileUid}/url")
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<DownloadLink> url(@PathVariable UUID fileUid) {
    reachable(fileUid, Rule::view);
    return ApiResponse.ok(files.downloadLink(fileUid), "Download link");
  }

  @GetMapping("/{fileUid}/download")
  @Access(scope = AccessScope.TENANT)
  public ResponseEntity<Void> download(@PathVariable UUID fileUid) {
    reachable(fileUid, Rule::view);
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(files.downloadUrl(fileUid)))
        .build();
  }

  @DeleteMapping("/{fileUid}")
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<Void> delete(@PathVariable UUID fileUid) {
    reachable(fileUid, Rule::delete);
    files.delete(fileUid);
    return ApiResponse.ok("File deleted");
  }

  /** The local store's presigned PUT: the signature is the authorisation. */
  @PutMapping("/blob/**")
  @Access(scope = AccessScope.PUBLIC)
  public ResponseEntity<Void> putBlob(
      HttpServletRequest request,
      @RequestParam long exp,
      @RequestParam String sig,
      @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType)
      throws IOException {
    try (InputStream content = request.getInputStream()) {
      blobs.put(key(request), exp, sig, contentType, content);
    }
    return ResponseEntity.ok().build();
  }

  /** The local store's presigned GET. */
  @GetMapping("/blob/**")
  @Access(scope = AccessScope.PUBLIC)
  public ResponseEntity<StreamingResponseBody> getBlob(
      HttpServletRequest request,
      @RequestParam long exp,
      @RequestParam String sig,
      @RequestParam String ct) {
    SignedBlobs.Blob blob = blobs.get(key(request), exp, sig, ct);
    StreamingResponseBody body =
        out -> {
          try (InputStream content = blob.content()) {
            content.transferTo(out);
          }
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(blob.contentType()))
        .contentLength(blob.size())
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate())
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .header("X-Content-Type-Options", "nosniff")
        .body(body);
  }

  private static String key(HttpServletRequest request) {
    String uri = request.getRequestURI();
    int at = uri.indexOf(BLOB);
    if (at < 0) {
      throw Refusal.forbidden("invalid_signature", "This file link is not valid.");
    }
    return URLDecoder.decode(uri.substring(at + BLOB.length()), StandardCharsets.UTF_8);
  }

  /** The file, when the caller may act on it this way; otherwise 403, or 404 across branches. */
  private FileView reachable(UUID fileUid, Function<Rule, String> action) {
    FileView file =
        files.find(fileUid).orElseThrow(() -> Refusal.notFound("file_not_found", "No such file."));
    require(file.ownerType(), action);
    try {
      requireOwner(file.ownerType(), file.ownerUid());
    } catch (Refusal elsewhere) {
      throw Refusal.notFound("file_not_found", "No such file.");
    }
    return file;
  }

  private static void require(String ownerType, Function<Rule, String> action) {
    Rule rule = RULES.getOrDefault(ownerType, RULES.get("OTHER"));
    String permission = action.apply(rule);
    if (permission != null && !ExecutionContext.permits(permission)) {
      throw Refusal.forbidden(
          "forbidden", "Your role does not include the permission " + permission + ".");
    }
  }

  /** A sale's files follow the sale: it must exist, in a branch the caller may use. */
  private void requireOwner(String ownerType, UUID ownerUid) {
    if (!"SALE".equals(ownerType) || ownerUid == null) {
      return;
    }
    sales
        .find(ownerUid)
        .filter(sale -> ExecutionContext.mayUseBranch(sale.branchUid()))
        .orElseThrow(() -> Refusal.notFound("sale_not_found", "No such sale."));
  }

  public record UploadRequest(
      String name, String contentType, Long size, String ownerType, UUID ownerUid, String purpose) {

    UploadCommand toCommand() {
      return new UploadCommand(
          name,
          contentType,
          size == null ? 0 : size,
          FileRules.ownerType(ownerType),
          ownerUid,
          purpose);
    }
  }
}
