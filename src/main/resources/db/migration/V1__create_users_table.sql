CREATE TYPE user_status AS ENUM (
    'PENDING_VERIFICATION',
    'ACTIVE',
    'LOCKED'
);

CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    email VARCHAR UNIQUE NOT NULL,
    password_hash VARCHAR NOT NULL,
    status user_status NOT NULL,
    creation_date TIMESTAMP NOT NULL
);