# Proposal

## Why

`openspec validate --all --strict` fails on two main-spec requirements whose descriptions run past OpenSpec's 500-character limit. Both grew past it when add-discord-login appended a sentence:
- gateway-routing, "Security headers on every HTTPS response" (528 characters)
- production-hosting, "Production secrets live only on the server" (566 characters)

This change restates each in under 500 characters without changing what it requires, so strict validation passes again.

## What Changes

- **gateway-routing, "Security headers on every HTTPS response"** (528 → 437 characters): removes the sentence "This SHALL hold for static files, responses proxied from the backend, and error responses." "Every HTTPS response" already includes those three kinds, and the existing scenarios "Static page", "Proxied backend response" and "Error response" state each one. All other text and all five scenarios are unchanged, word for word.
- **production-hosting, "Production secrets live only on the server"** (566 → 485 characters): rewords the text without removing any obligation. The file properties stay normative in the requirement text: owned by the deploy user, mode 600, ignored by git, and the same names as `secrets.env.example`.
  - The third-party clause is condensed. "typed in by hand on the server without being echoed and SHALL NOT be stored" becomes "typed in on the server without echo and never stored", still under the same SHALL.
  - The only word dropped is "committed" before `secrets.env.example`. That obligation is covered by secrets-handling's "Example file lists every secret name".
  - Both scenarios are unchanged, byte for byte.
- No requirement is added, removed or renamed, and no scenario is added or changed. Each requirement still states one behavior, so nothing needs splitting into a second requirement.

## Out of scope

- Any change to behavior, code, configuration, compose files, the runbook (`docs/deploy.md`), `docs/decisions.md`, `CLAUDE.md` or `openspec/config.yaml`.
- Rewording any other requirement or scenario, including ones that pass strict validation.
- Deploying anything. Production is untouched.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `gateway-routing`: "Security headers on every HTTPS response" is shortened. Its behavior and scenarios are unchanged.
- `production-hosting`: "Production secrets live only on the server" is reworded more concisely. Its behavior and scenarios are unchanged.

## Impact

- Spec files only: the two main specs after archive, plus this change's delta specs. Nothing outside `openspec/` changes.

## Security impact

None. Both requirements keep every security property:
- **Headers:** the four headers on every HTTPS response, HSTS without `includeSubDomains` or `preload`, and nginx as their only source.
- **Secrets:** server-only `secrets.env` with owner, mode, git-ignore and names; generated values; third-party secrets typed in without echo; no values anywhere else.

The only details that leave the requirement text are gateway-routing's three response kinds and production-hosting's word "committed". The kinds are stated by scenarios that stay; "committed" is required by secrets-handling.
