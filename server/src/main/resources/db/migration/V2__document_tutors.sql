CREATE TABLE tutors (
    session_id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    body JSONB NOT NULL
);
CREATE INDEX tutors_document ON tutors(document_id);
