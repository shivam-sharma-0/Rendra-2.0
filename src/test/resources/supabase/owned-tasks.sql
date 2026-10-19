CREATE TABLE public.tasks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id),
  title text NOT NULL,
  completed boolean NOT NULL DEFAULT false
);
ALTER TABLE public.tasks ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_access ON public.tasks FOR ALL TO authenticated
  USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
