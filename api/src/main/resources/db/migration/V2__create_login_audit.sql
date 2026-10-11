-- One row per login attempt where Discord confirmed the account's identity.
-- Failures before that (bad state, provider errors, denied consent) are logged, not audited,
-- so anonymous requests can't fill this table. No IP address column.
-- No foreign key to users: a too-new account is audited but never gets a users row.
create table login_audit (
    id          bigint generated always as identity primary key,
    occurred_at timestamptz not null default now(),
    discord_id  bigint      not null,
    outcome     text        not null check (outcome in ('accepted', 'rejected')),
    reason      text        check (reason in ('banned', 'too_new')),
    check ((outcome = 'accepted') = (reason is null))
);
