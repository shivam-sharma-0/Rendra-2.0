-- Stable public slug for published projects (used in the /p/{slug} URL).
ALTER TABLE projects ADD COLUMN public_slug VARCHAR(64);
ALTER TABLE projects ADD CONSTRAINT uq_projects_public_slug UNIQUE (public_slug);
