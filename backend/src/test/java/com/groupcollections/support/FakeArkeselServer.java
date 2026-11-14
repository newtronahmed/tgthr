package com.groupcollections.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process Arkesel SMS stand-in; never sends a real message. */
public final class FakeArkeselServer implements AutoCloseable {

  private final ObjectMapper mapper = new ObjectMapper();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> apiKeys = new CopyOnWriteArrayList<>();
  private volatile String scenario = "SUCCESS";
  private final HttpServer server;

  public FakeArkeselServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v2/sms/send", this::handleSend);
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public List<String> bodies() {
    return List.copyOf(bodies);
  }

  public List<String> apiKeys() {
    return List.copyOf(apiKeys);
  }

  /** SUCCESS, ERROR, UNAUTHORIZED or SERVER. */
  public void scenario(String value) {
    scenario = value;
  }

  private void handleSend(HttpExchange exchange) throws IOException {
    bodies.add(read(exchange.getRequestBody()));
    String key = exchange.getRequestHeaders().getFirst("api-key");
    apiKeys.add(key == null ? "" : key);
    switch (scenario) {
      case "ERROR" ->
          respond(
              exchange,
              200,
              Map.of("status", "error", "message", "Insufficient balance or invalid coverage!"));
      case "UNAUTHORIZED" ->
          respond(exchange, 401, Map.of("status", "error", "message", "Invalid API key"));
      case "PAYMENT_REQUIRED" ->
          respond(
              exchange,
              402,
              Map.of("status", "error", "message", "Insufficient balance or invalid coverage!"));
      case "SERVER" ->
          respond(exchange, 500, Map.of("status", "error", "message", "SMS request failed!"));
      default ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  "success",
                  "data",
                  List.of(Map.of("recipient", "233551234987", "id", "fake-sms-id"))));
    }
  }

  private void respond(HttpExchange exchange, int statusCode, Map<String, ?> payload)
      throws IOException {
    byte[] bytes = mapper.writeValueAsBytes(payload);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static String read(InputStream input) throws IOException {
    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
