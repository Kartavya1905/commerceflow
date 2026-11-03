// Generates the typed API client's types from the OpenAPI contracts in ../contracts, one file
// per service. The contracts are the source of truth; never edit src/api/schema by hand.
// CI regenerates and fails if the committed files differ.
import { execFileSync } from "node:child_process";
import { readdirSync } from "node:fs";
import { join } from "node:path";

const contracts = process.env.CONTRACTS_DIR ?? "../contracts/src/main/resources/openapi";
const out = "src/api/schema";
for (const file of readdirSync(contracts).filter((f) => f.endsWith(".yaml"))) {
  const name = file.replace(/\.yaml$/, "");
  execFileSync(
    "npx",
    ["openapi-typescript", join(contracts, file), "--output", join(out, `${name}.ts`)],
    { stdio: "inherit" },
  );
}
