package tz.co.pharmacy.modules.notifications.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.notifications.domain.TemplateOverride;
import tz.co.pharmacy.modules.notifications.persistence.TemplateOverrideRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.RenderedPreview;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateCommand;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateView;
import tz.co.pharmacy.modules.notifications.ports.inbound.Templates;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;

@Service
class TemplatesImpl implements Templates {

  private final TemplateOverrideRepository overrides;
  private final TemplateResolver resolver;

  TemplatesImpl(TemplateOverrideRepository overrides, TemplateResolver resolver) {
    this.overrides = overrides;
    this.resolver = resolver;
  }

  @Override
  @Transactional(readOnly = true)
  public List<TemplateView> list() {
    List<TemplateOverride> own = overrides.findAll();
    List<TemplateView> views = new ArrayList<>();
    for (String key : TemplateKeys.ALL) {
      for (Channel channel : Channel.values()) {
        for (String locale : TemplateCatalog.LOCALES) {
          views.add(
              own.stream()
                  .filter(
                      o ->
                          o.getTemplateKey().equals(key)
                              && o.getChannel() == channel
                              && o.getLocale().equals(locale))
                  .findFirst()
                  .map(
                      o ->
                          new TemplateView(key, channel, locale, o.getSubject(), o.getBody(), true))
                  .orElseGet(
                      () -> {
                        TemplateCatalog.Default base =
                            TemplateCatalog.forChannel(key, channel, locale);
                        return new TemplateView(
                            key, channel, locale, base.subject(), base.body(), false);
                      }));
        }
      }
    }
    return views;
  }

  @Override
  @Transactional
  public TemplateView override(
      String key, Channel channel, String locale, TemplateCommand command) {
    known(key, channel, locale);
    Checks.required(command, "template");
    String body = Checks.text(command.body(), "body", 4000);
    String subject =
        channel == Channel.SMS || channel == Channel.WHATSAPP
            ? null
            : Checks.optionalText(command.subject(), "subject", 200);
    TemplateOverride row =
        overrides
            .findByTemplateKeyAndChannelAndLocale(key, channel, locale)
            .map(
                existing -> {
                  existing.change(subject, body);
                  return existing;
                })
            .orElseGet(
                () -> overrides.save(new TemplateOverride(key, channel, locale, subject, body)));
    return new TemplateView(key, channel, locale, row.getSubject(), row.getBody(), true);
  }

  @Override
  @Transactional
  public void reset(String key, Channel channel, String locale) {
    known(key, channel, locale);
    overrides
        .findByTemplateKeyAndChannelAndLocale(key, channel, locale)
        .ifPresent(overrides::delete);
  }

  @Override
  @Transactional(readOnly = true)
  public RenderedPreview preview(
      String key, Channel channel, String locale, TemplateCommand draft) {
    known(key, channel, locale);
    Map<String, Object> sample = TemplateCatalog.sample(key);
    TemplateCatalog.Default template =
        draft == null || draft.body() == null
            ? resolver.effective(key, channel, locale)
            : new TemplateCatalog.Default(draft.subject(), draft.body(), null);
    TemplateResolver.Rendered rendered =
        TemplateResolver.render(template.subject(), template.body(), channel, sample);
    return new RenderedPreview(rendered.subject(), rendered.text(), rendered.html(), sample);
  }

  private static void known(String key, Channel channel, String locale) {
    if (!TemplateCatalog.known(key)
        || channel == null
        || !TemplateCatalog.LOCALES.contains(locale)) {
      throw Refusal.invalid("unknown_template", "No such template, channel or language.");
    }
  }
}
