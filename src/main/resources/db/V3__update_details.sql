-- Software updates: why an install failed, and what to restore when rolling back.

ALTER TABLE software_updates ADD COLUMN detail VARCHAR(300);
ALTER TABLE software_updates ADD COLUMN previous_values VARCHAR(500);   -- key=value;key=value before the install
CREATE UNIQUE INDEX software_updates_version ON software_updates (version);
