# Local Volunteer Shift Balancer

Java 17 + SQL (H2) app that assigns volunteers to shifts based on **skills, availability and weekly hour caps**.
It never double-books anyone, spreads hours evenly with a **PriorityQueue min-heap**, explains **why** a shift is uncovered,
and lets an admin **force-assign / swap / remove with undo-redo**. It comes with a web dashboard and a CLI mode.

Code split: ~90% Java + SQL (47 Java files, schema + 4 reporting views); the rest is a small HTML/CSS/JS dashboard.

---

## Run it

**Option A: no Maven needed** (the jars are already in `lib/`; needs JDK 17+)
```bash
./run.sh              # Mac/Linux → open http://localhost:8080
run.bat               # Windows
./run.sh --cli        # console report + out/schedule.json + out/schedule.csv
./run.sh test         # 28 JUnit tests
```

**Option B: Maven**
```bash
mvn test
mvn exec:java                                   # dashboard
mvn package && java -jar target/volunteer-shift-balancer-1.0.0-all.jar
```

Flags: `--port 9090` · `--cli` · `--strategy min-hours|capacity-ratio|strict-priority` · `--data ./sample-data`
(import a folder with volunteers + shifts as .json or .csv) · `--mem` (don't save to disk) · `--out ./out`

The app starts empty: nothing is built in. On the dashboard, click **Events** to add each event (name, date, start/end time, skill needed + minimum level, how many volunteers), then **Volunteers** to add each person (name, skills + level, max hours, the days/times they are free). After every add or remove the balancer re-divides everyone across the events.

The database is saved to `data/balancer.mv.db`. Delete that file to start over from empty.

---

## Pipeline (matches the spec)

| Step | What happens | Where |
|---|---|---|
| 1. Data ingestion | JSON/CSV → DTO records → Builders → immutable domain objects → saved to SQL | `io/JsonDataLoader`, `io/CsvDataLoader`, `io/Dtos`, `persistence/*Repository` |
| 2. Candidate filter | Skill + level, availability window, time overlap, weekly hour cap, shift full | `service/ValidationEngine` |
| 3. Balancer engine | Shifts ordered (critical → scarcest → earliest); per shift a min-heap of eligible volunteers ordered by the strategy; poll until full; then a variance-reducing refinement pass | `service/ShiftBalancerService`, `strategy/*` |
| 4. Diagnostics | For each unfilled shift: counts every rejection reason, picks the root cause, lists "near misses" you could force-assign | `service/DiagnosticsService` |
| 5. Manual edits | Assign / force-assign / remove / swap / clear / re-balance as Commands with undo + redo, re-validated instantly | `command/*`, `service/ScheduleEditor` |
| 6. Metrics & export | Coverage %, mean/σ hours, Jain fairness index, hours by skill/day; export JSON or CSV | `service/MetricsService`, `io/ScheduleExporter` |

---

## What each file does

### `model/`: domain
- **`TimeSlot`** (record): a `[start, end)` interval with `LocalDateTime`. Has `hours()`, `overlaps()`, `contains()`, `weekStart()`.
- **`Skill`** (record): skill name, lower-cased so "First Aid" = "first aid".
- **`Assignment`** (record): volunteer ↔ shift. `manual=true` means an admin placed it, so the balancer keeps (pins) it.
- **`Volunteer`** (class + Builder): profile (skills with level 1–5, weekly cap, availability). Booked hours are **private**
  and can only be changed by `Schedule`. Double-booking check is O(log n) on a `TreeSet<TimeSlot>`.
- **`Shift`** (class + Builder): activity, location, slot, required skill + min level, seats needed, priority (1–3).
- **`Schedule`**: the aggregate. Holds volunteers, shifts, assignments and the **`Map<Skill, Set<Volunteer>>` skill index**.
  Only this class books or releases time, so the "no double booking" rule can't be bypassed.
- **`ShiftStatus`** (enum): `OPEN`, `PARTIALLY_STAFFED`, `FULLY_STAFFED`.
- **`ConflictReason`** (enum): every rejection reason, plus whether an admin may override it.
  Overlap and shift-full can never be overridden.
- **`Violation`** (record): a reason plus a readable message.

### `util/TimeOverlapChecker`
The interval maths: `startA < endB && endA > startB`. Also merges touching availability windows (08–12 + 12–16 → 08–16).

### `strategy/`: Strategy pattern
- **`FairnessStrategy`** (interface): returns a `Comparator<Volunteer>` for a shift. That comparator orders the PriorityQueue.
- **`MinHoursBalancingStrategy`** (default): fewest hours, then most spare capacity, then higher skill, then id.
- **`CapacityRatioStrategy`**: lowest % of weekly cap used, so people who offered more hours do more.
- **`StrictPriorityStrategy`**: best-skilled first, hours only break ties.
- **`StrategyRegistry`**: looks strategies up by id for the UI and CLI.

### `service/`
- **`ValidationEngine`**: `check(volunteer, shift, schedule)` returns every broken rule. `revalidate(schedule)` audits
  the whole schedule after forced edits.
- **`ShiftBalancerService`**: the algorithm described above. Filling scarce shifts first stops easy shifts from using up the
  only qualified people. Refinement moves a shift from a busy volunteer to a lighter eligible one when that narrows the gap.
  Each move lowers the sum of squared hours, so the loop always ends.
- **`DiagnosticsService`**: root-cause analysis for each uncovered shift (skill deficit, hour cap reached, outside availability,
  time overlap) plus near-miss suggestions.
- **`MetricsService`**: statistics built with `Collectors.groupingBy`. Jain index = (Σx)² / (n·Σx²); 1.0 means perfectly even.
- **`ScheduleEditor`**: the admin service API. Runs every action as a Command, re-validates, saves to SQL and writes the audit log.

### `command/`: Command pattern (undo/redo)
`EditCommand` interface · `AssignCommand` (normal or force) · `RemoveCommand` · `SwapCommand` (validates both sides and rolls
back on failure) · `SnapshotCommand` (balance and clear, undone by restoring a snapshot) · `CommandHistory` (two `ArrayDeque` stacks) ·
`EditRejectedException` (carries the violations to the UI).

### `io/`
`Json` (configured Jackson mapper) · `Dtos` (file-format records + mapping) · `JsonDataLoader` / `CsvDataLoader`
(NIO.2 `Files.readString`; CSV parser handles quotes) · `ScheduleExporter` (JSON grouped by volunteer and shift, or flat CSV).

### `persistence/`: SQL
- **`resources/db/schema.sql`**: 7 tables (`volunteer`, `skill`, `volunteer_skill`, `availability`, `shift`, `assignment`,
  `audit_log`) with foreign keys, CHECK constraints and indexes, plus 4 **views**: `v_volunteer_workload`, `v_shift_coverage`,
  `v_skill_supply_demand`, `v_daily_load`.
- **`Database`**: embedded H2 (file or in-memory), runs the schema, transaction helper.
- **`VolunteerRepository`, `ShiftRepository`, `AssignmentRepository`**: JDBC with `PreparedStatement`, `MERGE`, batch inserts.
- **`ReportRepository`**: whitelisted read-only SQL reports (JOINs, GROUP BY, sub-queries, `RANK() OVER` window functions)
  shown live in the dashboard's "SQL reports" tab.
- **`DataStore`**: facade that loads and saves a whole `Schedule`.

### `web/`
- **`ApiServer`**: REST API on the JDK's built-in `HttpServer` (no framework). It also serves the dashboard files.
- **`StateMapper`**: turns the domain into JSON view records.

| Method | Path | Body |
|---|---|---|
| GET | `/api/state` | full dashboard state |
| GET | `/api/candidates?shift=S01` | every volunteer, with eligibility and reasons |
| POST | `/api/balance` | `{strategy}` |
| POST | `/api/assign` | `{volunteerId, shiftId, force}` |
| POST | `/api/remove` | `{volunteerId, shiftId}` |
| POST | `/api/swap` | `{volunteerA, shiftA, volunteerB, shiftB}` |
| POST | `/api/undo`, `/api/redo`, `/api/clear` | none |
| GET | `/api/reports`, `/api/reports/{id}` | SQL reports |
| GET | `/api/export?format=json\|csv` | download |
| POST | `/api/import?format=json\|csv` | `{volunteers, shifts}` file text |

### `App.java`
Entry point: sets up the DB, seeds sample data the first time, then starts the server or runs CLI mode.

### `resources/web/`: dashboard (the only non-Java part)
Week board (cards colored by status; click two names to swap, × to remove, "+ Assign" opens the candidate drawer
with Assign/Force buttons), KPI strip, workload bars with a mean marker, and tabs for uncovered shifts, rule violations,
SQL reports and activity. Ctrl+Z / Ctrl+Shift+Z for undo/redo.

### Tests (`src/test/java`, JUnit 4, 28 tests)
Interval logic, every validation rule, even spreading, no double-booking or cap breach under all strategies,
scarce-skill handling, diagnostics, undo/redo/swap rollback, CSV parsing, SQL round-trip (SQL view hours = Java hours).

---

## Data formats
`sample-data/` and `src/main/resources/data/` hold 18 volunteers and 26 shifts (Mon 2 – Sun 8 Nov 2026) in JSON and CSV.

```csv
id,name,email,maxWeeklyHours,skills,availability
V01,Ananya Rao,ananya@x.org,14,first aid:3|logistics:2,2026-11-02T17:00/2026-11-02T21:00|2026-11-07T08:00/2026-11-07T18:00
```
```csv
id,activity,location,start,end,requiredSkill,minSkillLevel,volunteersNeeded,priority
S01,Food bank sorting,Jayanagar Food Bank,2026-11-02T07:00,2026-11-02T11:00,logistics,1,3,2
```
