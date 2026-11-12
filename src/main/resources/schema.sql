CREATE TABLE IF NOT EXISTS owner_account (
  id INTEGER PRIMARY KEY CHECK (id = 1), username VARCHAR(80) NOT NULL, password_hash VARCHAR(512) NOT NULL
);
CREATE TABLE IF NOT EXISTS projects (
  id VARCHAR(200) PRIMARY KEY, revision BIGINT NOT NULL, document TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS assets (
  id VARCHAR(200) PRIMARY KEY, project_id VARCHAR(200) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name VARCHAR(200) NOT NULL, media_type VARCHAR(120) NOT NULL, content BYTEA NOT NULL
);
CREATE INDEX IF NOT EXISTS assets_project_idx ON assets(project_id);
CREATE TABLE IF NOT EXISTS owner_profile (
  owner_id INTEGER PRIMARY KEY REFERENCES owner_account(id) ON DELETE CASCADE,
  key_salt BYTEA NOT NULL, active_project_id VARCHAR(200)
);
CREATE TABLE IF NOT EXISTS owner_ha_connection (
  owner_id INTEGER PRIMARY KEY REFERENCES owner_account(id) ON DELETE CASCADE,
  instance_url VARCHAR(2048) NOT NULL, client_id VARCHAR(2048) NOT NULL, encrypted_refresh TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS energy_buckets (
  project_id VARCHAR(200) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  hour_utc BIGINT NOT NULL, source_id VARCHAR(200) NOT NULL, source_name VARCHAR(200) NOT NULL,
  utility VARCHAR(20) NOT NULL, quality VARCHAR(20) NOT NULL,
  amount DOUBLE PRECISION NOT NULL, cost DOUBLE PRECISION NOT NULL,
  unpriced DOUBLE PRECISION NOT NULL, observed_seconds DOUBLE PRECISION NOT NULL,
  PRIMARY KEY(project_id,hour_utc,source_id,quality)
);
CREATE TABLE IF NOT EXISTS energy_monthly (
  project_id VARCHAR(200) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  month_id VARCHAR(7) NOT NULL, utility VARCHAR(20) NOT NULL,
  amount DOUBLE PRECISION NOT NULL, cost DOUBLE PRECISION,
  PRIMARY KEY(project_id,month_id,utility)
);
