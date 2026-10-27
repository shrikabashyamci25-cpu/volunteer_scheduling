-- =====================================================================
--  Local Volunteer Shift Balancer — relational schema (H2, ANSI SQL)
-- =====================================================================

CREATE TABLE IF NOT EXISTS skill (
    name            VARCHAR(50)  PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS volunteer (
    id               VARCHAR(20)  PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    email            VARCHAR(120),
    max_weekly_hours INT          NOT NULL CHECK (max_weekly_hours > 0)
);

CREATE TABLE IF NOT EXISTS volunteer_skill (
    volunteer_id    VARCHAR(20) NOT NULL REFERENCES volunteer(id) ON DELETE CASCADE,
    skill_name      VARCHAR(50) NOT NULL REFERENCES skill(name),
    level           INT         NOT NULL CHECK (level BETWEEN 1 AND 5),
    PRIMARY KEY (volunteer_id, skill_name)
);

CREATE TABLE IF NOT EXISTS availability (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    volunteer_id    VARCHAR(20) NOT NULL REFERENCES volunteer(id) ON DELETE CASCADE,
    start_time      TIMESTAMP   NOT NULL,
    end_time        TIMESTAMP   NOT NULL,
    CHECK (end_time > start_time)
);

CREATE TABLE IF NOT EXISTS shift (
    id                VARCHAR(20)  PRIMARY KEY,
    activity          VARCHAR(120) NOT NULL,
    location          VARCHAR(120),
    start_time        TIMESTAMP    NOT NULL,
    end_time          TIMESTAMP    NOT NULL,
    required_skill    VARCHAR(50)  NOT NULL REFERENCES skill(name),
    min_skill_level   INT          NOT NULL DEFAULT 1 CHECK (min_skill_level BETWEEN 1 AND 5),
    volunteers_needed INT          NOT NULL DEFAULT 1 CHECK (volunteers_needed >= 1),
    priority          INT          NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 3),
    CHECK (end_time > start_time)
);

CREATE TABLE IF NOT EXISTS assignment (
    volunteer_id    VARCHAR(20) NOT NULL REFERENCES volunteer(id) ON DELETE CASCADE,
    shift_id        VARCHAR(20) NOT NULL REFERENCES shift(id) ON DELETE CASCADE,
    manual          BOOLEAN     NOT NULL DEFAULT FALSE,
    assigned_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (volunteer_id, shift_id)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    logged_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    action          VARCHAR(30)  NOT NULL,
    detail          VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_assignment_shift  ON assignment(shift_id);
CREATE INDEX IF NOT EXISTS idx_availability_vol  ON availability(volunteer_id);
CREATE INDEX IF NOT EXISTS idx_shift_start       ON shift(start_time);
CREATE INDEX IF NOT EXISTS idx_vskill_skill      ON volunteer_skill(skill_name);

-- ---------------------------------------------------------------------
--  Reporting views (the dashboard's "SQL Reports" tab reads these)
-- ---------------------------------------------------------------------

-- Hours and utilisation per volunteer
CREATE OR REPLACE VIEW v_volunteer_workload AS
SELECT  v.id,
        v.name,
        v.max_weekly_hours,
        COUNT(a.shift_id)                                                         AS shift_count,
        CAST(COALESCE(SUM(DATEDIFF('MINUTE', s.start_time, s.end_time)), 0) / 60.0 AS DECIMAL(6,2)) AS hours_assigned,
        CAST(COALESCE(SUM(DATEDIFF('MINUTE', s.start_time, s.end_time)), 0) * 100.0
             / (v.max_weekly_hours * 60) AS DECIMAL(6,1))                         AS utilization_pct
FROM    volunteer v
LEFT JOIN assignment a ON a.volunteer_id = v.id
LEFT JOIN shift      s ON s.id = a.shift_id
GROUP BY v.id, v.name, v.max_weekly_hours;

-- Staffing status per shift
CREATE OR REPLACE VIEW v_shift_coverage AS
SELECT  s.id,
        s.activity,
        s.start_time,
        s.required_skill,
        s.volunteers_needed,
        COUNT(a.volunteer_id)                       AS filled,
        s.volunteers_needed - COUNT(a.volunteer_id) AS missing,
        CASE WHEN COUNT(a.volunteer_id) = 0                   THEN 'OPEN'
             WHEN COUNT(a.volunteer_id) < s.volunteers_needed THEN 'PARTIALLY_STAFFED'
             ELSE 'FULLY_STAFFED' END               AS status
FROM    shift s
LEFT JOIN assignment a ON a.shift_id = s.id
GROUP BY s.id, s.activity, s.start_time, s.required_skill, s.volunteers_needed;

-- Supply vs demand per skill (spots a skill shortage before balancing even runs)
CREATE OR REPLACE VIEW v_skill_supply_demand AS
SELECT  sk.name                                                                    AS skill,
        (SELECT COUNT(*) FROM volunteer_skill vs WHERE vs.skill_name = sk.name)   AS volunteers_with_skill,
        (SELECT COALESCE(SUM(sh.volunteers_needed), 0)
           FROM shift sh WHERE sh.required_skill = sk.name)                        AS seats_demanded,
        CAST((SELECT COALESCE(SUM(DATEDIFF('MINUTE', sh.start_time, sh.end_time) * sh.volunteers_needed), 0)
           FROM shift sh WHERE sh.required_skill = sk.name) / 60.0 AS DECIMAL(7,1)) AS hours_demanded,
        (SELECT COALESCE(SUM(v.max_weekly_hours), 0)
           FROM volunteer v JOIN volunteer_skill vs ON vs.volunteer_id = v.id
          WHERE vs.skill_name = sk.name)                                           AS max_hours_offered
FROM    skill sk;

-- Daily load
CREATE OR REPLACE VIEW v_daily_load AS
SELECT  t.shift_day,
        COUNT(*)         AS shifts,
        SUM(t.needed)    AS seats_needed,
        SUM(t.filled)    AS seats_filled,
        CAST(SUM(t.filled) * 100.0 / SUM(t.needed) AS DECIMAL(5,1)) AS coverage_pct
FROM   (SELECT CAST(s.start_time AS DATE) AS shift_day,
               s.volunteers_needed        AS needed,
               (SELECT COUNT(*) FROM assignment a WHERE a.shift_id = s.id) AS filled
          FROM shift s) t
GROUP BY t.shift_day;
