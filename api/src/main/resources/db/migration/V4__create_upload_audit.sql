-- One row per upload attempt by a signed-in user that passed the CSRF check. Signed-out and
-- CSRF-rejected requests, and bodies nginx refuses, are not audited, so anonymous requests
-- can't fill this table. No IP address column.
-- No foreign keys: audit rows outlive stickers and accounts.
-- original_sha256 and original_bytes describe the uploaded file; they are null when its bytes
-- were never read (too_large). The original bytes themselves are never stored.
create table upload_audit (
    id                bigint      generated always as identity primary key,
    occurred_at       timestamptz not null default now(),
    discord_id        bigint      not null,
    outcome           text        not null check (outcome in ('accepted', 'rejected', 'failed')),
    reason            text        check (reason in ('banned', 'too_large', 'unsupported_type',
                          'dimensions_too_large', 'unreadable', 'unsupported_color',
                          'rate_limited', 'busy', 'invalid_request', 'error')),
    original_sha256   bytea       check (octet_length(original_sha256) = 32),
    original_bytes    bigint      check (original_bytes >= 0),
    sticker_public_id text,
    check ((outcome = 'accepted') = (reason is null)),
    check ((outcome = 'failed') = (reason = 'error')),
    check ((outcome = 'accepted') = (sticker_public_id is not null)),
    check (outcome <> 'accepted' or original_sha256 is not null)
);

-- The hourly limit counts a user's accepted uploads in the last 60 minutes.
create index upload_audit_accepted_recent on upload_audit (discord_id, occurred_at)
    where outcome = 'accepted';
