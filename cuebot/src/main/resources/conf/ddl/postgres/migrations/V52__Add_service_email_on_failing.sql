-- Opt-in per service (and per show override): email the job's owner when a job using the
-- service is failing, i.e. nothing is left waiting or running and some frames are dead.

ALTER TABLE service ADD COLUMN b_email_on_failing BOOLEAN DEFAULT false NOT NULL;
ALTER TABLE show_service ADD COLUMN b_email_on_failing BOOLEAN DEFAULT false NOT NULL;
