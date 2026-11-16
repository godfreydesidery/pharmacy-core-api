package tz.co.pharmacy.modules.integrations.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * A payment request pushed to a customer's phone. PENDING until the provider confirms or fails it;
 * USED once a sale has been paid with it, so it cannot pay twice.
 */
@Getter
@Entity
@Table(schema = "integrations", name = "payment_intents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentIntent extends TenantEntity {

  private UUID branchUid;
  private String methodCode;
  private BigDecimal amount;
  private String currency;
  private String phone;
  private String status;
  private String providerReference;
  private String failureReason;
  private String saleNumber;
  private Instant expiresAt;

  public PaymentIntent(
      UUID branchUid,
      String methodCode,
      BigDecimal amount,
      String currency,
      String phone,
      Instant expiresAt) {
    this.branchUid = branchUid;
    this.methodCode = methodCode;
    this.amount = amount;
    this.currency = currency;
    this.phone = phone;
    this.status = "PENDING";
    this.expiresAt = expiresAt;
  }

  public void confirmed(String providerReference) {
    this.status = "CONFIRMED";
    this.providerReference = providerReference;
  }

  public void failed(String reason) {
    this.status = "FAILED";
    this.failureReason = reason;
  }

  public void expired() {
    this.status = "EXPIRED";
  }

  public void usedBy(String saleNumber) {
    this.status = "USED";
    this.saleNumber = saleNumber;
  }

  public void providerReference(String reference) {
    this.providerReference = reference;
  }
}
