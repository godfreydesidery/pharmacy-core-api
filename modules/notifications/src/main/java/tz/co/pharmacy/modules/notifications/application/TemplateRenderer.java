package tz.co.pharmacy.modules.notifications.application;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {{param}}} placeholders. An unknown placeholder renders empty. For email the values
 * are HTML-escaped and the text is laid out in the branded email frame.
 */
final class TemplateRenderer {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

  private TemplateRenderer() {}

  static String text(String template, Map<String, Object> params) {
    return fill(template, params, false);
  }

  static String fill(String template, Map<String, Object> params, boolean html) {
    if (template == null) {
      return null;
    }
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      Object value = params == null ? null : params.get(matcher.group(1));
      String text = value == null ? "" : String.valueOf(value);
      matcher.appendReplacement(out, Matcher.quoteReplacement(html ? escape(text) : text));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** The email's HTML: the body (escaped, line breaks kept) in a simple branded frame. */
  static String email(String subject, String bodyTemplate, Map<String, Object> params) {
    String body = fill(escape(bodyTemplate == null ? "" : bodyTemplate), params, true);
    body = body.replace("\n", "<br>");
    Object link = params == null ? null : params.get("link");
    String button =
        link == null || !String.valueOf(link).startsWith("http")
            ? ""
            : "<p style=\"margin:28px 0 8px\"><a href=\""
                + escape(String.valueOf(link))
                + "\" style=\"background:#2463eb;color:#fff;text-decoration:none;padding:12px 20px;"
                + "border-radius:10px;font-weight:600;display:inline-block\">Open Pharmacy Core</a></p>";
    Object pharmacy = params == null ? null : params.get("pharmacyName");
    String footer =
        pharmacy == null ? "Pharmacy Core" : escape(String.valueOf(pharmacy)) + " · Pharmacy Core";
    return "<!doctype html><html><body style=\"margin:0;background:#f5f7fb;font-family:Inter,Segoe UI,"
        + "Arial,sans-serif;color:#1e293b\"><div style=\"max-width:560px;margin:0 auto;padding:32px 16px\">"
        + "<div style=\"background:#fff;border:1px solid #e8ecf3;border-radius:16px;padding:28px\">"
        + "<h1 style=\"font-size:19px;margin:0 0 16px\">"
        + escape(subject == null ? "" : subject)
        + "</h1><div style=\"font-size:14.5px;line-height:1.6\">"
        + body
        + "</div>"
        + button
        + "</div><p style=\"color:#94a3b8;font-size:12px;text-align:center;margin-top:16px\">"
        + footer
        + "</p></div></body></html>";
  }

  static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (char c : text.toCharArray()) {
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
