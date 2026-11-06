import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

const chunkDir = ".next/static/chunks";
const budgets = {
  jsBytes: 220 * 1024,
  cssBytes: 20 * 1024,
};

let js = 0;
let css = 0;
for (const file of readdirSync(chunkDir)) {
  const bytes = gzipSync(readFileSync(join(chunkDir, file))).length;
  if (file.endsWith(".js")) {
    js += bytes;
  } else if (file.endsWith(".css")) {
    css += bytes;
  }
}

const report = `client JS ${(js / 1024).toFixed(1)} KB gz (budget ${(budgets.jsBytes / 1024).toFixed(0)}), CSS ${(css / 1024).toFixed(1)} KB gz (budget ${(budgets.cssBytes / 1024).toFixed(0)})`;
console.log(report);

if (js > budgets.jsBytes || css > budgets.cssBytes) {
  console.error("Web performance budget exceeded");
  process.exit(1);
}
