CREATE TABLE mcp_server_configs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mcp_server_configs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_mcp_server_configs_user_name UNIQUE (user_id, name),
    CONSTRAINT uk_mcp_server_configs_user_url UNIQUE (user_id, url)
);
