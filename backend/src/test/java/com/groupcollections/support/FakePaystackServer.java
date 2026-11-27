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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  private final List<String> transferBodies = new CopyOnWriteArrayList<>();
  private final List<String> recipientBodies = new CopyOnWriteArrayList<>();
  private final List<String> resolveQueries = new CopyOnWriteArrayList<>();
  private final List<String> refundBodies = new CopyOnWriteArrayList<>();
  private volatile String transferScenario = "SUCCESS";
  private volatile String resolveScenario = "SUCCESS";
  private volatile String refundScenario = "SUCCESS";
  private final Map<String, String> scenarioByReference = new ConcurrentHashMap<>();
  private final Map<String, Long> transferAmountByReference = new ConcurrentHashMap<>();
  private final Map<String, Long> refundAmountByReference = new ConcurrentHashMap<>();
  private final Map<String, String> refundCurrencyByReference = new ConcurrentHashMap<>();
  private final Map<String, Long> refundAmountByTransaction = new ConcurrentHashMap<>();
  private final Map<String, String> refundCurrencyByTransaction = new ConcurrentHashMap<>();
  private final Map<String, String> refundReferenceByTransaction = new ConcurrentHashMap<>();
  private final Map<String, String> clientReferenceByTransaction = new ConcurrentHashMap<>();
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
    server.createContext("/bank/resolve", this::handleResolve);
    server.createContext("/charge", this::handleCharge);
    server.createContext("/transaction/verify/", this::handleVerify);
    server.createContext("/transferrecipient", this::handleTransferRecipient);
    server.createContext("/transfer/verify/", this::handleTransferVerify);
    server.createContext("/transfer", this::handleTransfer);
    server.createContext("/refund", this::handleRefund);
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
    transferBodies.clear();
    recipientBodies.clear();
    resolveQueries.clear();
    refundBodies.clear();
    transferAmountByReference.clear();
    refundAmountByReference.clear();
    refundCurrencyByReference.clear();
    refundAmountByTransaction.clear();
    refundCurrencyByTransaction.clear();
    refundReferenceByTransaction.clear();
    clientReferenceByTransaction.clear();
    transferScenario = "SUCCESS";
    resolveScenario = "SUCCESS";
    refundScenario = "SUCCESS";
    scenarioByReference.clear();
    amountByReference.clear();
  }

  public List<String> chargeBodies() {
    return List.copyOf(chargeBodies);
  }

  public List<String> transferBodies() {
    return List.copyOf(transferBodies);
  }

  public List<String> recipientBodies() {
    return List.copyOf(recipientBodies);
  }

  public List<String> resolveQueries() {
    return List.copyOf(resolveQueries);
  }

  public List<String> refundBodies() {
    return List.copyOf(refundBodies);
  }

  /** SUCCESS, PENDING, FAILED, REQUEST_REJECTED, SERVER_ERROR, or a duplicate scenario. */
  public void refundScenario(String scenario) {
    refundScenario = scenario;
  }

  /** Primes a refund record without a POST, for tests that seed a pending refund directly. */
  public void seedRefund(String transaction, String reference, long amount, String currency) {
    refundReferenceByTransaction.put(transaction, reference);
    refundAmountByTransaction.put(transaction, amount);
    refundCurrencyByTransaction.put(transaction, currency);
    refundAmountByReference.put(reference, amount);
    refundCurrencyByReference.put(reference, currency);
  }

  /**
   * Primes a refund record and registers the deterministic client reference the adapter sends, so a
   * verify for a seeded refund resolves the same way it does against live Paystack.
   */
  public void seedRefund(
      java.util.UUID refundId, String transaction, String reference, long amount, String currency) {
    seedRefund(transaction, reference, amount, currency);
    clientReferenceByTransaction.put("tgthr-refund-" + refundId, transaction);
  }

  /** SUCCESS, NOT_FOUND or UNKNOWN. */
  public void resolveScenario(String scenario) {
    resolveScenario = scenario;
  }

  /** SUCCESS, FAILED, OTP, DUPLICATE or UNKNOWN. */
  public void transferScenario(String scenario) {
    transferScenario = scenario;
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

  private void handleResolve(HttpExchange exchange) throws IOException {
    String query = exchange.getRequestURI().getQuery();
    resolveQueries.add(query);
    switch (resolveScenario) {
      case "NOT_FOUND" ->
          respond(exchange, 404, Map.of("status", false, "message", "Cannot resolve account"));
      case "UNAUTHORIZED" ->
          respond(exchange, 401, Map.of("status", false, "message", "Invalid key"));
      case "RATE_LIMITED" ->
          respond(exchange, 429, Map.of("status", false, "message", "rate limited"));
      case "UNKNOWN" ->
          respond(exchange, 500, Map.of("status", false, "message", "temporary failure"));
      default ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  Map.of(
                      "account_number", queryParam(query, "account_number"),
                      "account_name", "Kofi Mensah",
                      "bank_code", queryParam(query, "bank_code"))));
    }
  }

  private static String queryParam(String query, String name) {
    if (query == null) {
      return "";
    }
    for (String part : query.split("&")) {
      String[] pair = part.split("=", 2);
      if (pair.length == 2 && pair[0].equals(name)) {
        return pair[1];
      }
    }
    return "";
  }

  private void handleTransferRecipient(HttpExchange exchange) throws IOException {
    recipientBodies.add(read(exchange.getRequestBody()));
    if ("RECIPIENT_RATE_LIMITED".equals(transferScenario)) {
      respond(exchange, 429, Map.of("status", false, "message", "rate limited"));
      return;
    }
    respond(
        exchange,
        201,
        Map.of(
            "status",
            true,
            "data",
            Map.of(
                "recipient_code", "RCP_FAKE",
                "type", "mobile_money",
                "currency", "GHS",
                "active", true)));
  }

  private void handleTransfer(HttpExchange exchange) throws IOException {
    String body = read(exchange.getRequestBody());
    transferBodies.add(body);
    JsonNode request = mapper.readTree(body);
    String reference = request.path("reference").asText();
    long amount = request.path("amount").asLong();
    transferAmountByReference.put(reference, amount);
    switch (transferScenario) {
      case "FAILED" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  Map.of(
                      "status", "failed",
                      "transfer_code", "TRF_FAKE_FAIL",
                      "reference", reference,
                      "failures", "declined")));
      case "OTP" ->
          respond(
              exchange,
              200,
              Map.of("status", true, "data", Map.of("status", "otp", "reference", reference)));
      case "DUPLICATE" ->
          respond(
              exchange,
              400,
              Map.of("status", false, "message", "Transfer reference already exists"));
      case "UNKNOWN" ->
          respond(exchange, 500, Map.of("status", false, "message", "temporary failure"));
      case "RATE_LIMITED" ->
          respond(exchange, 429, Map.of("status", false, "message", "rate limited"));
      case "MISMATCH" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  Map.of(
                      "status", "success",
                      "transfer_code", "TRF_FAKE_OK",
                      "reference", reference,
                      "amount", amount + 100,
                      "currency", "GHS")));
      default ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  Map.of(
                      "status", "success",
                      "transfer_code", "TRF_FAKE_OK",
                      "reference", reference,
                      "amount", amount,
                      "currency", "GHS")));
    }
  }

  private void handleTransferVerify(HttpExchange exchange) throws IOException {
    String reference = exchange.getRequestURI().getPath().substring("/transfer/verify/".length());
    boolean failed = "FAILED".equals(transferScenario);
    respond(
        exchange,
        200,
        Map.of(
            "status",
            true,
            "data",
            Map.of(
                "status",
                failed ? "failed" : "success",
                "transfer_code",
                failed ? "TRF_FAKE_FAIL" : "TRF_FAKE_OK",
                "reference",
                reference,
                "amount",
                transferAmountByReference.getOrDefault(reference, 0L),
                "currency",
                "GHS")));
  }

  private void handleRefund(HttpExchange exchange) throws IOException {
    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      handleRefundVerify(exchange);
      return;
    }
    String body = read(exchange.getRequestBody());
    refundBodies.add(body);
    JsonNode request = mapper.readTree(body);
    long amount = request.path("amount").asLong();
    String currency = request.path("currency").asText("GHS");
    String transaction = request.path("transaction").asText("");
    // Live Paystack generates its own refund id and echoes the caller-supplied reference
    // separately.
    String reference = "refund-" + UUID.randomUUID();
    String supplied = request.path("reference").asText("");
    if (!supplied.isBlank()) {
      clientReferenceByTransaction.put(supplied, transaction);
    }
    if (!transaction.isBlank()) {
      refundReferenceByTransaction.put(transaction, reference);
      refundAmountByTransaction.put(transaction, amount);
      refundCurrencyByTransaction.put(transaction, currency);
    }
    refundAmountByReference.put(reference, amount);
    refundCurrencyByReference.put(reference, currency);
    switch (refundScenario) {
      case "REQUEST_REJECTED" ->
          respond(exchange, 400, Map.of("status", false, "message", "refund rejected"));
      case "REQUEST_TIMEOUT" ->
          respond(exchange, 408, Map.of("status", false, "message", "request timed out"));
      case "ALREADY_EXISTS" ->
          respond(exchange, 409, Map.of("status", false, "message", "Refund already exists"));
      // Live Paystack answers a duplicate refund with 400 + code=transaction_reversed and the
      // existing refunds in data.existing_refunds, not the 409 the adapter used to assume. The
      // captured entries carry only id, reference and status — no amount and no currency — and the
      // reference is the one Paystack generated, not necessarily the caller's.
      case "ALREADY_EXISTS_LIVE" ->
          respond(
              exchange,
              400,
              Map.of(
                  "status",
                  false,
                  "message",
                  "A refund already exist for this transaction",
                  "type",
                  "api_error",
                  "code",
                  "transaction_reversed",
                  "data",
                  Map.of("existing_refunds", List.of(duplicateRefundEntry(reference)))));
      case "ALREADY_EXISTS_LIVE_AMBIGUOUS" ->
          respond(
              exchange,
              400,
              Map.of(
                  "status",
                  false,
                  "message",
                  "A refund already exist for this transaction",
                  "type",
                  "api_error",
                  "code",
                  "transaction_reversed",
                  "data",
                  Map.of(
                      "existing_refunds",
                      List.of(
                          duplicateRefundEntry("refund-duplicate-one"),
                          duplicateRefundEntry("refund-duplicate-two")))));
      // A proxy or load balancer can answer 400 with HTML. That is not evidence of a duplicate, so
      // the adapter must not classify it as one.
      case "BAD_REQUEST_UNPARSABLE" ->
          respondRaw(exchange, 400, "<html><body>Bad Request</body></html>");
      case "RATE_LIMITED" ->
          respond(exchange, 429, Map.of("status", false, "message", "rate limited"));
      case "SERVER_ERROR" ->
          respond(exchange, 500, Map.of("status", false, "message", "temporary failure"));
      case "PENDING" ->
          respond(exchange, 200, refundResponse("pending", reference, amount, currency));
      case "FAILED" ->
          respond(exchange, 200, refundResponse("failed", reference, amount, currency));
      case "AMOUNT_MISMATCH" ->
          respond(exchange, 200, refundResponse("processed", reference, amount + 1, currency));
      case "CURRENCY_MISMATCH" ->
          respond(exchange, 200, refundResponse("processed", reference, amount, "NGN"));
      case "MISSING_AMOUNT" ->
          respond(exchange, 200, refundResponse("processed", reference, amount, currency, false));
      default -> respond(exchange, 200, refundResponse("processed", reference, amount, currency));
    }
  }

  private void handleRefundVerify(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getQuery();
    if ("/refund".equals(path) && query != null && query.contains("reference=")) {
      // Live resolves a caller-supplied refund reference here; it is the only lookup available when
      // no provider reference was captured.
      String reference = transactionParameter(query.replace("reference=", "transaction="));
      String transaction = referenceToTransaction(reference);
      if (transaction.isEmpty()) {
        respond(exchange, 200, Map.of("status", true, "data", List.of()));
        return;
      }
      handleRefundList(exchange, transaction);
      return;
    }
    if ("/refund".equals(path) && query != null && query.contains("transaction=")) {
      handleRefundList(exchange, transactionParameter(query));
      return;
    }
    handleRefundObject(exchange, path.substring("/refund/".length()));
  }

  private String referenceToTransaction(String reference) {
    String byClient = clientReferenceByTransaction.get(reference);
    if (byClient != null) {
      return byClient;
    }
    for (Map.Entry<String, String> entry : refundReferenceByTransaction.entrySet()) {
      if (entry.getValue().equals(reference)) {
        return entry.getKey();
      }
    }
    return "";
  }

  /**
   * Paystack documents {@code GET /refund?transaction=} as an array of refunds and {@code GET
   * /refund/:id} as a single object; the fake mirrors both shapes.
   */
  private void handleRefundList(HttpExchange exchange, String transaction) throws IOException {
    String reference =
        refundReferenceByTransaction.getOrDefault(transaction, "refund-" + transaction);
    long amount = refundAmountByTransaction.getOrDefault(transaction, 0L);
    String currency = refundCurrencyByTransaction.getOrDefault(transaction, "GHS");
    switch (refundScenario) {
      case "SERVER_ERROR" ->
          respond(exchange, 500, Map.of("status", false, "message", "temporary failure"));
      case "REQUEST_TIMEOUT" ->
          respond(exchange, 408, Map.of("status", false, "message", "request timed out"));
      case "VERIFY_404" -> respond(exchange, 404, Map.of("status", false, "message", "not found"));
      case "VERIFY_200_FALSE" ->
          respond(exchange, 200, Map.of("status", false, "message", "refund not found"));
      case "VERIFY_EMPTY" -> respond(exchange, 200, Map.of("status", true, "data", List.of()));
      case "VERIFY_MULTIPLE" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "processed",
                          "refund-older",
                          amount - 1,
                          currency,
                          transaction,
                          "2026-01-01T00:00:00.000Z"),
                      refundEntry(
                          "processed",
                          reference,
                          amount,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
      case "VERIFY_MULTIPLE_MATCHING" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "pending",
                          "refund-stale",
                          amount,
                          currency,
                          transaction,
                          "2026-01-01T00:00:00.000Z"),
                      refundEntry(
                          "processed",
                          reference,
                          amount,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
      case "VERIFY_NO_MATCH" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "processed",
                          reference,
                          amount + 100,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
      case "VERIFY_WRONG_TRANSACTION" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "processed",
                          reference,
                          amount,
                          currency,
                          "other-transaction",
                          "2026-01-02T00:00:00.000Z"))));
      case "UNKNOWN_STATUS" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "needs-attention",
                          reference,
                          amount,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
      case "PENDING" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "pending",
                          reference,
                          amount,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
      case "FAILED" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "failed",
                          reference,
                          amount,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
      case "AMOUNT_MISMATCH" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "processed",
                          reference,
                          amount + 1,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
      case "CURRENCY_MISMATCH" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "processed",
                          reference,
                          amount,
                          "NGN",
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
      case "MISSING_AMOUNT" ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "processed",
                          reference,
                          amount,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z",
                          false))));
      default ->
          respond(
              exchange,
              200,
              Map.of(
                  "status",
                  true,
                  "data",
                  List.of(
                      refundEntry(
                          "processed",
                          reference,
                          amount,
                          currency,
                          transaction,
                          "2026-01-02T00:00:00.000Z"))));
    }
  }

  private void handleRefundObject(HttpExchange exchange, String reference) throws IOException {
    long amount = refundAmountByReference.getOrDefault(reference, 0L);
    String currency = refundCurrencyByReference.getOrDefault(reference, "GHS");
    switch (refundScenario) {
      case "SERVER_ERROR" ->
          respond(exchange, 500, Map.of("status", false, "message", "temporary failure"));
      case "REQUEST_TIMEOUT" ->
          respond(exchange, 408, Map.of("status", false, "message", "request timed out"));
      case "VERIFY_404" -> respond(exchange, 404, Map.of("status", false, "message", "not found"));
      case "VERIFY_200_FALSE" ->
          respond(exchange, 200, Map.of("status", false, "message", "refund not found"));
      case "UNKNOWN_STATUS" ->
          respond(exchange, 200, refundResponse("needs-attention", reference, amount, currency));
      case "PENDING" ->
          respond(exchange, 200, refundResponse("pending", reference, amount, currency));
      case "FAILED" ->
          respond(exchange, 200, refundResponse("failed", reference, amount, currency));
      case "AMOUNT_MISMATCH" ->
          respond(exchange, 200, refundResponse("processed", reference, amount + 1, currency));
      case "CURRENCY_MISMATCH" ->
          respond(exchange, 200, refundResponse("processed", reference, amount, "NGN"));
      case "MISSING_AMOUNT" ->
          respond(exchange, 200, refundResponse("processed", reference, amount, currency, false));
      default -> respond(exchange, 200, refundResponse("processed", reference, amount, currency));
    }
  }

  private static String transactionParameter(String query) {
    String transaction = query.substring(query.indexOf("transaction=") + "transaction=".length());
    int amp = transaction.indexOf('&');
    return amp >= 0 ? transaction.substring(0, amp) : transaction;
  }

  /** The shape live Paystack uses inside {@code data.existing_refunds}: id, reference, status. */
  private static Map<String, Object> duplicateRefundEntry(String id) {
    return Map.of("id", id, "reference", UUID.randomUUID().toString(), "status", "processing");
  }

  private static Map<String, Object> refundEntry(
      String status,
      String reference,
      long amount,
      String currency,
      String transaction,
      String createdAt) {
    return refundEntry(status, reference, amount, currency, transaction, createdAt, true);
  }

  private static Map<String, Object> refundEntry(
      String status,
      String reference,
      long amount,
      String currency,
      String transaction,
      String createdAt,
      boolean includeAmount) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", reference);
    entry.put("status", status);
    if (includeAmount) {
      entry.put("amount", amount);
    }
    entry.put("currency", currency);
    entry.put("reference", reference);
    entry.put("created_at", createdAt);
    entry.put("transaction", Map.of("reference", transaction));
    return entry;
  }

  private static Map<String, ?> refundResponse(
      String status, String reference, long amount, String currency) {
    return refundResponse(status, reference, amount, currency, true);
  }

  private static Map<String, ?> refundResponse(
      String status, String reference, long amount, String currency, boolean includeAmount) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", reference);
    data.put("status", status);
    if (includeAmount) {
      data.put("amount", amount);
    }
    data.put("currency", currency);
    return Map.of("status", true, "data", data);
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

  private void respondRaw(HttpExchange exchange, int statusCode, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html");
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
