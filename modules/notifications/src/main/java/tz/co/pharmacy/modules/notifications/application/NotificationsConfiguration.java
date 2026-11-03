package tz.co.pharmacy.modules.notifications.application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The virtual threads that send messages after commit, away from requests and listeners. */
@Configuration(proxyBeanMethods = false)
class NotificationsConfiguration {

  @Bean(destroyMethod = "shutdown")
  ExecutorService notificationSenders() {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("notify-", 0).factory());
  }
}
