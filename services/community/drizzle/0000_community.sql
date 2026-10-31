CREATE TABLE clients (token_hash TEXT PRIMARY KEY, id TEXT NOT NULL, created_at INTEGER NOT NULL);
--> statement-breakpoint
CREATE TABLE rate_limits (key TEXT PRIMARY KEY, hits INTEGER NOT NULL, expires INTEGER NOT NULL);
--> statement-breakpoint
CREATE TABLE configs (
 id TEXT PRIMARY KEY, revision INTEGER NOT NULL DEFAULT 1,
 game_name TEXT NOT NULL, author TEXT NOT NULL DEFAULT '', metadata TEXT NOT NULL,
 manifest TEXT NOT NULL, zip_key TEXT NOT NULL, cover_key TEXT,
 sha256 TEXT NOT NULL, zip_bytes INTEGER NOT NULL,
 verified INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0,
 source_url TEXT UNIQUE, submission_key TEXT UNIQUE, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
--> statement-breakpoint
CREATE INDEX configs_updated ON configs(updated_at DESC, id);
--> statement-breakpoint
CREATE TABLE moderation_log (id TEXT PRIMARY KEY, config_id TEXT NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL);
