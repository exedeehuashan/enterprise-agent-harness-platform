-- Named API tokens issued by approvers, in addition to the three configured deployment tokens. Only a SHA-256 hash
-- of each token is stored; the token itself is shown once, at creation.
CREATE TABLE api_tokens (
    id UUID PRIMARY KEY,
    name VARCHAR(40) NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9._-]{1,39}$'),
    description VARCHAR(200),
    token_hash BYTEA NOT NULL UNIQUE,
    roles JSONB NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by VARCHAR(100)
);
-- One active token per name keeps audit attribution unambiguous; revoke before reissuing.
CREATE UNIQUE INDEX api_tokens_active_name ON api_tokens(name) WHERE revoked_at IS NULL;
