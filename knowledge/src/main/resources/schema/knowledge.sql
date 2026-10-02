-- =====================================================================
-- FitKingIA — banco de conhecimento (fitness.db)
-- Distribuído com o app, somente leitura em runtime. Gerado a partir de
-- knowledge/src/main/resources/knowledge/*.json por BuildKnowledgeDb.
--
-- Cadeia de rastreabilidade:
--   recomendação → rules → rule_claims → claims → claim_sources → evidence_sources
-- =====================================================================
PRAGMA foreign_keys = ON;

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- ---------------------------------------------------------------------
-- Evidências
-- ---------------------------------------------------------------------
CREATE TABLE evidence_sources (
    id                TEXT PRIMARY KEY,
    title             TEXT NOT NULL,
    authors           TEXT NOT NULL,
    organization      TEXT,
    year              INTEGER,
    venue             TEXT,
    url               TEXT,
    doi               TEXT,
    source_type       TEXT NOT NULL CHECK (source_type IN ('GUIDELINE','POSITION_STAND','CONSENSUS','SYSTEMATIC_REVIEW',
                          'META_ANALYSIS','RCT','VALIDATION_STUDY','NARRATIVE_REVIEW','ARTICLE','DATABASE')),
    tier              TEXT NOT NULL CHECK (tier IN ('S','A','B')),
    topic             TEXT NOT NULL,
    summary           TEXT NOT NULL,
    last_verified     TEXT,
    verification      TEXT NOT NULL CHECK (verification IN ('VERIFIED','PENDING')),
    verification_note TEXT NOT NULL,
    CHECK (verification = 'PENDING' OR last_verified IS NOT NULL)
);

-- 🔵 FATO: afirmação com nível de evidência. Conflito = fontes SUPPORTS e CONTRADICTS na mesma afirmação.
CREATE TABLE claims (
    id             TEXT PRIMARY KEY,
    statement      TEXT NOT NULL,
    topic          TEXT NOT NULL,
    evidence_level TEXT NOT NULL CHECK (evidence_level IN ('HIGH','MODERATE','LIMITED','INCONCLUSIVE')),
    last_reviewed  TEXT NOT NULL,
    review_status  TEXT NOT NULL CHECK (review_status IN ('CURRENT','NEEDS_REVIEW'))
);

CREATE TABLE claim_sources (
    claim_id  TEXT NOT NULL REFERENCES claims(id),
    source_id TEXT NOT NULL REFERENCES evidence_sources(id),
    stance    TEXT NOT NULL CHECK (stance IN ('SUPPORTS','CONTRADICTS','CONTEXT')),
    note      TEXT,
    PRIMARY KEY (claim_id, source_id)
);

-- 🟢 REGRA DO SISTEMA: parâmetros do motor + justificativa + vínculo com as afirmações.
CREATE TABLE rules (
    id            TEXT PRIMARY KEY,
    category      TEXT NOT NULL,
    description   TEXT NOT NULL,
    basis         TEXT NOT NULL CHECK (basis IN ('EVIDENCE','HEURISTIC','CONVENTION')),
    rationale     TEXT NOT NULL,
    params_json   TEXT NOT NULL CHECK (json_valid(params_json)),
    last_reviewed TEXT NOT NULL
);

CREATE TABLE rule_claims (
    rule_id  TEXT NOT NULL REFERENCES rules(id),
    claim_id TEXT NOT NULL REFERENCES claims(id),
    PRIMARY KEY (rule_id, claim_id)
);

-- Fila do Evidence Update Engine: novas evidências aguardam validação humana.
-- Regras críticas nunca mudam automaticamente.
CREATE TABLE evidence_updates (
    id            INTEGER PRIMARY KEY,
    detected_at   TEXT NOT NULL,
    title         TEXT NOT NULL,
    doi           TEXT,
    url           TEXT,
    related_claim TEXT REFERENCES claims(id),
    status        TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    reviewer_note TEXT
);

-- ---------------------------------------------------------------------
-- Catálogo de treino
-- ---------------------------------------------------------------------
CREATE TABLE movement_patterns (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE pattern_relations (
    pattern_id TEXT NOT NULL REFERENCES movement_patterns(id),
    related_id TEXT NOT NULL REFERENCES movement_patterns(id),
    PRIMARY KEY (pattern_id, related_id),
    CHECK (pattern_id <> related_id)
);

CREATE TABLE muscles (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    region          TEXT NOT NULL CHECK (region IN ('upper','lower','core')),
    volume_tracked  INTEGER NOT NULL CHECK (volume_tracked IN (0, 1)),
    volume_factor   REAL NOT NULL DEFAULT 1.0 CHECK (volume_factor > 0),
    fill_pattern_id TEXT REFERENCES movement_patterns(id)
);

CREATE TABLE equipment (
    id       TEXT PRIMARY KEY,
    name     TEXT NOT NULL,
    category TEXT NOT NULL
);

CREATE TABLE environments (
    id   TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE environment_equipment (
    environment_id TEXT NOT NULL REFERENCES environments(id),
    equipment_id   TEXT NOT NULL REFERENCES equipment(id),
    PRIMARY KEY (environment_id, equipment_id)
);

CREATE TABLE exercises (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    pattern_id TEXT NOT NULL REFERENCES movement_patterns(id),
    mechanic   TEXT NOT NULL CHECK (mechanic IN ('COMPOUND','ISOLATION')),
    laterality TEXT NOT NULL CHECK (laterality IN ('BILATERAL','UNILATERAL')),
    load_type  TEXT NOT NULL CHECK (load_type IN ('BARBELL','DUMBBELL','MACHINE','CABLE','SMITH','KETTLEBELL','BODYWEIGHT','BAND')),
    difficulty INTEGER NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    min_tier   TEXT NOT NULL CHECK (min_tier IN ('NOVICE','INTERMEDIATE','ADVANCED')),
    stability  INTEGER NOT NULL CHECK (stability BETWEEN 1 AND 5),
    mobility   INTEGER NOT NULL CHECK (mobility BETWEEN 1 AND 5),
    -- Curadoria: 0–3, quão indicado é como escolha padrão quando há equipamento para tudo.
    staple     INTEGER NOT NULL DEFAULT 2 CHECK (staple BETWEEN 0 AND 3),
    -- Regressões: acima deste nível só entram se não houver alternativa.
    max_tier   TEXT CHECK (max_tier IN ('NOVICE','INTERMEDIATE','ADVANCED')),
    -- Isometrias/carregamentos prescritos em segundos.
    timed      INTEGER NOT NULL DEFAULT 0 CHECK (timed IN (0, 1))
);

CREATE TABLE exercise_aliases (
    exercise_id TEXT NOT NULL REFERENCES exercises(id),
    alias       TEXT NOT NULL,
    PRIMARY KEY (exercise_id, alias)
);

CREATE TABLE exercise_muscles (
    exercise_id TEXT NOT NULL REFERENCES exercises(id),
    muscle_id   TEXT NOT NULL REFERENCES muscles(id),
    role        TEXT NOT NULL CHECK (role IN ('primary','secondary')),
    PRIMARY KEY (exercise_id, muscle_id)
);

CREATE TABLE exercise_equipment (
    exercise_id  TEXT NOT NULL REFERENCES exercises(id),
    equipment_id TEXT NOT NULL REFERENCES equipment(id),
    PRIMARY KEY (exercise_id, equipment_id)
);

-- Demanda articular 0–3 usada nos filtros de dor relatada (não é diagnóstico).
CREATE TABLE exercise_joint_demand (
    exercise_id TEXT NOT NULL REFERENCES exercises(id),
    joint       TEXT NOT NULL CHECK (joint IN ('KNEE','HIP','LOWER_BACK','SHOULDER','ELBOW','WRIST','ANKLE','NECK')),
    demand      INTEGER NOT NULL CHECK (demand BETWEEN 0 AND 3),
    PRIMARY KEY (exercise_id, joint)
);

CREATE TABLE exercise_texts (
    exercise_id TEXT NOT NULL REFERENCES exercises(id),
    kind        TEXT NOT NULL CHECK (kind IN ('instruction','common_mistake','safety_note','progression_method')),
    position    INTEGER NOT NULL,
    text        TEXT NOT NULL,
    PRIMARY KEY (exercise_id, kind, position)
);

CREATE TABLE exercise_substitutions (
    exercise_id   TEXT NOT NULL REFERENCES exercises(id),
    substitute_id TEXT NOT NULL REFERENCES exercises(id),
    rank          INTEGER NOT NULL,
    PRIMARY KEY (exercise_id, substitute_id),
    CHECK (exercise_id <> substitute_id)
);

-- ---------------------------------------------------------------------
-- Templates de divisão
-- ---------------------------------------------------------------------
CREATE TABLE split_templates (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    days_per_week INTEGER NOT NULL CHECK (days_per_week BETWEEN 1 AND 7),
    min_tier      TEXT NOT NULL CHECK (min_tier IN ('NOVICE','INTERMEDIATE','ADVANCED')),
    priority      INTEGER NOT NULL,
    rationale     TEXT NOT NULL
);

CREATE TABLE split_focuses (
    split_id TEXT NOT NULL REFERENCES split_templates(id),
    focus    TEXT NOT NULL CHECK (focus IN ('HYPERTROPHY','STRENGTH','POWER','MUSCULAR_ENDURANCE','GENERAL_FITNESS')),
    PRIMARY KEY (split_id, focus)
);

CREATE TABLE session_templates (
    split_id TEXT NOT NULL REFERENCES split_templates(id),
    position INTEGER NOT NULL,
    key      TEXT NOT NULL,
    name     TEXT NOT NULL,
    PRIMARY KEY (split_id, position)
);

CREATE TABLE session_slots (
    split_id          TEXT NOT NULL,
    session_position  INTEGER NOT NULL,
    position          INTEGER NOT NULL,
    pattern_id        TEXT NOT NULL REFERENCES movement_patterns(id),
    role              TEXT NOT NULL CHECK (role IN ('MAIN','SECONDARY','ACCESSORY')),
    base_sets         INTEGER NOT NULL CHECK (base_sets >= 1),
    target_muscle_id  TEXT REFERENCES muscles(id),
    prefer_mechanic   TEXT CHECK (prefer_mechanic IN ('COMPOUND','ISOLATION')),
    prefer_laterality TEXT CHECK (prefer_laterality IN ('BILATERAL','UNILATERAL')),
    PRIMARY KEY (split_id, session_position, position),
    FOREIGN KEY (split_id, session_position) REFERENCES session_templates(split_id, position)
);

-- ---------------------------------------------------------------------
-- Esportes, segurança, nutrição, suplementos
-- ---------------------------------------------------------------------
CREATE TABLE sports (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    lower_body_load INTEGER NOT NULL CHECK (lower_body_load BETWEEN 0 AND 3),
    upper_body_load INTEGER NOT NULL CHECK (upper_body_load BETWEEN 0 AND 3),
    cardio_demand   INTEGER NOT NULL CHECK (cardio_demand BETWEEN 0 AND 3),
    recovery_demand INTEGER NOT NULL CHECK (recovery_demand BETWEEN 0 AND 3)
);

CREATE TABLE safety_questions (
    id             TEXT PRIMARY KEY,
    position       INTEGER NOT NULL,
    question       TEXT NOT NULL,
    category       TEXT NOT NULL,
    outcome_if_yes TEXT NOT NULL CHECK (outcome_if_yes IN ('BLOCK','CAUTION','INFO')),
    message        TEXT NOT NULL,
    applies_to     TEXT CHECK (applies_to IN ('MALE','FEMALE'))
);

-- Valores por 100 g. NULL = nutriente ainda não verificado (nunca inventado).
CREATE TABLE foods (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    kcal              REAL NOT NULL CHECK (kcal >= 0),
    protein_g         REAL CHECK (protein_g >= 0),
    carbs_g           REAL CHECK (carbs_g >= 0),
    fat_g             REAL CHECK (fat_g >= 0),
    fiber_g           REAL CHECK (fiber_g >= 0),
    sodium_mg         REAL CHECK (sodium_mg >= 0),
    default_serving_g REAL NOT NULL CHECK (default_serving_g > 0),
    serving_label     TEXT NOT NULL,
    source_id         TEXT NOT NULL REFERENCES evidence_sources(id),
    verification      TEXT NOT NULL
);

CREATE TABLE food_aliases (
    food_id TEXT NOT NULL REFERENCES foods(id),
    alias   TEXT NOT NULL,
    PRIMARY KEY (food_id, alias)
);

CREATE TABLE supplements (
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    what_is          TEXT NOT NULL,
    purpose          TEXT NOT NULL,
    evidence_summary TEXT NOT NULL,
    how_studied      TEXT NOT NULL,
    known_effects    TEXT NOT NULL,
    limitations      TEXT NOT NULL,
    cautions         TEXT NOT NULL,
    evidence_level   TEXT NOT NULL CHECK (evidence_level IN ('HIGH','MODERATE','LIMITED','INCONCLUSIVE'))
);

CREATE TABLE supplement_sources (
    supplement_id TEXT NOT NULL REFERENCES supplements(id),
    source_id     TEXT NOT NULL REFERENCES evidence_sources(id),
    PRIMARY KEY (supplement_id, source_id)
);

-- ---------------------------------------------------------------------
-- Índices e visões de auditoria
-- ---------------------------------------------------------------------
CREATE INDEX idx_exercises_pattern ON exercises(pattern_id);
CREATE INDEX idx_exercise_muscles_muscle ON exercise_muscles(muscle_id, role);
CREATE INDEX idx_claim_sources_source ON claim_sources(source_id);

-- "Por que isso?" em SQL puro: regra → afirmação → fonte.
CREATE VIEW v_rule_evidence AS
SELECT r.id AS rule_id, r.basis, c.id AS claim_id, c.evidence_level, cs.stance,
       s.id AS source_id, s.title, s.year, s.doi, s.url, s.tier, s.last_verified
FROM rules r
JOIN rule_claims rc ON rc.rule_id = r.id
JOIN claims c ON c.id = rc.claim_id
JOIN claim_sources cs ON cs.claim_id = c.id
JOIN evidence_sources s ON s.id = cs.source_id;

-- 🧪 Evidência conflitante: o sistema mostra os estudos em vez de inventar consenso.
CREATE VIEW v_conflicting_claims AS
SELECT claim_id
FROM claim_sources
GROUP BY claim_id
HAVING SUM(stance = 'SUPPORTS') > 0 AND SUM(stance = 'CONTRADICTS') > 0;

-- Fontes que precisam de reverificação (pendentes ou verificadas há mais de 1 ano).
CREATE VIEW v_sources_to_verify AS
SELECT id, title, verification, last_verified
FROM evidence_sources
WHERE verification = 'PENDING' OR last_verified < date('now', '-1 year');
