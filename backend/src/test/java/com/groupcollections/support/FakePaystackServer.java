package com.groupcollections.support;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process stand-in for the Paystack charge API. Scenarios are selected deterministically by the
 * payer's mobile money number so a test never needs to reach the real provider (Constitution VII).
 * Numbers ending in 1..4 map to success, declined, timeout and request rejected; anything else
 * succeeds.
 */
public final class FakePaystackServer implements AutoCloseable {

  private static final Map<String, String> SCENARIO_BY_PHONE =
      Map.of(
          "0240000001", "SUCCESS",
          "0240000002", "DECLINED",
          "0240000003", "TIMEOUT",
          "0240000004", "REQUEST_REJECTED");

  private final ObjectMapper mapper = new ObjectMapper();
  private final List<String> chargeBodies = new CopyOnWriteArrayList<>();
  private final Map<String, String> scenarioByReference = new ConcurrentHashMap<>();
  private final HttpServer server;

  public FakePaystackServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/charge", this::handleCharge);
    server.createContext("/transaction/verify/", this::handleVerify);
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** How many prompt requests (charge initiations) the partner has received. */
  public int promptCount() {
    return chargeBodies.size();
  }

  public List<String> chargeBodies() {
    return List.copyOf(chargeBodies);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handleCharge(HttpExchange exchange) throws IOException {
    String body = read(exchange.getRequestBody());
    chargeBodies.add(body);
    JsonNode request = mapper.readTree(body);
    String reference = request.path("reference").asText();
    String phone = request.path("mobile_money").path("phone").asText();
    String scenario = SCENARIO_BY_PHONE.getOrDefault(phone, "SUCCESS");
    scenarioByReference.put(reference, scenario);

    switch (scenario) {
      case "REQUEST_REJECTED" ->
          respond(exchange, 400, Map.of("status", false, "message", "charge request rejected"));
      case "DECLINED" ->
          respond(
              exchange,
              200,
              Map.of("status", true, "data", Map.of("reference", reference, "status", "failed")));
      case "TIMEOUT" -> {
        sleep(2_000);
        respond(exchange, 200, accepted(reference));
      }
      default -> respond(exchange, 200, accepted(reference));
    }
  }

  private void handleVerify(HttpExchange exchange) throws IOException {
    String reference =
        exchange.getRequestURI().getPath().substring("/transaction/verify/".length());
    String scenario = scenarioByReference.getOrDefault(reference, "SUCCESS");
    String status = "DECLINED".equals(scenario) ? "failed" : "success";
    respond(
        exchange,
        200,
        Map.of(
            "status", true, "data", Map.of("reference", reference, "status", status, "amount", 0)));
  }

  private static Map<String, Object> accepted(String reference) {
    return Map.of(
        "status",
        true,
        "data",
        Map.of(
            "reference", reference,
            "status", "pending",
            "display_text", "Approve the prompt on your phone"));
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

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
