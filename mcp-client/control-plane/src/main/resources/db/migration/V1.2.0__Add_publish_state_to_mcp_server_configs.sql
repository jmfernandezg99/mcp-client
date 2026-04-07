ALTER TABLE mcp_server_configs ADD COLUMN published BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mcp_server_configs ADD COLUMN published_at TIMESTAMP NULL;
