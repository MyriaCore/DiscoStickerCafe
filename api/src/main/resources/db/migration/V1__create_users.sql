-- One row per Discord account that has signed in, or that the maintainer banned by hand.
-- Deliberately minimal: no email, name, avatar, tokens or IP addresses.
-- last_login_at is null for an account banned before it ever signed in.
create table users (
    id            bigint generated always as identity primary key,
    discord_id    bigint      not null unique,
    created_at    timestamptz not null default now(),
    last_login_at timestamptz,
    banned_at     timestamptz
);
