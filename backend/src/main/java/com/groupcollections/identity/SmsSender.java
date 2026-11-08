package com.groupcollections.identity;

/**
 * Sends a one-time code by SMS. Tests use a fake; production needs a provider adapter (ADR 0009).
 */
public interface SmsSender {

  void send(String phoneE164, String message);
}
