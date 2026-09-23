import { generateKeyPairSync } from "node:crypto";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const workspaceRoot = dirname(projectRoot);
const argument = process.argv[2];
if (!argument) {
  console.error("Usage: node scripts/generate-key.mjs <output-directory-outside-workspace>");
  process.exit(2);
}
const outputDirectory = resolve(argument);
const insideWorkspace = relative(workspaceRoot, outputDirectory);
if (!isAbsolute(outputDirectory) || insideWorkspace === "" || (!insideWorkspace.startsWith(`..${sep}`) && insideWorkspace !== "..")) {
  console.error("Choose a directory outside the workspace so the private key cannot be committed accidentally.");
  process.exit(2);
}

const privatePath = resolve(outputDirectory, "catalog-private.pkcs8.b64");
const publicPath = resolve(outputDirectory, "catalog-public.raw.b64");
if (existsSync(privatePath) || existsSync(publicPath)) {
  console.error("Key files already exist; choose a new output directory for key rotation.");
  process.exit(2);
}
const { privateKey, publicKey } = generateKeyPairSync("ed25519");
mkdirSync(outputDirectory, { recursive: true, mode: 0o700 });
writeFileSync(privatePath, privateKey.export({ type: "pkcs8", format: "der" }).toString("base64") + "\n", {
  flag: "wx",
  mode: 0o600,
});
const publicJwk = publicKey.export({ format: "jwk" });
if (!publicJwk.x) throw new Error("Generated Ed25519 public key is missing its raw bytes");
writeFileSync(publicPath, Buffer.from(publicJwk.x, "base64url").toString("base64") + "\n", {
  flag: "wx",
  mode: 0o644,
});
console.log(`Private key: ${privatePath}`);
console.log(`Public key for Android pin: ${publicPath}`);
