import { createHmac, randomUUID } from "node:crypto";
import { createServer } from "node:http";

const PORT = Number(process.env.PORT ?? 8090);
const SECRET = process.env.PAYSTACK_SECRET_KEY ?? "test-secret";
const PAYSTACK_BASE = process.env.PAYSTACK_BASE_URL ?? "http://127.0.0.1:9099";

const contributions = new Map();

const GOAL = {
  slug: "e2e-maldives",
  title: "E2E Maldives",
  template: "TRIP",
  targetType: "OPEN",
  fixedAmountPesewas: null,
  targetAmountPesewas: 500000,
  minimumAmountPesewas: 1000,
  deadline: null,
  status: "OPEN",
  payoutRules: {
    destinationType: "BENEFICIARY_MOMO",
    secondApprover: "OFF",
    holdWindowMinutes: 60,
    surplusRule: "PAY_BENEFICIARY",
  },
};

function fees(amount) {
  const platform = Math.ceil((amount * 150) / 10000);
  const base = amount + platform;
  const total = Math.ceil((base * 10000) / 9805);
  return { platform, processing: total - base, total };
}

function status(contribution) {
  const body = {
    id: contribution.id,
    state: contribution.state,
    amountPesewas: contribution.amount,
    processingFeePesewas: contribution.processing,
    platformFeePesewas: contribution.platform,
    feePesewas: contribution.processing + contribution.platform,
    totalPesewas: contribution.total,
  };
  if (contribution.state === "CONFIRMED") {
    body.receipt = {
      reference: contribution.id,
      confirmedAt: contribution.confirmedAt,
    };
  }
  return body;
}

function goalView() {
  const confirmed = [...contributions.values()].filter((item) => item.state === "CONFIRMED");
  return {
    ...GOAL,
    raisedPesewas: confirmed.reduce((sum, item) => sum + item.amount, 0),
    contributorCount: confirmed.length,
    feed: confirmed.map((item) => ({
      displayName: item.displayName,
      anonymous: item.anonymous,
      amountPesewas: item.amount,
      at: item.confirmedAt,
    })),
  };
}

function json(response, statusCode, payload) {
  response.writeHead(statusCode, { "content-type": "application/json" });
  response.end(JSON.stringify(payload));
}

function read(request) {
  return new Promise((resolve) => {
    let body = "";
    request.on("data", (chunk) => {
      body += chunk;
    });
    request.on("end", () => resolve(body));
  });
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host}`);

  if (request.method === "GET" && url.pathname === "/v1/public/goals/e2e-maldives") {
    return json(response, 200, goalView());
  }

  if (
    request.method === "POST" &&
    url.pathname === "/v1/public/goals/e2e-maldives/contribution-quote"
  ) {
    const body = JSON.parse((await read(request)) || "{}");
    const amount = Number(body.amountPesewas);
    const breakdown = fees(amount);
    return json(response, 200, {
      amountPesewas: amount,
      processingFeePesewas: breakdown.processing,
      platformFeePesewas: breakdown.platform,
      totalPesewas: breakdown.total,
    });
  }

  if (
    request.method === "POST" &&
    url.pathname === "/v1/public/goals/e2e-maldives/contributions"
  ) {
    const body = JSON.parse((await read(request)) || "{}");
    const amount = Number(body.amountPesewas);
    const breakdown = fees(amount);
    const id = randomUUID();
    await fetch(`${PAYSTACK_BASE}/charge`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        reference: id,
        amount: breakdown.total,
        currency: "GHS",
        mobile_money: { phone: body.momoNumber, provider: "mtn" },
      }),
    });
    contributions.set(id, {
      id,
      amount,
      processing: breakdown.processing,
      platform: breakdown.platform,
      total: breakdown.total,
      state: "PENDING",
      displayName: body.displayName ?? null,
      anonymous: Boolean(body.anonymous),
      confirmedAt: null,
    });
    return json(response, 202, status(contributions.get(id)));
  }

  if (request.method === "GET" && url.pathname.startsWith("/v1/public/contributions/")) {
    const id = url.pathname.split("/").pop();
    const contribution = contributions.get(id);
    if (!contribution) {
      return json(response, 404, { code: "CONTRIBUTION_NOT_FOUND" });
    }
    return json(response, 200, status(contribution));
  }

  if (request.method === "POST" && url.pathname === "/v1/webhooks/momo") {
    const raw = await read(request);
    const signature = request.headers["x-paystack-signature"];
    const expected = createHmac("sha512", SECRET).update(raw).digest("hex");
    if (signature !== expected) {
      return json(response, 401, { code: "INVALID_SIGNATURE" });
    }
    const event = JSON.parse(raw);
    const contribution = contributions.get(event.data.reference);
    if (contribution && contribution.state === "PENDING" && event.event === "charge.success") {
      contribution.state = "CONFIRMED";
      contribution.confirmedAt = new Date().toISOString();
    }
    response.writeHead(204);
    response.end();
    return;
  }

  json(response, 404, { error: "not found" });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`web E2E stub listening on http://127.0.0.1:${PORT}`);
});
