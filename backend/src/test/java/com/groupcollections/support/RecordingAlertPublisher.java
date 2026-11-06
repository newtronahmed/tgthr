package com.groupcollections.support;

import com.groupcollections.contribution.Alert;
import com.groupcollections.contribution.AlertPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures alerts raised during integration tests. */
public final class RecordingAlertPublisher implements AlertPublisher {

  private static final List<Alert> ALERTS = new CopyOnWriteArrayList<>();

  public static void reset() {
    ALERTS.clear();
  }

  public static List<Alert> alerts() {
    return List.copyOf(ALERTS);
  }

  @Override
  public void publish(Alert alert) {
    ALERTS.add(alert);
  }
}
