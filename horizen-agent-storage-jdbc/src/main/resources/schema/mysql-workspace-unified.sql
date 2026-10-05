-- Existing Agent installations only, after legacy Skill files have been exported/imported.
-- Keep the old selection column for rollback, but new code neither reads nor writes it.
-- A default lets new aggregate inserts work against the old NOT NULL column.
ALTER TABLE ha_workspace ALTER COLUMN skill_selection_json SET DEFAULT '[]';
-- New databases use mysql.sql and do not contain skill_selection_json or separate Skill tables.
