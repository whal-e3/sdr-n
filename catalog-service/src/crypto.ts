import type { CatalogSignature } from "./types.ts";

function base64Bytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value.replace(/\s/g, ""));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function gzipJson(value: unknown, maxPlainBytes?: number): Promise<Uint8Array<ArrayBuffer>> {
  const plain = new TextEncoder().encode(JSON.stringify(value));
  if (maxPlainBytes !== undefined && plain.byteLength > maxPlainBytes) {
    throw new Error("Catalog exceeds Android uncompressed size limit");
  }
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(plain);
      controller.close();
    },
  });
  // Workers' CompressionStream accepts BufferSource; ReadableStream.pipeThrough
  // currently types its input more narrowly even though Uint8Array is valid.
  const compressor = new CompressionStream("gzip") as unknown as ReadableWritablePair<Uint8Array, Uint8Array>;
  return new Uint8Array(await new Response(stream.pipeThrough(compressor)).arrayBuffer());
}

export async function signCatalog(
  bytes: Uint8Array<ArrayBuffer>,
  privateKeyPkcs8Base64: string,
  keyId: string,
  sequence: number,
): Promise<CatalogSignature> {
  if (!privateKeyPkcs8Base64 || !/^[a-zA-Z0-9._-]{1,64}$/.test(keyId)) {
    throw new Error("Catalog signing key or key ID is not configured");
  }
  const key = await crypto.subtle.importKey(
    "pkcs8",
    base64Bytes(privateKeyPkcs8Base64),
    { name: "Ed25519" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(await crypto.subtle.sign("Ed25519", key, bytes));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return {
    schemaVersion: 1,
    sequence,
    algorithm: "Ed25519",
    keyId,
    sha256: [...digest].map((part) => part.toString(16).padStart(2, "0")).join(""),
    signature: base64Url(signature),
  };
}
