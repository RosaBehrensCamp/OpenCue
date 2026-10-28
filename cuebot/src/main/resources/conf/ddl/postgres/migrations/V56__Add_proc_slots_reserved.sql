-- Number of concurrency slots reserved by a proc (booked frame) on a slot-based host.
-- 0 for regular (cores/memory) procs. This is the single source of truth for slot
-- accounting: per-host and per subscription/folder/job slot usage both derive from
-- SUM(proc.int_slots_reserved).
alter table proc
    add int_slots_reserved INT DEFAULT 0 NOT NULL;

-- A slot proc reserves 0 cores (it runs unpinned, capped by slots instead), so only
-- reject a 0-core proc that reserves no slots either.
CREATE OR REPLACE FUNCTION trigger__before_insert_proc()
RETURNS TRIGGER AS $body$
BEGIN
    IF NEW.int_cores_reserved <= 0 AND NEW.int_slots_reserved <= 0 THEN
        RAISE EXCEPTION 'failed to allocate proc, tried to allocate 0 cores';
    END IF;
    RETURN NEW;
END;
$body$
LANGUAGE PLPGSQL;
