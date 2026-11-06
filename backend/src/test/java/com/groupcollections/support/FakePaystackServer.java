package com.groupcollections.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
          "0240000004", "REQUEST_REJECTED",
          "0240000005", "PENDING_THEN_FAILED",
          "0240000006", "PENDING_THEN_ABANDONED",
          "0240000007", "SLOW",
          "0240000008", "PENDING_THEN_WRONG_AMOUNT");

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final String secret;
  private final List<String> chargeBodies = new CopyOnWriteArrayList<>();
  private final Map<String, String> scenarioByReference = new ConcurrentHashMap<>();
  private final Map<String, Long> amountByReference = new ConcurrentHashMap<>();
  private final HttpServer server;

  public FakePaystackServer(String secret) throws IOException {
    this.secret = secret;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService executor =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable);
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(executor);
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

  /** Clears recorded prompts and scenario state between tests. */
  public void reset() {
    chargeBodies.clear();
    scenarioByReference.clear();
    amountByReference.clear();
  }

  public List<String> chargeBodies() {
    return List.copyOf(chargeBodies);
  }

  public int sendSuccessCallback(String webhookUrl, String reference, long amount) {
    return sendCallback(webhookUrl, "success", reference, amount, true);
  }

  public int sendFailedCallback(String webhookUrl, String reference, long amount) {
    return sendCallback(webhookUrl, "failed", reference, amount, true);
  }

  public int sendCallbackWithBadSignature(
      String webhookUrl, String reference, String outcome, long amount) {
    return sendCallback(webhookUrl, outcome, reference, amount, false);
  }

  public int sendRawCallback(String webhookUrl, String body, boolean validSignature) {
    return post(webhookUrl, body, validSignature ? sign(body) : "not-a-signature");
  }

  public String signature(String body) {
    return sign(body);
  }

  private int sendCallback(
      String webhookUrl, String outcome, String reference, long amount, boolean validSignature) {
    String event = "success".equals(outcome) ? "charge.success" : "charge.failed";
    String body =
        """
        {"event":"%s","data":{"reference":"%s","status":"%s","amount":%d,"currency":"GHS","paid_at":"%s"}}
        """
            .formatted(event, reference, outcome, amount, Instant.now());
    return post(webhookUrl, body, validSignature ? sign(body) : "not-a-signature");
  }

  private int post(String webhookUrl, String body, String signature) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .header("x-paystack-signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while sending a fake callback", interrupted);
    } catch (IOException failure) {
      throw new IllegalStateException("could not send a fake callback", failure);
    }
  }

  private String sign(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not sign the fake callback", failure);
    }
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
    amountByReference.put(reference, request.path("amount").asLong());

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
      case "SLOW" -> {
        sleep(5_000);
        respond(exchange, 200, accepted(reference));
      }
      default -> respond(exchange, 200, accepted(reference));
    }
  }

  private void handleVerify(HttpExchange exchange) throws IOException {
    String reference =
        exchange.getRequestURI().getPath().substring("/transaction/verify/".length());
    String scenario = scenarioByReference.getOrDefault(reference, "SUCCESS");
    String status =
        switch (scenario) {
          case "DECLINED", "PENDING_THEN_FAILED" -> "failed";
          case "PENDING_THEN_ABANDONED" -> "abandoned";
          default -> "success";
        };
    long amount = amountByReference.getOrDefault(reference, 0L);
    if ("PENDING_THEN_WRONG_AMOUNT".equals(scenario)) {
      amount += 100;
    }
    respond(
        exchange,
        200,
        Map.of(
            "status",
            true,
            "data",
            Map.of(
                "reference", reference,
                "status", status,
                "amount", amount,
                "currency", "GHS")));
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
