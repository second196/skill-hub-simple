CREATE OR REPLACE FUNCTION prevent_governance_record_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'governance record is append-only: %', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_skill_artifact_append_only
BEFORE UPDATE OR DELETE ON skill_artifact
FOR EACH ROW EXECUTE FUNCTION prevent_governance_record_mutation();

CREATE OR REPLACE FUNCTION prevent_skill_version_content_mutation()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.asset_id IS DISTINCT FROM NEW.asset_id
       OR OLD.artifact_id IS DISTINCT FROM NEW.artifact_id
       OR OLD.version_label IS DISTINCT FROM NEW.version_label
       OR OLD.version_digest IS DISTINCT FROM NEW.version_digest
       OR OLD.source_type IS DISTINCT FROM NEW.source_type
       OR OLD.source_locator IS DISTINCT FROM NEW.source_locator
       OR OLD.source_snapshot_uri IS DISTINCT FROM NEW.source_snapshot_uri
       OR OLD.metadata_status IS DISTINCT FROM NEW.metadata_status
       OR OLD.created_by IS DISTINCT FROM NEW.created_by THEN
        RAISE EXCEPTION 'skill version content is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_skill_version_content_immutable
BEFORE UPDATE OR DELETE ON skill_version
FOR EACH ROW EXECUTE FUNCTION prevent_skill_version_content_mutation();

CREATE TRIGGER trg_gate_evidence_append_only
BEFORE UPDATE OR DELETE ON gate_evidence
FOR EACH ROW EXECUTE FUNCTION prevent_governance_record_mutation();

CREATE TRIGGER trg_release_policy_append_only
BEFORE UPDATE OR DELETE ON release_policy_version
FOR EACH ROW EXECUTE FUNCTION prevent_governance_record_mutation();

CREATE TRIGGER trg_retention_policy_append_only
BEFORE UPDATE OR DELETE ON retention_policy_version
FOR EACH ROW EXECUTE FUNCTION prevent_governance_record_mutation();
