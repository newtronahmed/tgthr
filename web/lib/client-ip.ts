/**
 * The client address used for per-IP rate limiting.
 *
 * Only the platform edge may set `x-real-ip`; everything a browser sends is attacker-controlled, so
 * `x-forwarded-for` is deliberately **not** consulted. Honouring it let a single caller mint a new
 * rate-limit bucket per request, defeating the limit.
 *
 * When no trustworthy address is available this returns `undefined` and callers must fail closed
 * rather than fall back to a header the caller controls. See `backendHeaders` for how the BFF tells
 * the backend which address to limit on.
 */
export function clientIp(request: Request): string | undefined {
  const platformIp = request.headers.get("x-real-ip");
  if (!platformIp) {
    return undefined;
  }
  const trimmed = platformIp.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}
