BEGIN;

INSERT INTO tracker_settings (id, daily_goal)
VALUES (1, 8000)
ON CONFLICT (id) DO UPDATE SET daily_goal = EXCLUDED.daily_goal;

INSERT INTO daily_steps (step_date, steps) VALUES
  (CURRENT_DATE - 6, 6400),
  (CURRENT_DATE - 5, 8100),
  (CURRENT_DATE - 4, 10500),
  (CURRENT_DATE - 3, 7900),
  (CURRENT_DATE - 2, 12500),
  (CURRENT_DATE - 1, 9000),
  (CURRENT_DATE, 5400)
ON CONFLICT (step_date) DO UPDATE SET steps = EXCLUDED.steps;

-- Version 1 records that the service's initial schema and legacy-data migration ran.
INSERT INTO schema_migrations (version) VALUES (1)
ON CONFLICT (version) DO NOTHING;

COMMIT;
