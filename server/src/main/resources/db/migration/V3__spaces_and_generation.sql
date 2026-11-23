ALTER TABLE jobs ALTER COLUMN document_id DROP NOT NULL, ALTER COLUMN document_version DROP NOT NULL,
    ALTER COLUMN options DROP NOT NULL,
    DROP CONSTRAINT jobs_owner_id_client_request_id_key,
    ADD COLUMN operation text NOT NULL DEFAULT 'compile',
    ADD COLUMN generation jsonb,
    ADD UNIQUE(owner_id, operation, client_request_id),
    ADD CHECK ((operation = 'compile' AND document_id IS NOT NULL AND document_version IS NOT NULL AND options IS NOT NULL AND generation IS NULL)
            OR (operation IN ('concept-game','space-path') AND document_id IS NULL AND document_version IS NULL AND options IS NULL AND generation IS NOT NULL));

CREATE TABLE spaces (space_id text PRIMARY KEY, owner_id text NOT NULL, body jsonb NOT NULL);
CREATE INDEX spaces_owner_idx ON spaces(owner_id);
CREATE TABLE activity_artifacts (job_id text PRIMARY KEY REFERENCES jobs ON DELETE CASCADE, archive bytea NOT NULL);
