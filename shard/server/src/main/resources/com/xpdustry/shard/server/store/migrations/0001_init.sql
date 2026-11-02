-- Players, sessions, moderation, notes and chat (note 34 "Logical schema"). Times are UTC epoch milliseconds.
-- Never edit this file once released: its checksum is recorded in schema_migrations. Add a new migration instead.

-- One row per client UUID. A UUID is an opaque client string, not proof of identity; usid_hash is the SHA-256 of
-- the last USID the client sent (the raw USID is never stored).
CREATE TABLE players(
    id INTEGER PRIMARY KEY,
    uuid TEXT NOT NULL UNIQUE CHECK(length(uuid) BETWEEN 1 AND 64),
    usid_hash TEXT CHECK(usid_hash IS NULL OR length(usid_hash) = 64),
    last_name TEXT NOT NULL CHECK(length(last_name) <= 256),
    last_address TEXT CHECK(last_address IS NULL OR length(last_address) <= 64),
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL CHECK(last_seen >= first_seen),
    times_joined INTEGER NOT NULL DEFAULT 0 CHECK(times_joined >= 0),
    times_kicked INTEGER NOT NULL DEFAULT 0 CHECK(times_kicked >= 0)
) STRICT;

-- Every name a player joined with. plain is the name without color tags, trimmed and in lower case, for search.
CREATE TABLE player_names(
    player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK(length(name) <= 256),
    plain TEXT NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    PRIMARY KEY(player_id, name)
) STRICT, WITHOUT ROWID;
CREATE INDEX player_names_plain ON player_names(plain);

-- Every address a player joined from, in canonical form.
CREATE TABLE player_addresses(
    player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    address TEXT NOT NULL CHECK(length(address) <= 64),
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    PRIMARY KEY(player_id, address)
) STRICT, WITHOUT ROWID;
CREATE INDEX player_addresses_address ON player_addresses(address);

-- One row per connection that got past admission.
CREATE TABLE sessions(
    id INTEGER PRIMARY KEY,
    player_id INTEGER NOT NULL REFERENCES players(id),
    world TEXT,
    address TEXT NOT NULL,
    name TEXT NOT NULL CHECK(length(name) <= 256),
    locale TEXT CHECK(locale IS NULL OR length(locale) <= 32),
    client_version INTEGER,
    mobile INTEGER NOT NULL CHECK(mobile IN (0, 1)),
    modded INTEGER NOT NULL CHECK(modded IN (0, 1)),
    started_at INTEGER NOT NULL,
    ended_at INTEGER CHECK(ended_at IS NULL OR ended_at >= started_at),
    end_reason TEXT CHECK(end_reason IS NULL OR length(end_reason) <= 256)
) STRICT;
CREATE INDEX sessions_player ON sessions(player_id, started_at);

-- Bans by UUID, by address or by CIDR subnet. A ban is never deleted: it expires or is revoked, with attribution.
CREATE TABLE bans(
    id INTEGER PRIMARY KEY,
    kind TEXT NOT NULL CHECK(kind IN ('uuid', 'address', 'subnet')),
    target TEXT NOT NULL CHECK(length(target) BETWEEN 1 AND 64),
    player_id INTEGER REFERENCES players(id),
    reason TEXT NOT NULL DEFAULT '' CHECK(length(reason) <= 1024),
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL CHECK(length(created_by) BETWEEN 1 AND 128),
    expires_at INTEGER CHECK(expires_at IS NULL OR expires_at > created_at),
    revoked_at INTEGER,
    revoked_by TEXT CHECK(revoked_by IS NULL OR length(revoked_by) BETWEEN 1 AND 128),
    CHECK((revoked_at IS NULL) = (revoked_by IS NULL))
) STRICT;
CREATE INDEX bans_target ON bans(kind, target);
CREATE INDEX bans_open ON bans(revoked_at, expires_at);

-- Admins, pinned to the USID they had when granted (vanilla checks the UUID and the USID together).
CREATE TABLE admins(
    player_id INTEGER PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
    usid_hash TEXT NOT NULL CHECK(length(usid_hash) = 64),
    granted_at INTEGER NOT NULL,
    granted_by TEXT NOT NULL CHECK(length(granted_by) BETWEEN 1 AND 128)
) STRICT;

-- The whitelist, keyed like vanilla's by UUID and USID together.
CREATE TABLE whitelist(
    player_id INTEGER PRIMARY KEY REFERENCES players(id) ON DELETE CASCADE,
    usid_hash TEXT NOT NULL CHECK(length(usid_hash) = 64),
    added_at INTEGER NOT NULL,
    added_by TEXT NOT NULL CHECK(length(added_by) BETWEEN 1 AND 128)
) STRICT;

-- Moderator notes on a player.
CREATE TABLE notes(
    id INTEGER PRIMARY KEY,
    player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    author TEXT NOT NULL CHECK(length(author) BETWEEN 1 AND 128),
    created_at INTEGER NOT NULL,
    body TEXT NOT NULL CHECK(length(body) BETWEEN 1 AND 4000)
) STRICT;
CREATE INDEX notes_player ON notes(player_id, created_at);

-- Chat, kept out of the general logs (note 34). delivered = 0 when a filter or rate limit stopped the message.
CREATE TABLE chat_log(
    id INTEGER PRIMARY KEY,
    at INTEGER NOT NULL,
    world TEXT,
    player_id INTEGER REFERENCES players(id) ON DELETE SET NULL,
    session_id INTEGER REFERENCES sessions(id) ON DELETE SET NULL,
    channel TEXT NOT NULL CHECK(channel IN ('global', 'team', 'whisper', 'command', 'system')),
    message TEXT NOT NULL CHECK(length(message) <= 2048),
    delivered INTEGER NOT NULL CHECK(delivered IN (0, 1))
) STRICT;
CREATE INDEX chat_log_at ON chat_log(at);
CREATE INDEX chat_log_player ON chat_log(player_id, at);
