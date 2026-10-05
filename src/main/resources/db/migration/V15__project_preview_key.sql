-- Each project's private preview is served from its own host, pv--<preview_key>.<sites domain>.
ALTER TABLE projects ADD COLUMN preview_key varchar(32);
CREATE UNIQUE INDEX ux_projects_preview_key ON projects (preview_key);
