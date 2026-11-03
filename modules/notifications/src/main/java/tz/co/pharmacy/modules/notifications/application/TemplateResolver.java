package tz.co.pharmacy.modules.notifications.application;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.domain.TemplateOverride;
import tz.co.pharmacy.modules.notifications.persistence.TemplateOverrideRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.platform.persistence.TenantContext;

/** The template in effect: the tenant's own wording when it has one, otherwise the built-in one. */
@Component
class TemplateResolver {

  record Rendered(String subject, String text, String html) {}

  private final TemplateOverrideRepository overrides;

  TemplateResolver(TemplateOverrideRepository overrides) {
    this.overrides = overrides;
  }

  /** Inside a transaction: reads the tenant's override, if the request is in a tenant. */
  Rendered render(String key, Channel channel, String locale, Map<String, Object> params) {
    TemplateCatalog.Default template = effective(key, channel, locale);
    return render(template.subject(), template.body(), channel, params);
  }

  TemplateCatalog.Default effective(String key, Channel channel, String locale) {
    String language = TemplateCatalog.locale(locale);
    Optional<TemplateOverride> own =
        TenantContext.tenantUid().isPresent()
            ? overrides.findByTemplateKeyAndChannelAndLocale(key, channel, language)
            : Optional.empty();
    return own.map(o -> new TemplateCatalog.Default(o.getSubject(), o.getBody(), null))
        .orElseGet(() -> TemplateCatalog.forChannel(key, channel, language));
  }

  static Rendered render(String subject, String body, Channel channel, Map<String, Object> params) {
    String renderedSubject = TemplateRenderer.text(subject, params);
    String text = TemplateRenderer.text(body, params);
    String html =
        channel == Channel.EMAIL ? TemplateRenderer.email(renderedSubject, body, params) : null;
    return new Rendered(renderedSubject, text, html);
  }
}
