---
title: "Applying database migrations"
layout: default
parent: Other Guides
nav_order: 40
linkTitle: "Applying database migrations"
date: 2019-08-22
description: >
  Apply new database migrations
---

# Applying Database Migrations

### Apply new database migrations

---

This page describes how to apply new database migrations when necessary. This
guide is intended for OpenCue system admins.

Occasionally changes in OpenCue also require an update to the database schema.
When this happens, the development team add the schema changes as a new
*migration*. The migration contains raw SQL and only includes the parts of
the schema that have changed. You can apply and safely roll back the migration
using the database management tool of your choice. This guide describes how to
apply the migration using [flyway](https://flywaydb.org/).

When you update Cuebot, you must also apply any new migrations to ensure the
Cuebot code matches the correct database schema. Changes that require a
database migration are noted in the
[OpenCue release notes](https://www.opencue.io/blog/releases/).

## Applying a migration

To apply a migration:

1.  Stop Cuebot, by either killing the Cuebot process or stopping its
    container.

1.  To execute the migrations, you run the `flyway` command and supply the
    credentials from the original
    [database installation](/docs/getting-started/setting-up-the-database).
    Run the `flyway` from the root folder of the OpenCue repo:

    ```shell
    flyway -url=jdbc:postgresql://$DB_HOST/$DB_NAME -user=$USER -n -locations=filesystem:cuebot/src/main/resources/conf/ddl/postgres/migrations migrate
    ``` 

1.  To update Cuebot to the version that corresponds to the database changes,
    follow the installation instructions in
    [Deploying Cuebot](/docs/getting-started/deploying-cuebot).

1.  Restart Cuebot.

## Building large indexes ahead of time

Some migrations add indexes to tables that can be large on a busy farm. Flyway
runs each migration in a transaction, and PostgreSQL can't build an index with
`CREATE INDEX CONCURRENTLY` inside a transaction, so these migrations use a
plain `CREATE INDEX`. A plain build scans the whole table and blocks writes to
it until it finishes. Cuebot is stopped while you migrate, so nothing is
blocked, but each build adds its scan to the downtime.

To keep the downtime short on a large database, build these indexes yourself
with `CREATE INDEX CONCURRENTLY` while Cuebot is still running. A concurrent
build doesn't block writes. These migrations create their indexes with
`IF NOT EXISTS`, so when Flyway runs them afterwards, it skips the indexes you
built.

| Migration | Indexes | Tables |
|---|---|---|
| V44 | `idx_layer_tags_array`, `idx_job_pending_lookup`, `idx_layer_stat_waiting` | `layer`, `job`, `layer_stat` |
| V50 | `i_frame_layer_dispatch_waiting` | `frame` |
| V58 | `i_proc_slots_pkjob`, `i_proc_slots_pkshow`, `i_proc_slots_pkhost`, `i_layer_slots_pkjob` | `proc`, `layer` |

To build an index ahead of time:

1.  Copy its `CREATE INDEX` statement from the migration file in
    `cuebot/src/main/resources/conf/ddl/postgres/migrations` and add
    `CONCURRENTLY` after `INDEX`. Keep the name and the rest of the definition
    exactly as they are. For example, for one of V58's indexes:

    ```sql
    CREATE INDEX CONCURRENTLY IF NOT EXISTS i_proc_slots_pkjob
        ON proc (pk_job) WHERE int_slots_reserved > 0;
    ```

1.  Run each statement on its own in `psql`, not inside a transaction block:
    PostgreSQL rejects a concurrent build after `BEGIN`.

1.  Check that every build finished. A concurrent build that fails or is
    canceled leaves an invalid index behind under the same name, and the
    migration's `IF NOT EXISTS` would then skip it. This query lists invalid
    indexes:

    ```sql
    SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
    ```

    Drop each one it lists with `DROP INDEX CONCURRENTLY <name>`, then build it
    again.

1.  Apply the migrations as described in
    [Applying a migration](#applying-a-migration).

### V58 and slot-based booking

V58's indexes cover columns that V54 and V56 add, so you can't build them
before those migrations have run. When you upgrade from a version before V54,
build them between two Flyway runs instead:

1.  Stop Cuebot and apply the migrations up to V57 by adding `-target=57` to the
    `flyway` command.

1.  Update and start Cuebot. It works without V58's indexes; until they exist,
    its slot usage queries are just slower.

1.  Build V58's indexes concurrently, as described above.

1.  Run the `flyway` command again, without `-target`. V58's indexes already
    exist, so it only records V58 and finishes in a moment, and you can run it
    with Cuebot running.

## What's next?

*   To stay up to date on changes that might require database migrations,
    subscribe to the RSS feed for the
    [OpenCue release notes](https://www.opencue.io/blog/releases/).
