// The api's session endpoints. Same origin through nginx, so cookies flow with no CORS.

export type Me = { discordId: string; displayName: string };

/** The signed-in user, or null when signed out (401). */
export async function getMe(): Promise<Me | null> {
  const response = await fetch("/api/me", { credentials: "same-origin", cache: "no-store" });
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`GET /api/me failed: ${response.status}`);
  }
  return (await response.json()) as Me;
}

/** Ends the session. The api requires the CSRF token from the XSRF-TOKEN cookie as a header. */
export async function signOut(): Promise<void> {
  const response = await fetch("/api/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { "X-XSRF-TOKEN": readCookie("XSRF-TOKEN") ?? "" },
  });
  if (response.status !== 204) {
    throw new Error(`POST /api/logout failed: ${response.status}`);
  }
}

/**
 * Fixed text for the api's ?login= codes. Any other value gets the generic message, so the
 * parameter's own text never reaches the page.
 */
export function loginMessage(code: string | null): string | null {
  switch (code) {
    case null:
      return null;
    case "banned":
      return "This Discord account can't sign in.";
    case "too_new":
      return "Discord accounts must be at least 7 days old to sign in.";
    case "error":
      return "Sign-in didn't complete. Please try again.";
    default:
      return "Sign-in failed. Please try again.";
  }
}

function readCookie(name: string): string | null {
  for (const part of document.cookie.split(";")) {
    const [key, ...value] = part.trim().split("=");
    if (key === name) {
      return decodeURIComponent(value.join("="));
    }
  }
  return null;
}
