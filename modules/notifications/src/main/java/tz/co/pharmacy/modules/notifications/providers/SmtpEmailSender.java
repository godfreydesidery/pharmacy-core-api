package tz.co.pharmacy.modules.notifications.providers;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.outbound.EmailSender;

/**
 * Email through any SMTP server (Gmail or Office 365 relays, SES SMTP, Postmark, Mailgun …) with
 * {@code spring.mail.host}, {@code .port}, {@code .username}, {@code .password} and the sender
 * {@code pharmacy.notifications.email.from}. Each message carries both HTML and plain text.
 */
@Component
@ConditionalOnProperty(name = "pharmacy.notifications.email.provider", havingValue = "smtp")
class SmtpEmailSender implements EmailSender {

  private final ObjectProvider<JavaMailSender> mailSender;
  private final String from;

  SmtpEmailSender(
      ObjectProvider<JavaMailSender> mailSender,
      @Value("${pharmacy.notifications.email.from:Pharmacy Core <no-reply@pharmacy.local>}")
          String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  @Override
  public String name() {
    return "smtp";
  }

  @Override
  public void send(String to, String subject, String htmlBody, String textBody) {
    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null) {
      throw new ProviderException("smtp is not configured: set spring.mail.host");
    }
    try {
      MimeMessage message = sender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(from);
      helper.setTo(to);
      helper.setSubject(subject == null ? "" : subject);
      helper.setText(textBody == null ? "" : textBody, htmlBody == null ? textBody : htmlBody);
      sender.send(message);
    } catch (MessagingException | MailException exception) {
      throw new ProviderException("smtp refused the message: " + exception.getMessage(), exception);
    }
  }
}
