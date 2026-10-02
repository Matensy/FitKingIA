-- =====================================================================
-- FitKingIA — banco do usuário (user.db)
-- Separado do banco de conhecimento: atualizar o conteúdo científico nunca
-- toca nos dados pessoais. Dados locais por padrão; nada vai para a IA sem
-- consentimento explícito (tabela consents). IDs de exercícios/alimentos
-- referenciam o fitness.db (sem FK entre arquivos).
-- =====================================================================
PRAGMA foreign_keys = ON;

CREATE TABLE users (
    id          INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    birth_year  INTEGER,
    sex         TEXT NOT NULL CHECK (sex IN ('MALE','FEMALE')),
    height_cm   REAL NOT NULL CHECK (height_cm > 0),
    experience  TEXT NOT NULL,
    environment TEXT,
    activity    TEXT NOT NULL DEFAULT 'MODERATE',
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Consentimento granular e auditável (LGPD): armazenamento, IA, fotos, analytics.
CREATE TABLE consents (
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind       TEXT NOT NULL CHECK (kind IN ('data_storage','ai_coach','photos','analytics','backup')),
    granted    INTEGER NOT NULL CHECK (granted IN (0, 1)),
    decided_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, kind, decided_at)
);

CREATE TABLE user_goals (
    user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    goal     TEXT NOT NULL,
    priority INTEGER NOT NULL CHECK (priority IN (1, 2)),
    since    TEXT NOT NULL DEFAULT (date('now')),
    PRIMARY KEY (user_id, priority)
);

CREATE TABLE user_preferences (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key     TEXT NOT NULL,
    value   TEXT NOT NULL,
    PRIMARY KEY (user_id, key)
);

CREATE TABLE user_availability (
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    minutes     INTEGER NOT NULL CHECK (minutes >= 0),
    PRIMARY KEY (user_id, day_of_week)
);

CREATE TABLE user_equipment (
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    equipment_id TEXT NOT NULL,
    PRIMARY KEY (user_id, equipment_id)
);

CREATE TABLE user_exercise_preferences (
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    exercise_id TEXT NOT NULL,
    preference  TEXT NOT NULL CHECK (preference IN ('favorite','excluded')),
    PRIMARY KEY (user_id, exercise_id)
);

CREATE TABLE user_limitations (
    id          INTEGER PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joint       TEXT NOT NULL,
    severity    INTEGER NOT NULL CHECK (severity BETWEEN 0 AND 10),
    note        TEXT,
    reported_at TEXT NOT NULL DEFAULT (datetime('now')),
    resolved_at TEXT
);

CREATE TABLE user_sports (
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sport_id    TEXT NOT NULL,
    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    intensity   INTEGER NOT NULL CHECK (intensity BETWEEN 1 AND 3),
    PRIMARY KEY (user_id, sport_id, day_of_week)
);

CREATE TABLE safety_answers (
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    question_id TEXT NOT NULL,
    answer      INTEGER NOT NULL CHECK (answer IN (0, 1)),
    answered_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, question_id, answered_at)
);

-- ---------------------------------------------------------------------
-- Corpo
-- ---------------------------------------------------------------------
CREATE TABLE body_measurements (
    id             INTEGER PRIMARY KEY,
    user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    measured_on    TEXT NOT NULL,
    weight_kg      REAL CHECK (weight_kg > 0),
    waist_cm       REAL, abdomen_cm REAL, hip_cm REAL, chest_cm REAL,
    arm_left_cm    REAL, arm_right_cm REAL, thigh_left_cm REAL, thigh_right_cm REAL, calf_cm REAL,
    note           TEXT
);

CREATE TABLE progress_photos (
    id            INTEGER PRIMARY KEY,
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    taken_on      TEXT NOT NULL,
    angle         TEXT NOT NULL CHECK (angle IN ('front','back','side')),
    file_uri      TEXT NOT NULL,
    lighting_note TEXT, distance_note TEXT, pose_note TEXT
);

-- ---------------------------------------------------------------------
-- Programa e execução
-- ---------------------------------------------------------------------
CREATE TABLE programs (
    id                   INTEGER PRIMARY KEY,
    user_id              INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    split_id             TEXT NOT NULL,
    focus                TEXT NOT NULL,
    tier                 TEXT NOT NULL,
    created_at           TEXT NOT NULL DEFAULT (datetime('now')),
    kb_content_version   TEXT NOT NULL,
    active               INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    -- {"explanations": [...], "warnings": [...]} (JSON validado pelo app; o SQLite do Android pode não ter JSON1)
    explanations_json    TEXT
);

CREATE TABLE program_sessions (
    id                INTEGER PRIMARY KEY,
    program_id        INTEGER NOT NULL REFERENCES programs(id) ON DELETE CASCADE,
    position          INTEGER NOT NULL,
    key               TEXT NOT NULL,
    name              TEXT NOT NULL,
    day_of_week       INTEGER CHECK (day_of_week BETWEEN 1 AND 7),
    budget_minutes    INTEGER,
    estimated_minutes INTEGER
);

CREATE TABLE program_exercises (
    id           INTEGER PRIMARY KEY,
    session_id   INTEGER NOT NULL REFERENCES program_sessions(id) ON DELETE CASCADE,
    position     INTEGER NOT NULL,
    exercise_id  TEXT NOT NULL,
    role         TEXT NOT NULL CHECK (role IN ('MAIN','SECONDARY','ACCESSORY')),
    sets         INTEGER NOT NULL CHECK (sets >= 1),
    reps_min     INTEGER NOT NULL,
    reps_max     INTEGER NOT NULL CHECK (reps_max >= reps_min),
    rir          INTEGER NOT NULL,
    rest_seconds INTEGER NOT NULL,
    rest_min     INTEGER NOT NULL,
    rest_max     INTEGER NOT NULL,
    hold_min     INTEGER,
    hold_max     INTEGER,
    tempo        TEXT NOT NULL,
    note         TEXT
);

-- Replanejamento de uma semana específica (ex.: "faltei um treino" → opção A, B ou D).
-- Vale só para aquela semana; o programa base não muda.
CREATE TABLE week_plans (
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    week_start    TEXT NOT NULL,
    from_day      INTEGER NOT NULL CHECK (from_day BETWEEN 1 AND 7),
    reason        TEXT NOT NULL,
    sessions_json TEXT NOT NULL,
    created_at    TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, week_start)
);

CREATE TABLE readiness_checks (
    id          INTEGER PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    checked_at  TEXT NOT NULL DEFAULT (datetime('now')),
    sleep       TEXT NOT NULL CHECK (sleep IN ('POOR','NORMAL','EXCELLENT')),
    energy      INTEGER NOT NULL CHECK (energy BETWEEN 1 AND 10),
    soreness    INTEGER NOT NULL CHECK (soreness BETWEEN 0 AND 10),
    stress      INTEGER NOT NULL CHECK (stress BETWEEN 1 AND 10),
    motivation  INTEGER NOT NULL CHECK (motivation BETWEEN 1 AND 10),
    score       INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100)
);

CREATE TABLE workouts (
    id                 INTEGER PRIMARY KEY,
    user_id            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    program_session_id INTEGER REFERENCES program_sessions(id) ON DELETE SET NULL,
    readiness_check_id INTEGER REFERENCES readiness_checks(id) ON DELETE SET NULL,
    started_at         TEXT NOT NULL,
    finished_at        TEXT,
    perceived          TEXT CHECK (perceived IN ('VERY_EASY','ADEQUATE','HARD','EXTREMELY_HARD')),
    note               TEXT,
    -- sessão como foi planejada no dia (após ajustes de tempo/prontidão), para retomar e comparar
    plan_json          TEXT
);

-- O que realmente aconteceu (carga, reps, RIR) — base do Progressive Overload Engine.
CREATE TABLE workout_sets (
    id           INTEGER PRIMARY KEY,
    workout_id   INTEGER NOT NULL REFERENCES workouts(id) ON DELETE CASCADE,
    exercise_id  TEXT NOT NULL,
    set_index    INTEGER NOT NULL,
    load_kg      REAL NOT NULL CHECK (load_kg >= 0),
    reps         INTEGER NOT NULL CHECK (reps >= 0),
    rir          INTEGER CHECK (rir BETWEEN 0 AND 10),
    is_warmup    INTEGER NOT NULL DEFAULT 0 CHECK (is_warmup IN (0, 1)),
    completed_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE personal_records (
    id          INTEGER PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    exercise_id TEXT NOT NULL,
    type        TEXT NOT NULL CHECK (type IN ('HEAVIEST_LOAD','MOST_REPS_AT_LOAD','BEST_E1RM','BEST_SESSION_VOLUME','BEST_DISTANCE','BEST_TIME')),
    value       REAL NOT NULL,
    achieved_on TEXT NOT NULL,
    workout_id  INTEGER REFERENCES workouts(id) ON DELETE SET NULL,
    description TEXT NOT NULL
);

CREATE TABLE missed_workouts (
    id                 INTEGER PRIMARY KEY,
    user_id            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    program_session_id INTEGER REFERENCES program_sessions(id) ON DELETE SET NULL,
    missed_on          TEXT NOT NULL,
    chosen_option      TEXT CHECK (chosen_option IN ('A','B','C','D')),
    decided_at         TEXT
);

-- ---------------------------------------------------------------------
-- Nutrição, água, sono, cardio, mobilidade
-- ---------------------------------------------------------------------
CREATE TABLE nutrition_targets (
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    valid_from    TEXT NOT NULL,
    kcal          INTEGER NOT NULL,
    protein_min_g INTEGER NOT NULL,
    protein_max_g INTEGER NOT NULL,
    energy_goal   TEXT NOT NULL,
    PRIMARY KEY (user_id, valid_from)
);

CREATE TABLE meals (
    id       INTEGER PRIMARY KEY,
    user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    eaten_at TEXT NOT NULL,
    label    TEXT,
    raw_text TEXT
);

CREATE TABLE meal_items (
    id        INTEGER PRIMARY KEY,
    meal_id   INTEGER NOT NULL REFERENCES meals(id) ON DELETE CASCADE,
    food_id   TEXT NOT NULL,
    grams     REAL NOT NULL CHECK (grams > 0),
    kcal      REAL NOT NULL,
    protein_g REAL, carbs_g REAL, fat_g REAL, fiber_g REAL
);

CREATE TABLE water_logs (
    id        INTEGER PRIMARY KEY,
    user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    logged_at TEXT NOT NULL DEFAULT (datetime('now')),
    ml        INTEGER NOT NULL CHECK (ml > 0)
);

CREATE TABLE sleep_logs (
    id          INTEGER PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    night_of    TEXT NOT NULL,
    bed_time    TEXT,
    wake_time   TEXT,
    hours       REAL CHECK (hours BETWEEN 0 AND 24),
    quality     INTEGER CHECK (quality BETWEEN 1 AND 5),
    awakenings  INTEGER CHECK (awakenings >= 0),
    sleepiness  INTEGER CHECK (sleepiness BETWEEN 1 AND 10)
);

CREATE TABLE cardio_sessions (
    id           INTEGER PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    started_at   TEXT NOT NULL,
    kind         TEXT NOT NULL,
    duration_min REAL NOT NULL CHECK (duration_min > 0),
    distance_km  REAL CHECK (distance_km >= 0),
    avg_hr       INTEGER,
    rpe          INTEGER CHECK (rpe BETWEEN 1 AND 10),
    note         TEXT
);

CREATE TABLE mobility_sessions (
    id           INTEGER PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    done_at      TEXT NOT NULL,
    region       TEXT NOT NULL,
    duration_min REAL NOT NULL,
    note         TEXT
);

-- ---------------------------------------------------------------------
-- Engajamento, auditoria e IA
-- ---------------------------------------------------------------------
CREATE TABLE xp_events (
    id      INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event   TEXT NOT NULL,
    points  INTEGER NOT NULL,
    at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Toda recomendação exibida guarda as regras e a versão do conhecimento usadas:
-- permite responder "por que me recomendaram isso em março?" mesmo após atualizações.
CREATE TABLE recommendation_log (
    id                 INTEGER PRIMARY KEY,
    user_id            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shown_at           TEXT NOT NULL DEFAULT (datetime('now')),
    kind               TEXT NOT NULL,
    provenance         TEXT NOT NULL CHECK (provenance IN ('FACT','SYSTEM_RULE','AI_SUGGESTION')),
    rule_ids           TEXT,
    kb_content_version TEXT NOT NULL,
    payload_json       TEXT NOT NULL
);

-- Conversas com o AI Coach: só existem com consentimento 'ai_coach'.
CREATE TABLE ai_messages (
    id                  INTEGER PRIMARY KEY,
    user_id             INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    at                  TEXT NOT NULL DEFAULT (datetime('now')),
    role                TEXT NOT NULL CHECK (role IN ('user','assistant','tool')),
    content             TEXT NOT NULL,
    shared_context_json TEXT
);

CREATE TABLE user_events (
    id           INTEGER PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    at           TEXT NOT NULL DEFAULT (datetime('now')),
    kind         TEXT NOT NULL,
    payload_json TEXT
);

CREATE INDEX idx_workout_sets_exercise ON workout_sets(exercise_id, completed_at);
CREATE INDEX idx_workouts_user ON workouts(user_id, started_at);
CREATE INDEX idx_measurements_user ON body_measurements(user_id, measured_on);
CREATE INDEX idx_water_user ON water_logs(user_id, logged_at);
