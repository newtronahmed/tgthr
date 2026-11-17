import "server-only";

import { proxy, readBody } from "./proxy";

/**
 * Enrollment BFF. It deliberately forwards no cookies: the token is the only credential and the
 * response never sets an operator session.
 */
export async function enrollmentProxy(request: Request, path: string): Promise<Response> {
  const body = await readBody(request);
  if (body === null) {
    return new Response(null, { status: 413 });
  }
  return proxy(
    path,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body,
    },
    30_000,
  );
}
