package com.groupcollections.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingAlertPublisherTest {

  @Test
  void publishesAtErrorLevelWithTheAlertToken() {
    Logger logger = (Logger) LoggerFactory.getLogger(LoggingAlertPublisher.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      new LoggingAlertPublisher()
          .publish(new Alert(Alert.AlertType.AMOUNT_MISMATCH, "ref-1", "amount changed"));
      assertThat(appender.list).hasSize(1);
      ILoggingEvent event = appender.list.getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.ERROR);
      assertThat(event.getFormattedMessage()).startsWith("ALERT ").contains("ref-1");
    } finally {
      logger.detachAppender(appender);
    }
  }
}
