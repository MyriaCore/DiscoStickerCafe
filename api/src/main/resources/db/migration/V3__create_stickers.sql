-- One row per accepted sticker. Its PNG is in object storage under object_key, written before
-- this row is committed, so a row always has its object.
-- public_id is the only identifier that appears in URLs: 128 random bits as 22 base64url
-- characters. The check allows only the canonical encoding (the last character carries 2 bits),
-- so each sticker has exactly one URL. id and owner_id never leave the api.
create table stickers (
    id          bigint      generated always as identity primary key,
    public_id   text        not null unique check (public_id ~ '^[A-Za-z0-9_-]{21}[AQgw]$'),
    owner_id    bigint      not null references users (id),
    object_key  text        not null unique,
    width       integer     not null check (width > 0),
    height      integer     not null check (height > 0),
    png_bytes   integer     not null check (png_bytes > 0),
    created_at  timestamptz not null default now()
);

create index stickers_owner_created on stickers (owner_id, created_at);
