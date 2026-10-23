-- Domain modules (方案) plugged into EAHP: core, qa (方案 4), and later others. A missing row means enabled.
CREATE TABLE domain_modules (
    key VARCHAR(40) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
