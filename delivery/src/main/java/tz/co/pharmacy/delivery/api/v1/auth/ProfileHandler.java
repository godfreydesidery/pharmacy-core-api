package tz.co.pharmacy.delivery.api.v1.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.access.ports.inbound.UserView;
import tz.co.pharmacy.platform.kernel.Refusal;

/** The signed-in person's own profile: name and phone (for SMS and WhatsApp). */
@RestController
@RequestMapping("/api/v1/auth/profile")
public class ProfileHandler {

  private final UserAdministration users;

  public ProfileHandler(UserAdministration users) {
    this.users = users;
  }

  @GetMapping
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<UserView> profile() {
    return ApiResponse.ok(users.me(), "Profile retrieved");
  }

  /** {@code phone} in international form (+255754000111); blank removes it. */
  @PutMapping
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<UserView> change(@RequestBody ProfileRequest body) {
    if (body == null) {
      throw Refusal.malformed("A profile is required.");
    }
    return ApiResponse.ok(users.changeOwnProfile(body.name(), body.phone()), "Profile saved");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProfileRequest(String name, String phone) {}
}
