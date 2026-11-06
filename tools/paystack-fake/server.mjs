import { createHmac } from "node:crypto";
import { createServer } from "node:http";

const PORT = Number(process.env.PORT ?? 9099);
const SECRET = process.env.PAYSTACK_SECRET_KEY ?? "test-secret";
const WEBHOOK_URL = process.env.WEBHOOK_URL ?? "http://127.0.0.1:8090/v1/webhooks/momo";

const charges = new Map();

function read(request) {
  return new Promise((resolve) => {
    let body = "";
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => resolve(body));
  });
}

function json(response, status, payload) {
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(payload));
}

const server = createServer(async (request, response) => {
  if (request.method === "POST" && request.url === "/charge") {
    const body = JSON.parse((await read(request)) || "{}");
    charges.set(body.reference, {
      amount: body.amount ?? 0,
      status: "pending",
    });
    return json(response, 200, {
      status: true,
      data: { reference: body.reference, status: "pending" },
    });
  }

  if (request.method === "GET" && request.url.startsWith("/transaction/verify/")) {
    const reference = decodeURIComponent(request.url.split("/").pop());
    const charge = charges.get(reference) ?? { amount: 0, status: "success" };
    const status = charge.status === "pending" ? "success" : charge.status;
    return json(response, 200, {
      status: true,
      data: { reference, status, amount: charge.amount, currency: "GHS" },
    });
  }

  if (request.method === "POST" && request.url === "/simulate") {
    const body = JSON.parse((await read(request)) || "{}");
    const charge = charges.get(body.reference);
    if (!charge) {
      return json(response, 404, { error: "unknown reference" });
    }
    const success = body.outcome !== "failed";
    charge.status = success ? "success" : "failed";
    const payload = JSON.stringify({
      event: success ? "charge.success" : "charge.failed",
      data: {
        reference: body.reference,
        status: success ? "success" : "failed",
        amount: charge.amount,
        currency: "GHS",
        paid_at: new Date().toISOString(),
      },
    });
    const signature = createHmac("sha512", SECRET).update(payload).digest("hex");
    const callback = await fetch(WEBHOOK_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-paystack-signature": signature,
      },
      body: payload,
    });
    return json(response, 200, { status: callback.status });
  }

  return json(response, 404, { error: "not found" });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`fake paystack listening on http://127.0.0.1:${PORT}`);
});
