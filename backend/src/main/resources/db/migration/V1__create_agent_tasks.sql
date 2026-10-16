CREATE TABLE agent_tasks (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL CHECK (length(trim(title)) > 0),
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
