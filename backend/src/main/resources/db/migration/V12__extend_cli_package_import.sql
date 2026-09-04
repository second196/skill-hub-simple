ALTER TABLE skill_import_attempt
    ADD COLUMN request_digest CHAR(64),
    ADD COLUMN version_digest CHAR(64);

UPDATE skill_import_attempt
SET request_digest = artifact_digest,
    version_digest = artifact_digest
WHERE status = 'SUCCEEDED'
  AND artifact_digest IS NOT NULL;

ALTER TABLE skill_import_attempt
    ADD CONSTRAINT ck_import_request_digest_sha256
        CHECK (request_digest IS NULL OR request_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_import_version_digest_sha256
        CHECK (version_digest IS NULL OR version_digest ~ '^[0-9a-f]{64}$');

CREATE INDEX idx_import_request_digest
    ON skill_import_attempt (request_digest)
    WHERE request_digest IS NOT NULL;

CREATE INDEX idx_import_version_digest
    ON skill_import_attempt (version_digest)
    WHERE version_digest IS NOT NULL;
