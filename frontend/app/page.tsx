"use client";

import { useEffect, useState } from "react";

import { getMe, loginMessage, signOut, type Me } from "../lib/session";

type SessionState =
  | { status: "loading" }
  | { status: "signed-out" }
  | { status: "signed-in"; me: Me }
  | { status: "unavailable" };

export default function Home() {
  const [session, setSession] = useState<SessionState>({ status: "loading" });
  const [notice, setNotice] = useState<string | null>(null);

  async function refresh() {
    try {
      const me = await getMe();
      setSession(me ? { status: "signed-in", me } : { status: "signed-out" });
    } catch {
      setSession({ status: "unavailable" });
    }
  }

  useEffect(() => {
    // The only read of the query string: the code reaches the page only as fixed text.
    setNotice(loginMessage(new URLSearchParams(window.location.search).get("login")));
    void refresh();
  }, []);

  async function handleSignOut() {
    setNotice(null);
    try {
      await signOut();
    } catch {
      setNotice("Sign-out didn't complete. Please try again.");
    }
    await refresh();
  }

  return (
    <main>
      <h1>DiscoSticker.cafe</h1>
      {notice && <p role="alert">{notice}</p>}
      {session.status === "loading" && <p>Checking sign-in…</p>}
      {session.status === "signed-out" && (
        <p>
          {/* A full navigation, not fetch: the OAuth flow is a chain of top-level redirects. */}
          <a href="/oauth2/authorization/discord">Sign in with Discord</a>
        </p>
      )}
      {session.status === "signed-in" && (
        <p>
          Signed in as {session.me.displayName}.{" "}
          <button type="button" onClick={handleSignOut}>
            Sign out
          </button>
        </p>
      )}
      {session.status === "unavailable" && <p>Sign-in is unavailable right now. Please try again later.</p>}
      <p>Sticker uploads are coming soon.</p>
    </main>
  );
}
