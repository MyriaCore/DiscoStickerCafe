# Tasks

A spec-only change on `feature/login`: nothing outside `openspec/` changes. Commands run in Git Bash from the repo root.

## 1. Shorten the two requirements

- [x] 1.1 Finalize the delta specs `specs/gateway-routing/spec.md` and `specs/production-hosting/spec.md`, applying any edits from the maintainer's review of the proposal. Each delta holds one MODIFIED requirement with the same name, a description under 500 characters, and every existing scenario word for word, with no new scenarios. The deltas are checked before the wording is.
  Verify: `openspec validate tidy-long-requirements --strict` prints `Change 'tidy-long-requirements' is valid`.

- [x] 1.2 Review that no behavior was added or removed. Record a side-by-side in this task's notes: for each requirement, the old and new description, the sentence removed, and the existing scenario that still states each of its details. The scenarios are compared byte for byte against the main specs.
  Verify: `python -I` on a short script that extracts each requirement's scenarios from `openspec/specs/<cap>/spec.md` and from this change's delta prints `identical` for both capabilities. The recorded side-by-side shows every removed detail covered by a scenario that stays, and the maintainer confirms no behavior was added or removed.
  Notes (2026-10-11): the scenario check printed `gateway-routing: identical (5 scenarios, 1007 bytes)` and `production-hosting: identical (2 scenarios, 594 bytes)`.

  **gateway-routing, "Security headers on every HTTPS response"** (528 → 437 characters). The text is the same except for one removed sentence.

  | Old | New | Where the obligation lives now |
  |---|---|---|
  | "every HTTPS response from the gateway SHALL carry" the four headers | unchanged | requirement text |
  | "This SHALL hold for static files, responses proxied from the backend, and error responses." | removed | "every HTTPS response" in the text, plus the unchanged scenarios "Static page", "Proxied backend response" and "Error response" |
  | HSTS "SHALL NOT include `includeSubDomains` or `preload`" | unchanged | requirement text |
  | "only source of these four headers … locally or in production" | unchanged | requirement text, plus "Each header exactly once on proxied responses" and "Backend copies not passed on locally" |

  **production-hosting, "Production secrets live only on the server"** (566 → 485 characters). It is reworded, and no obligation is removed.

  | Old | New | Note |
  |---|---|---|
  | "SHALL exist only in `secrets.env` in the deployment checkout on the server" | same, continued in one sentence | unchanged |
  | "The file SHALL be owned by the deploy user with mode 600" | "owned by the deploy user with mode 600" | still normative under the same SHALL |
  | "SHALL be ignored by git" | "ignored by git" | still normative under the same SHALL |
  | "SHALL list the same variable names as the committed `secrets.env.example`" | "listing the same names as `secrets.env.example`" | "variable" dropped (same meaning). "committed" dropped: covered by secrets-handling's "Example file lists every secret name". |
  | "Values SHALL be generated on the server" | same | unchanged |
  | "except third-party-issued secrets (the Discord client secret), which SHALL be typed in by hand on the server without being echoed" | "a third-party-issued secret (the Discord client secret) SHALL instead be typed in on the server without echo" | same obligation. "By hand" is dropped from the text; the scenario still says the value is read from a prompt. |
  | "and SHALL NOT be stored on the maintainer's machine" | "and never stored on the maintainer's machine" | same prohibition under the same SHALL |
  | "No value SHALL appear in the repository, images, logs, or Claude Code sessions." | same | unchanged |

  Both scenarios ("File ownership and names", "Third-party secret entered without echo") are unchanged. The maintainer confirmed no behavior was added or removed (2026-10-11).

## Workflow follow-up

- Archive the change on `feature/login` with `/opsx:archive tidy-long-requirements`, syncing the delta specs into the main specs.
  Verify: `openspec validate --all --strict` prints `Totals: 7 passed, 0 failed (7 items)`.
