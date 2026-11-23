package tz.co.pharmacy.modules.notifications.providers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.pharmacy.modules.notifications.ports.outbound.EmailSender;
import tz.co.pharmacy.modules.notifications.ports.outbound.SmsSender;
import tz.co.pharmacy.modules.notifications.ports.outbound.WhatsAppSender;

/**
 * The default providers: they log the message (see {@link LogOutput}) and report it sent, so a
 * local run or a test needs no account anywhere. Production configures a real provider per channel.
 */
@Configuration(proxyBeanMethods = false)
class LogProviders {

  @Bean
  @ConditionalOnProperty(
      name = "pharmacy.notifications.email.provider",
      havingValue = "log",
      matchIfMissing = true)
  EmailSender logEmailSender(LogOutput output) {
    return new EmailSender() {
      @Override
      public String name() {
        return "log";
      }

      @Override
      public void send(String to, String subject, String htmlBody, String textBody) {
        output.write("email", to, subject, textBody);
      }
    };
  }

  @Bean
  @ConditionalOnProperty(
      name = "pharmacy.notifications.sms.provider",
      havingValue = "log",
      matchIfMissing = true)
  SmsSender logSmsSender(LogOutput output) {
    return new SmsSender() {
      @Override
      public String name() {
        return "log";
      }

      @Override
      public void send(String toE164, String text) {
        output.write("sms", toE164, null, text);
      }
    };
  }

  @Bean
  @ConditionalOnProperty(
      name = "pharmacy.notifications.whatsapp.provider",
      havingValue = "log",
      matchIfMissing = true)
  WhatsAppSender logWhatsAppSender(LogOutput output) {
    return new WhatsAppSender() {
      @Override
      public String name() {
        return "log";
      }

      @Override
      public void send(String toE164, String text) {
        output.write("whatsapp", toE164, null, text);
      }
    };
  }
}
