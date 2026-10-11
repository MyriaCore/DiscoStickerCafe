"use client";

import { useEffect, useRef, useState, type SubmitEvent } from "react";

import { getMe, loginMessage, signOut, type Me } from "../lib/session";
import {
  getLimits,
  megabytes,
  precheck,
  typeNames,
  uploadMessage,
  uploadSticker,
  type Limits,
} from "../lib/stickers";

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

  function handleSessionEnded() {
    setNotice("Your session has ended. Please sign in again.");
    void refresh();
  }

  return (
    <main className="page">
      <section className="splash">
        <img className="splash-logo" src="/discosticker-logo.svg" alt="" />
        <div>
          <h1>Your pics, sticker-sized.</h1>
          <p className="splash-line">Upload a pic and get a link that shows up sticker-sized in Discord.</p>
        </div>
      </section>
      {notice && <p className="alert" role="alert">{notice}</p>}
      {session.status === "loading" && <p className="status">Checking sign-in…</p>}
      {session.status === "signed-out" && (
        <p className="session">
          {/* A full navigation, not fetch: the OAuth flow is a chain of top-level redirects. */}
          <a className="button button-filled" href="/oauth2/authorization/discord">Sign in with Discord</a>
        </p>
      )}
      {session.status === "signed-in" && (
        <>
          <p className="session">
            Signed in as {session.me.displayName}.{" "}
            <button className="button button-outlined" type="button" onClick={handleSignOut}>
              Sign out
            </button>
          </p>
          <StickerUpload onSessionEnded={handleSessionEnded} />
        </>
      )}
      {session.status === "unavailable" && <p className="status">Sign-in is unavailable right now. Please try again later.</p>}
    </main>
  );
}

/**
 * The upload form. Every type and number on it comes from the api's limits, so it always matches
 * what the server enforces; without them the form isn't shown. Messages are fixed text chosen by the
 * api's reason codes, never text from a response or the file name.
 */
function StickerUpload({ onSessionEnded }: { onSessionEnded: () => void }) {
  const [limits, setLimits] = useState<Limits | null | "loading">("loading");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const linkInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // Also sets the XSRF-TOKEN cookie the upload sends back as a header.
    void getLimits().then(setLimits);
  }, []);

  if (limits === "loading") {
    return <p className="status">Loading the upload form…</p>;
  }
  if (limits === null) {
    return <p className="status">Uploads are unavailable right now. Please try again later.</p>;
  }
  const loaded: Limits = limits;

  function handleFile(chosen: File | null) {
    setFile(chosen);
    setLink(null);
    setCopied(false);
    // Convenience only: refuse in the browser what the server would refuse anyway.
    setMessage(chosen ? precheck(chosen, loaded) : null);
  }

  async function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file || precheck(file, loaded) !== null) {
      return;
    }
    setMessage(null);
    setLink(null);
    setCopied(false);
    setBusy(true);
    const result = await uploadSticker(file);
    setBusy(false);
    if (result.kind === "accepted") {
      // Our own origin plus the api's checked path; never a host from the response.
      setLink(new URL(result.path, window.location.origin).toString());
      return;
    }
    if (result.kind === "rejected" && result.status === 401) {
      onSessionEnded();
      return;
    }
    setMessage(uploadMessage(result, loaded));
  }

  async function handleCopy() {
    if (!link) {
      return;
    }
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
    } catch {
      linkInput.current?.select();
    }
  }

  const size = loaded.stickerSize;
  return (
    <section className="card" aria-labelledby="upload-heading">
      <h2 id="upload-heading">Make a sticker</h2>
      <p className="hint">
        {typeNames(loaded)} images up to {megabytes(loaded)} MB, {loaded.uploadsPerHour} uploads per hour. Images
        larger than {size} × {size} pixels are shrunk to fit.
      </p>
      <p className="hint">Animated images become a still image.</p>
      <form className="upload-form" onSubmit={handleSubmit}>
        <input
          className="file-input"
          type="file"
          aria-label="Image to upload"
          // In the api's order, JPEG first: Safari converts HEIC photos to the first listed type.
          accept={loaded.acceptedTypes.join(",")}
          onChange={(event) => handleFile(event.target.files?.[0] ?? null)}
        />{" "}
        <button className="button button-filled" type="submit" disabled={!file || busy || message !== null}>
          {busy ? "Uploading…" : "Upload"}
        </button>
      </form>
      {message && <p className="alert" role="alert">{message}</p>}
      {link && (
        <p className="result">
          <label className="result-label">
            Your sticker link:{" "}
            <input className="result-link" ref={linkInput} type="text" readOnly value={link} size={link.length} onFocus={(e) => e.target.select()} />
          </label>{" "}
          <button className="button button-filled" type="button" onClick={handleCopy}>
            {copied ? "Copied" : "Copy"}
          </button>
        </p>
      )}
    </section>
  );
}
