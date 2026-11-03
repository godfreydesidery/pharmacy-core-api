package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.util.List;

/**
 * Templates: built-in English and Swahili defaults for every key and channel, which a tenant may
 * override. Bodies use {@code {{param}}} placeholders.
 */
public interface Templates {

  /** Every key × channel × locale, with the tenant's own wording where it has one. */
  List<TemplateView> list();

  /** 422 unknown_template for a key, channel or locale that does not exist. */
  TemplateView override(String key, Channel channel, String locale, TemplateCommand command);

  void reset(String key, Channel channel, String locale);

  /** Renders a draft (or, with a null draft, the template in effect) with sample parameters. */
  RenderedPreview preview(String key, Channel channel, String locale, TemplateCommand draft);
}
