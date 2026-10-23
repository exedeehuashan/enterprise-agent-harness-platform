-- Outgoing webhooks: each subscription follows audit_events with its own cursor, so events are delivered in order,
-- at least once, and a receiver that was down catches up from where it stopped.
CREATE TABLE webhook_subscriptions (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    url VARCHAR(500) NOT NULL,
    secret VARCHAR(100) NOT NULL,
    events JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    cursor_id BIGINT NOT NULL,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_delivery_at TIMESTAMPTZ,
    last_error VARCHAR(200),
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE webhook_deliveries (
    id BIGSERIAL PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES webhook_subscriptions(id) ON DELETE CASCADE,
    audit_event_id BIGINT NOT NULL,
    action VARCHAR(80) NOT NULL,
    status_code INTEGER,
    error VARCHAR(200),
    duration_ms INTEGER,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX webhook_deliveries_recent ON webhook_deliveries(subscription_id, id DESC);
