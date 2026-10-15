-- Nullable metadata preserves legacy replies and uses the original accepted prompt for build offers.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS suggested_build_text text;
