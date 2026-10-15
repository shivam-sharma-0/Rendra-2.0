-- A clear request approved without any answer edit has requirements revision 0.
ALTER TABLE agent_behavior_criteria DROP CONSTRAINT IF EXISTS agent_behavior_criteria_requirements_revision_check;
ALTER TABLE agent_behavior_criteria ADD CONSTRAINT agent_behavior_criteria_requirements_revision_check CHECK (requirements_revision >= 0);
