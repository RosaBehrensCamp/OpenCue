---
title: "Slot-Based Booking"
nav_order: 14
parent: Concepts
layout: default
linkTitle: "Slot-Based Booking"
date: 2026-07-03
description: >
  Booking frames by concurrency slots instead of cores and memory
---

# Slot-Based Booking

Slot-based booking is an alternative dispatch mode for pipelines whose frames are
limited not by CPU or memory but by some other shared resource — storage bandwidth,
network throughput, a licensed external service, etc. In these pipelines what matters
is bounding *concurrency*, not resource consumption.

A slot-based frame ignores cores and memory entirely: it reserves **0 cores and 0
memory** and runs unpinned on the host. The only thing that limits it is a **slot
budget**.

> **Note:** Slot-based booking is implemented in [OpenCue Maestro](/docs/developer-guide/maestro/)
> and only works for shows Maestro schedules. The legacy dispatcher never books a slot
> layer and never books anything onto a slot host.

## The two slot axes

Slot booking is governed by two independent limits:

1. **Per-host cap** — `host.concurrent_slots_limit`. A host with this set is a
   *slot host*: it runs **only** slot-based layers, up to this many concurrent slots.
2. **Per-hierarchy max** — a `max_slots` limit at the **subscription**, **folder**, and
   **job** levels, enforced by Maestro at plan time. For a slot layer these replace
   the cores limits: a slot layer is not held back by a job's max cores, a folder's
   max cores or a show's core burst.

Both must be satisfied for a slot frame to book.

## Strict pairing

Slot hosts and slot layers are strictly paired:

| Host \ Layer | Regular layer | Slot layer (`slots_required > 0`) |
|---|---|---|
| **Regular host** | books by cores/memory | **rejected** |
| **Slot host** (`concurrent_slots_limit ≥ 0`) | **rejected** | books by slots |

A slot host never runs a regular layer, and a slot layer never runs on a regular host.

## Limit values

Every `max_slots` limit uses the convention:

- **`-1`** — unlimited (the migration default; a slot layer is bounded only by host
  capacity until an admin sets a limit).
- **`0`** — reject all slot work at this level.
- **`N`** — cap at N concurrent slots.

Regular (cores/memory) layers are unaffected by `max_slots`, and slot layers are
unaffected by the cores/GPUs limits.

## Making a layer slot-based

Set `slots_required` on the layer in the job spec (spec version **1.16**+). PyOutline
declares 1.16 by itself for a job with a slot layer and keeps the configured version
for every other job, so regular jobs still launch on a Cuebot without slot-based
booking. With PyOutline:

```python
layer = outline.modules.shell.Shell(
    "bandwidth_bound_layer",
    command=["my_command"],
    slots_required=1,   # each frame consumes one slot
)
```

A frame may require more than one slot (`slots_required > 1`) — a "heavy" frame counts
N against the host cap and all three hierarchy limits. Slot-based layers are forced
non-threadable.

## Configuring the limits

- **Host slot cap** — set a host's concurrent slots limit from CueCommander, or via
  PyCue: `host.setConcurrentSlotsLimit(8)` (`-1` disables slot mode).
- **Subscription / folder / job max slots** — set from the GUI or via PyCue
  (`subscription.setMaxSlots(...)`, `group.setMaxSlots(...)`, `job.setMaxSlots(...)`),
  mirroring how `max_cores` is managed.

## Upgrading RQD before you make a host slot-based

Cuebot launches a slot frame with 0 cores. An RQD that predates slot-based booking,
Python or Rust, refuses to launch a frame with 0 cores, so upgrade RQD on a host before
you give it a concurrent slots limit. Roll out in this order:

1. Upgrade Cuebot. Its migrations add the slot columns.
2. Upgrade RQD on the hosts that will become slot hosts.
3. Set those hosts' concurrent slots limits.

Hosts that stay regular can keep running an older RQD: their frames launch as before.

If a slot host's RQD isn't upgraded, its slot frames never start. Maestro books them,
the launch fails, and the frame goes back to waiting to be booked again on the next
tick. You'll see:

- In the Cuebot log: `Maestro: RQD launch failed for <proc> on frame <frame id>: ...
  unbooking and clearing frame`
- In the RQD log: `Not launching, numCores must be > 0` (Python RQD) or
  `Not launching, num_cores must be positive` (Rust RQD)

Upgrading RQD on the host fixes it, and its slot frames start on the next tick. Until
you can upgrade it, set the host's limit back to `-1` to stop Maestro from booking slot
frames there.

## Switching a host between slot-based and regular booking

Drain a host before you switch it between slot-based and regular booking, that is,
before you change its limit from `-1` to `0` or more, or back to `-1`. Changing the
limit doesn't stop the frames the host is running, and the new kind of work is booked
without counting them:

- **Slot host to regular host:** slot frames reserve no cores or memory, so the host
  looks fully idle and regular frames are booked on top of the slot frames still
  running.
- **Regular host to slot host:** the regular frames keep running while slot frames are
  booked beside them, up to the new limit.

To switch a host safely:

1. Lock the host, so nothing new is booked on it.
2. Wait for its running frames to finish.
3. Change its concurrent slots limit.
4. Unlock the host.

When you change the limit in CueGUI (**Update Slot Limit...** in the Monitor Hosts
view) and the change switches a host that is still running frames, CueGUI lists those
hosts and asks you to confirm before it changes anything. Changes that keep a host
slot-based, such as raising its limit from 4 to 8, don't need draining.

## How it accounts

`proc.int_slots_reserved` is the single source of truth for slot usage. Per-host slot
counts and per-subscription/folder/job counts both derive from `SUM(proc.int_slots_reserved)`,
so RQD does not need to report slots and there is no counter to drift. Maestro reads
those sums at the start of every tick, like the rest of its snapshot, and spends them
in memory as it places slot frames during the tick.
