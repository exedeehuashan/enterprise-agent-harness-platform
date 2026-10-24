-- Notification integrations (P41): a subscription delivers events as signed JSON (generic) or as chat or email messages.
ALTER TABLE webhook_subscriptions ADD COLUMN format VARCHAR(10) NOT NULL DEFAULT 'generic'
    CHECK (format IN ('generic','slack','dingtalk','wecom','feishu','email'));
