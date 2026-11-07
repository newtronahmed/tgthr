package com.groupcollections.payments;

public final class InvalidWebhookPayloadException extends RuntimeException {

  public InvalidWebhookPayloadException(String message) {
    super(message);
  }
}
