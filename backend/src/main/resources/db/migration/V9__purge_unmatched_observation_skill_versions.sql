-- Observation skill identity must match a published skill_version label.
-- Physically remove skill steps that are unversioned, unlisted, or version-mismatched.
DELETE FROM observation_step st
WHERE st.type = 'skill'
  AND (
    st.skill_slug IS NULL
    OR TRIM(st.skill_slug) = ''
    OR st.skill_version_label IS NULL
    OR TRIM(st.skill_version_label) = ''
    OR NOT EXISTS (
      SELECT 1
      FROM skill s
      JOIN skill_version v ON v.skill_id = s.id
      WHERE s.slug = st.skill_slug
        AND LOWER(TRIM(v.version_label)) = LOWER(
          REGEXP_REPLACE(TRIM(st.skill_version_label), '^[vV]', '')
        )
    )
  );
