// The api's sticker endpoints. Every type and number shown comes from GET /api/stickers/limits,
// the settings the server enforces; none is written into the page.

import { readCookie } from "./cookies";

export type Limits = {
  acceptedTypes: string[];
  maxBytes: number;
  uploadsPerHour: number;
  stickerSize: number;
};

export type UploadResult =
  | { kind: "accepted"; path: string; width: number; height: number }
  | { kind: "rejected"; status: number; code: string | null }
  | { kind: "network-error" };

/** The only shape of sticker path the page will turn into a link. */
const STICKER_PATH = /^\/s\/[A-Za-z0-9_-]{21}[AQgw]\.png$/;

const TYPE_LABELS: Record<string, string> = { "image/jpeg": "JPEG", "image/png": "PNG" };

/** The upload limits, or null when they can't be loaded (then the form isn't shown). */
export async function getLimits(): Promise<Limits | null> {
  try {
    const response = await fetch("/api/stickers/limits", { credentials: "same-origin", cache: "no-store" });
    if (!response.ok) {
      return null;
    }
    const limits = (await response.json()) as Limits;
    const valid =
      Array.isArray(limits.acceptedTypes) &&
      [limits.maxBytes, limits.uploadsPerHour, limits.stickerSize].every((n) => Number.isInteger(n) && n > 0);
    return valid ? limits : null;
  } catch {
    return null;
  }
}

/** Uploads one file. The api needs the CSRF token from the XSRF-TOKEN cookie as a header. */
export async function uploadSticker(file: File): Promise<UploadResult> {
  const body = new FormData();
  body.append("file", file);
  let response: Response;
  try {
    response = await fetch("/api/stickers", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-XSRF-TOKEN": readCookie("XSRF-TOKEN") ?? "" },
      body,
    });
  } catch {
    return { kind: "network-error" };
  }
  if (response.status === 201) {
    const created = (await response.json()) as { path: unknown; width: unknown; height: unknown };
    if (typeof created.path === "string" && STICKER_PATH.test(created.path)) {
      return { kind: "accepted", path: created.path, width: Number(created.width), height: Number(created.height) };
    }
    return { kind: "rejected", status: response.status, code: null };
  }
  let code: string | null = null;
  if (response.headers.get("Content-Type")?.startsWith("application/json")) {
    try {
      const json = (await response.json()) as { error?: unknown };
      code = typeof json.error === "string" ? json.error : null;
    } catch {
      code = null;
    }
  }
  return { kind: "rejected", status: response.status, code };
}

/** "JPEG and PNG", from the accepted types; unknown types are left out rather than shown raw. */
export function typeNames(limits: Limits): string {
  const names = limits.acceptedTypes.map((type) => TYPE_LABELS[type]).filter((name) => name !== undefined);
  return names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}` : (names[0] ?? "");
}

/** The size limit in MB (binary, as the server counts it): "10", or one decimal place. */
export function megabytes(limits: Limits): string {
  const mb = limits.maxBytes / (1024 * 1024);
  return Number.isInteger(mb) ? String(mb) : mb.toFixed(1);
}

/**
 * A message when the browser can already tell the file will be refused, else null. A convenience
 * only: the server checks every file that is sent, and a file the browser reports no type for is sent.
 */
export function precheck(file: File, limits: Limits): string | null {
  if (file.size > limits.maxBytes) {
    return tooLargeMessage(limits);
  }
  if (file.type !== "" && !limits.acceptedTypes.includes(file.type)) {
    return `Only ${typeNames(limits)} images can be uploaded.`;
  }
  return null;
}

/**
 * Fixed text for each outcome. Only the api's reason codes and the limits' numbers choose the
 * message; no text from a response body or the file name ever reaches the page.
 */
export function uploadMessage(result: Exclude<UploadResult, { kind: "accepted" }>, limits: Limits): string {
  if (result.kind === "network-error") {
    return `Upload failed. Please try again. Files must be under ${megabytes(limits)} MB.`;
  }
  // Any 413 means too large: the api's JSON, or the gateway's own HTML page for a far larger body.
  if (result.status === 413) {
    return tooLargeMessage(limits);
  }
  switch (result.code) {
    case "unsupported_type":
      return `Only ${typeNames(limits)} images can be uploaded.`;
    case "dimensions_too_large":
      return "That image's dimensions are too large.";
    case "unreadable":
      return "That image couldn't be read. It may be damaged.";
    case "unsupported_color":
      return "CMYK JPEGs aren't supported. Save it as an RGB JPEG or a PNG.";
    case "rate_limited":
      return `You've reached ${limits.uploadsPerHour} uploads this hour. Try again later.`;
    case "busy":
      return "The server is busy. Try again in a moment.";
    case "banned":
      return "This Discord account can't upload stickers.";
    default:
      return "Upload failed. Please try again.";
  }
}

function tooLargeMessage(limits: Limits): string {
  return `That file is over ${megabytes(limits)} MB.`;
}
