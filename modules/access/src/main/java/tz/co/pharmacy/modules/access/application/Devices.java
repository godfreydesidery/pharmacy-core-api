package tz.co.pharmacy.modules.access.application;

/** A readable name for a user agent, such as "Chrome on Windows", for notices and session lists. */
final class Devices {

  private Devices() {}

  static String describe(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return "an unknown device";
    }
    String agent = userAgent.toLowerCase();
    String browser =
        agent.contains("edg/")
            ? "Edge"
            : agent.contains("chrome/")
                ? "Chrome"
                : agent.contains("firefox/")
                    ? "Firefox"
                    : agent.contains("safari/") ? "Safari" : "a browser";
    String system =
        agent.contains("windows")
            ? "Windows"
            : agent.contains("android")
                ? "Android"
                : agent.contains("iphone") || agent.contains("ipad")
                    ? "iOS"
                    : agent.contains("mac os")
                        ? "macOS"
                        : agent.contains("linux") ? "Linux" : "an unknown system";
    return browser + " on " + system;
  }
}
