-- Anything2Game server schema, version 1. Timestamps are epoch milliseconds (the server's own clock, injectable in tests).
-- Packages, frames, chunks, options and errors are jsonb documents written with the contract's JSON configuration.

CREATE TABLE installs (
    install_id  text   PRIMARY KEY,
    token_hash  text   NOT NULL UNIQUE,
    created_at  bigint NOT NULL
);

-- Quotas and global cost guards. scope = owner id or '*' (everybody); bucket = '<kind>:<day or hour>'.
CREATE TABLE quota_counters (
    scope       text   NOT NULL,
    bucket      text   NOT NULL,
    n           int    NOT NULL,
    updated_at  bigint NOT NULL,
    PRIMARY KEY (scope, bucket)
);

CREATE TABLE documents (
    document_id       text   PRIMARY KEY,
    owner_id          text   NOT NULL,
    title             text   NOT NULL,
    kind              text   NOT NULL,
    document_version  text   NOT NULL,
    page_count        int    NOT NULL,
    created_at        bigint NOT NULL,
    chunks            jsonb  NOT NULL
);
CREATE INDEX documents_owner_idx ON documents (owner_id);

-- The jobs table is also the work queue: a job is claimable while it is not terminal and has no lease or an expired one.
CREATE TABLE jobs (
    job_id             text    PRIMARY KEY,
    owner_id           text    NOT NULL,
    document_id        text    NOT NULL REFERENCES documents (document_id) ON DELETE CASCADE,
    document_version   text    NOT NULL,
    options            jsonb   NOT NULL,
    client_request_id  text    NOT NULL,
    package_id         text    NOT NULL,
    state              text    NOT NULL,
    levels_ready       int     NOT NULL DEFAULT 0,
    levels_planned     int     NOT NULL DEFAULT 0,
    error              jsonb,
    created_at         bigint  NOT NULL,
    updated_at         bigint  NOT NULL,
    claimed_by         text,
    lease_until        bigint,
    attempts           int     NOT NULL DEFAULT 0,
    UNIQUE (owner_id, client_request_id)
);
CREATE INDEX jobs_document_idx ON jobs (document_id);
CREATE INDEX jobs_claim_idx ON jobs (created_at) WHERE state IN ('queued', 'planning', 'generating');

CREATE TABLE packages (
    package_id   text    PRIMARY KEY,
    owner_id     text    NOT NULL,
    job_id       text    NOT NULL,
    document_id  text    NOT NULL REFERENCES documents (document_id) ON DELETE CASCADE,
    complete     boolean NOT NULL,
    pkg          jsonb   NOT NULL
);
CREATE INDEX packages_document_idx ON packages (document_id);

CREATE TABLE frames (
    package_id  text  NOT NULL REFERENCES packages (package_id) ON DELETE CASCADE,
    frame_id    text  NOT NULL,
    overlay     jsonb NOT NULL,
    PRIMARY KEY (package_id, frame_id)
);
