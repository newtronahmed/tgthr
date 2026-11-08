package com.groupcollections.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingAlertPublisher implements AlertPublisher {

  private static final Logger log = LoggerFactory.getLogger(LoggingAlertPublisher.class);

  @Override
  public void publish(Alert alert) {
    log.error(
        "ALERT type={} reference={} detail={}", alert.type(), alert.reference(), alert.detail());
  }
}
