import { createServer as createHttpServer } from "http";
import { createServer as createHttpsServer } from "https";
import { readFileSync } from "fs";

/** TLS configuration is fail-closed: a requested but incomplete TLS setup never falls back to ws. */
export function createRelayServer(env: NodeJS.ProcessEnv = process.env) {
  if (env.USE_TLS !== "true") return createHttpServer();
  if (!env.SSL_CERT || !env.SSL_KEY) {
    throw new Error("USE_TLS=true requires SSL_CERT and SSL_KEY paths");
  }
  return createHttpsServer({
    cert: readFileSync(env.SSL_CERT),
    key: readFileSync(env.SSL_KEY),
    minVersion: "TLSv1.2",
  });
}
