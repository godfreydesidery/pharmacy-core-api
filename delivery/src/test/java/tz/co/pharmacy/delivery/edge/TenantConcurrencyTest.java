package tz.co.pharmacy.delivery.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.platform.kernel.Refusal;

class TenantConcurrencyTest {

  @Test
  @DisplayName(
      "a pharmacy at its limit waits, then is refused 429; another pharmacy is not held up")
  void limitPerPharmacy() {
    TenantConcurrency fairShare = new TenantConcurrency(3, 100);
    UUID busy = UUID.randomUUID();
    UUID neighbour = UUID.randomUUID();
    List<TenantConcurrency.Slot> held = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      held.add(fairShare.acquire(busy));
    }
    assertThat(fairShare.inUse(busy)).isEqualTo(3);

    long started = System.nanoTime();
    assertThatThrownBy(() -> fairShare.acquire(busy))
        .isInstanceOf(Refusal.class)
        .hasMessageContaining("more requests at once");
    assertThat((System.nanoTime() - started) / 1_000_000)
        .as("waited for a slot")
        .isGreaterThanOrEqualTo(90);

    try (TenantConcurrency.Slot other = fairShare.acquire(neighbour)) {
      assertThat(fairShare.inUse(neighbour)).isOne();
    }
    held.forEach(TenantConcurrency.Slot::close);
    assertThat(fairShare.inUse(busy)).isZero();
  }

  @Test
  @DisplayName("a request over the limit gets the slot a finishing one frees")
  void waitsForAFreeSlot() throws Exception {
    TenantConcurrency fairShare = new TenantConcurrency(1, 2000);
    UUID pharmacy = UUID.randomUUID();
    TenantConcurrency.Slot first = fairShare.acquire(pharmacy);
    CountDownLatch waiting = new CountDownLatch(1);
    AtomicBoolean served = new AtomicBoolean();
    Thread second =
        Thread.ofVirtual()
            .start(
                () -> {
                  waiting.countDown();
                  try (TenantConcurrency.Slot slot = fairShare.acquire(pharmacy)) {
                    served.set(true);
                  }
                });
    waiting.await();
    Thread.sleep(100);
    first.close();
    second.join(2000);
    assertThat(served).isTrue();
  }
}
