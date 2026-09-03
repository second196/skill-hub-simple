INSERT INTO governance_role (role_key, name)
VALUES
    ('ASSET_CONTRIBUTOR', 'Asset Contributor'),
    ('REVIEWER', 'Reviewer'),
    ('RELEASE_MANAGER', 'Release Manager'),
    ('RELEASE_PUBLISHER', 'Release Publisher'),
    ('RELEASE_APPROVER', 'Release Approver'),
    ('POLICY_ADMIN', 'Policy Administrator'),
    ('GOVERNANCE_ADMIN', 'Governance Administrator'),
    ('AUDITOR', 'Auditor')
ON CONFLICT (role_key) DO NOTHING;

INSERT INTO governance_scope (scope_type, scope_key, name, status, source_system)
VALUES ('COMPANY', 'skillhub', 'SKILL HUB System', 'ACTIVE', 'bootstrap')
ON CONFLICT (scope_type, scope_key) DO NOTHING;

INSERT INTO principal_account (username, password_hash, enabled)
VALUES
    ('admin', '$2a$10$2tTgdAMFTAqLRuvS2Jelxu5JgHKnhBVhqQNp14U9T.4z8nPLOxSFi', TRUE),
    ('user', '$2a$10$ozg09w0Ydqa3eNBvxagQx.ifXFvwHU.lvwGbIUpqKbrhBzjAZPIcC', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO principal_scope_role (principal_id, scope_id, role_key)
SELECT pa.id, gs.id, roles.role_key
FROM principal_account pa
JOIN governance_scope gs
    ON gs.scope_type = 'COMPANY' AND gs.scope_key = 'skillhub'
JOIN (
    VALUES
        ('admin', 'ASSET_CONTRIBUTOR'),
        ('admin', 'REVIEWER'),
        ('admin', 'RELEASE_MANAGER'),
        ('admin', 'RELEASE_PUBLISHER'),
        ('admin', 'RELEASE_APPROVER'),
        ('admin', 'POLICY_ADMIN'),
        ('admin', 'GOVERNANCE_ADMIN'),
        ('admin', 'AUDITOR'),
        ('user', 'ASSET_CONTRIBUTOR')
) AS roles(username, role_key)
    ON roles.username = pa.username
ON CONFLICT (principal_id, scope_id, role_key) DO NOTHING;
