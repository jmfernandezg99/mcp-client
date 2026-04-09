CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(40) NOT NULL,
    runtime_port INTEGER,
    runtime_url VARCHAR(500),
    config_path VARCHAR(1000),
    startup_command VARCHAR(4000),
    last_error VARCHAR(2000),
    last_applied_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workspaces_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_workspaces_user UNIQUE (user_id),
    CONSTRAINT uk_workspaces_runtime_port UNIQUE (runtime_port)
);
