-- Legacy approvals remain explicit legacy state; never reinterpret an ambiguous "Cloud" label.
ALTER TABLE requirement_sessions ADD COLUMN IF NOT EXISTS build_decision_json text;
