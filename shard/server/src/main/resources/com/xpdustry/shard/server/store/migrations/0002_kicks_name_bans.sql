-- Kicks with a cooldown, and name bans (WP50). Times are UTC epoch milliseconds.
-- Never edit this file once released: its checksum is recorded in schema_migrations. Add a new migration instead.

-- Every kick that keeps its player out for a while (vanilla's handleKicked): the UUID and the address wait until
-- expires_at, unless pardoned. Vanilla saved the same as lastKicked and ip-kicks; a kick without a cooldown leaves
-- no row, as vanilla counts none.
CREATE TABLE kicks(
    id INTEGER PRIMARY KEY,
    player_id INTEGER NOT NULL REFERENCES players(id),
    address TEXT NOT NULL CHECK(length(address) BETWEEN 1 AND 64),
    reason TEXT NOT NULL DEFAULT '' CHECK(length(reason) <= 1024),
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL CHECK(length(created_by) BETWEEN 1 AND 128),
    expires_at INTEGER NOT NULL CHECK(expires_at > created_at),
    pardoned_at INTEGER,
    pardoned_by TEXT CHECK(pardoned_by IS NULL OR length(pardoned_by) BETWEEN 1 AND 128),
    CHECK((pardoned_at IS NULL) = (pardoned_by IS NULL))
) STRICT;
CREATE INDEX kicks_player ON kicks(player_id, created_at);
CREATE INDEX kicks_open ON kicks(pardoned_at, expires_at);

-- Name bans: case-insensitive regular expressions a joining name must not contain (vanilla's banned-names).
CREATE TABLE name_bans(
    id INTEGER PRIMARY KEY,
    pattern TEXT NOT NULL UNIQUE CHECK(length(pattern) BETWEEN 1 AND 256),
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL CHECK(length(created_by) BETWEEN 1 AND 128)
) STRICT;
