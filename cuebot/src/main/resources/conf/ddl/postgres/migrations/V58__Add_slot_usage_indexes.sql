-- Slot usage is summed from proc.int_slots_reserved (V56) per job, show, host and
-- folder, in the whiteboard reads CueGUI polls (jobs, groups, shows, hosts and
-- allocations) and in Maestro's tick reads. Each sum filters on
-- int_slots_reserved > 0, and these partial indexes hold only the procs that reserve
-- slots, so a sum over regular work reads no proc rows instead of every proc of the
-- job, show or host. The layer index does the same for the count of a job's slot
-- layers in the job whiteboard.
--
-- The indexes hold only slot procs and slot layers, so they are small, but building
-- each still scans its whole table and blocks writes to it meanwhile. On a large
-- production database, build them out-of-band with CREATE INDEX CONCURRENTLY (not
-- allowed inside Flyway's transaction) under the same names, after migrating to V57
-- (flyway -target=57): they index columns V54 and V56 add. The IF NOT EXISTS below
-- then makes this migration a no-op when Flyway runs it; no manual
-- flyway_schema_history edit is needed. The steps are in
-- docs/_docs/other-guides/applying-database-migrations.md.
CREATE INDEX IF NOT EXISTS i_proc_slots_pkjob
    ON proc (pk_job) WHERE int_slots_reserved > 0;

CREATE INDEX IF NOT EXISTS i_proc_slots_pkshow
    ON proc (pk_show) WHERE int_slots_reserved > 0;

CREATE INDEX IF NOT EXISTS i_proc_slots_pkhost
    ON proc (pk_host) WHERE int_slots_reserved > 0;

CREATE INDEX IF NOT EXISTS i_layer_slots_pkjob
    ON layer (pk_job) WHERE int_slots_required > 0;
