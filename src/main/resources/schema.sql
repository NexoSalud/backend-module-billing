-- ============================================================
-- MÓDULO DE RECAUDO / FACTURACIÓN - NexoSalud HIS
-- Normativa: Acuerdo 260/2004 CRES, Circular Externa 048/2025,
--            Resolución 3488/2025 (UVB 2026), Decreto 1652/2022,
--            Ley 1751/2015, Ley 1581/2012, Res. 948/2026 RIPS
-- Contrato B v2.0 — Recaudo → Facturación
-- ============================================================

-- ─── Pre-migraciones: añadir columnas nuevas a tablas ya existentes en BD ────
-- Deben ejecutarse ANTES de cualquier CREATE TABLE o INSERT para que sean
-- idempotentes tanto en despliegues frescos como en actualizaciones.
ALTER TABLE IF EXISTS cuotas_moderadoras  ADD COLUMN IF NOT EXISTS valor_uvb      NUMERIC(10,4);
ALTER TABLE IF EXISTS topes_copago        ADD COLUMN IF NOT EXISTS valor_uvb      NUMERIC(10,4) NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS medical_orders      ADD COLUMN IF NOT EXISTS episodio_id    VARCHAR(50);
ALTER TABLE IF EXISTS medical_orders      ADD COLUMN IF NOT EXISTS appointment_id BIGINT;
ALTER TABLE IF EXISTS medical_orders      ADD COLUMN IF NOT EXISTS diagnosis_code VARCHAR(10);
ALTER TABLE IF EXISTS medical_orders      ADD COLUMN IF NOT EXISTS diagnosis_desc VARCHAR(500);
ALTER TABLE IF EXISTS medical_orders      ADD COLUMN IF NOT EXISTS order_notes    TEXT;

-- ─── UVB vigente (Circular Externa 048/2025, Res. 3488/2025) ─────────────────
-- Indexación obligatoria desde 01-ene-2026
CREATE TABLE IF NOT EXISTS uvb_vigente (
    id              SERIAL PRIMARY KEY,
    valor_pesos     NUMERIC(18,2) NOT NULL,          -- equivalencia en pesos
    vigencia_desde  DATE NOT NULL,
    vigencia_hasta  DATE,
    resolucion      VARCHAR(100),
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO uvb_vigente (valor_pesos, vigencia_desde, vigencia_hasta, resolucion, activo) VALUES
(42412, '2026-01-01', NULL, 'Resolución 3488/2025 - Hacienda', TRUE),
(39200, '2025-01-01', '2025-12-31', 'Resolución anterior 2025', FALSE)
ON CONFLICT DO NOTHING;

-- ─── Cuotas moderadoras (Acuerdo 260/2004 + Circular 048/2025) ───────────────
-- Valores en pesos (convertidos desde UVB × factor)
CREATE TABLE IF NOT EXISTS cuotas_moderadoras (
    id              SERIAL PRIMARY KEY,
    regimen         VARCHAR(30) NOT NULL,   -- CONTRIBUTIVO, SUBSIDIADO
    categoria       VARCHAR(10) NOT NULL,   -- A, B, C
    tipo_servicio   VARCHAR(60) NOT NULL,
    valor_uvb       NUMERIC(10,4),          -- valor en UVB (obligatorio desde 2026)
    valor_pesos     NUMERIC(18,2) NOT NULL, -- equivalencia calculada
    vigencia_desde  DATE NOT NULL,
    vigencia_hasta  DATE,
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_cuota_mod UNIQUE (regimen, categoria, tipo_servicio, vigencia_desde)
);

-- Valores 2026 indexados a UVB (Circular Externa 048/2025)
-- UVB 2026 = $42.412 (Res. 3488/2025)
INSERT INTO cuotas_moderadoras (regimen, categoria, tipo_servicio, valor_uvb, valor_pesos, vigencia_desde) VALUES
-- Contributivo A (IBC < 2 SMLMV) — ~0.1 UVB
('CONTRIBUTIVO','A','CONSULTA_MEDICA_GENERAL',    0.1000,  4241,  '2026-01-01'),
('CONTRIBUTIVO','A','CONSULTA_ESPECIALISTA',       0.1700,  7210,  '2026-01-01'),
('CONTRIBUTIVO','A','URGENCIAS',                   0.5700, 24175,  '2026-01-01'),
('CONTRIBUTIVO','A','LABORATORIO',                 0.1000,  4241,  '2026-01-01'),
('CONTRIBUTIVO','A','IMAGEN_DIAGNOSTICA',          0.1700,  7210,  '2026-01-01'),
('CONTRIBUTIVO','A','PROCEDIMIENTO_AMBULATORIO',   0.1700,  7210,  '2026-01-01'),
-- Contributivo B (IBC 2-5 SMLMV) — ~0.17 UVB
('CONTRIBUTIVO','B','CONSULTA_MEDICA_GENERAL',     0.1700,  7210,  '2026-01-01'),
('CONTRIBUTIVO','B','CONSULTA_ESPECIALISTA',       0.2950, 12511,  '2026-01-01'),
('CONTRIBUTIVO','B','URGENCIAS',                   0.8560, 36305,  '2026-01-01'),
('CONTRIBUTIVO','B','LABORATORIO',                 0.1700,  7210,  '2026-01-01'),
('CONTRIBUTIVO','B','IMAGEN_DIAGNOSTICA',          0.2950, 12511,  '2026-01-01'),
('CONTRIBUTIVO','B','PROCEDIMIENTO_AMBULATORIO',   0.2950, 12511,  '2026-01-01'),
-- Contributivo C (IBC > 5 SMLMV) — ~0.295 UVB
('CONTRIBUTIVO','C','CONSULTA_MEDICA_GENERAL',     0.2950, 12511,  '2026-01-01'),
('CONTRIBUTIVO','C','CONSULTA_ESPECIALISTA',       0.5710, 24217,  '2026-01-01'),
('CONTRIBUTIVO','C','URGENCIAS',                   1.4270, 60522,  '2026-01-01'),
('CONTRIBUTIVO','C','LABORATORIO',                 0.2950, 12511,  '2026-01-01'),
('CONTRIBUTIVO','C','IMAGEN_DIAGNOSTICA',          0.5710, 24217,  '2026-01-01'),
('CONTRIBUTIVO','C','PROCEDIMIENTO_AMBULATORIO',   0.5710, 24217,  '2026-01-01'),
-- Subsidiado (copago según norma)
('SUBSIDIADO',  'A','CONSULTA_MEDICA_GENERAL',     0.0000,     0,  '2026-01-01'),
('SUBSIDIADO',  'A','CONSULTA_ESPECIALISTA',        0.0000,     0,  '2026-01-01'),
('SUBSIDIADO',  'B','CONSULTA_MEDICA_GENERAL',     0.0500,  2121,  '2026-01-01'),
('SUBSIDIADO',  'B','CONSULTA_ESPECIALISTA',        0.1000,  4241,  '2026-01-01'),
('SUBSIDIADO',  'C','CONSULTA_MEDICA_GENERAL',     0.1000,  4241,  '2026-01-01'),
('SUBSIDIADO',  'C','CONSULTA_ESPECIALISTA',        0.1700,  7210,  '2026-01-01')
ON CONFLICT ON CONSTRAINT uq_cuota_mod DO NOTHING;

-- ─── Topes de copago (Acuerdo 260/2004 + UVB 2026) ───────────────────────────
CREATE TABLE IF NOT EXISTS topes_copago (
    id              SERIAL PRIMARY KEY,
    categoria       VARCHAR(10) NOT NULL,
    tipo_tope       VARCHAR(20) NOT NULL,   -- POR_EVENTO, ANUAL
    valor_uvb       NUMERIC(10,4) NOT NULL,
    valor_pesos     NUMERIC(18,2) NOT NULL,
    vigencia_desde  DATE NOT NULL,
    vigencia_hasta  DATE,
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tope_copago UNIQUE (categoria, tipo_tope, vigencia_desde)
);

INSERT INTO topes_copago (categoria, tipo_tope, valor_uvb, valor_pesos, vigencia_desde) VALUES
('A', 'POR_EVENTO',  2.3000,  97548, '2026-01-01'),
('A', 'ANUAL',      23.0000, 975476, '2026-01-01'),
('B', 'POR_EVENTO',  4.6000, 195095, '2026-01-01'),
('B', 'ANUAL',      46.0000,1950952, '2026-01-01'),
('C', 'POR_EVENTO',  9.2000, 390190, '2026-01-01'),
('C', 'ANUAL',      92.0000,3901904, '2026-01-01')
ON CONFLICT ON CONSTRAINT uq_tope_copago DO NOTHING;

-- ─── Catálogo de exenciones (Decreto 1652/2022) ───────────────────────────────
CREATE TABLE IF NOT EXISTS exenciones_recaudo (
    id              SERIAL PRIMARY KEY,
    codigo          VARCHAR(30) NOT NULL UNIQUE,
    descripcion     VARCHAR(255) NOT NULL,
    fuente_marcador VARCHAR(100) NOT NULL,  -- dónde vive el marcador
    aplica_regimen  VARCHAR(30),            -- NULL = todos
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO exenciones_recaudo (codigo, descripcion, fuente_marcador, aplica_regimen) VALUES
('PYD',           'Protección Específica y Detección Temprana',         'es_pyd en Contrato A',         NULL),
('PARTO',         'Atención del parto y control prenatal',              'marcador_clinico HC',          NULL),
('ALTO_COSTO',    'Enfermedades de alto costo / catastróficas',         'perfil_paciente auditoria',    NULL),
('VICTIMA',       'Víctimas del conflicto armado',                      'marcador_poblacional paciente',NULL),
('PROMO_PREV',    'Programas de promoción y prevención / crónicos',     'marcador_programa CE',         NULL),
('MENOR',         'Menor de edad según Decreto 1652/2022',              'fecha_nacimiento paciente',    NULL),
('GESTANTE',      'Gestante',                                           'marcador_clinico HC',          NULL),
('SUBSIDIADO_A',  'Régimen subsidiado nivel A exento por norma',        'regimen + categoria paciente', 'SUBSIDIADO'),
('URGENCIA_VITAL','Urgencia vital — prohibición Ley 1751/2015',         'tipo_servicio = urgencia',     NULL)
ON CONFLICT (codigo) DO NOTHING;

-- ─── Acumulado anual de copago por afiliado (RN-07, decisión 1) ──────────────
CREATE TABLE IF NOT EXISTS acumulado_copago_anual (
    id              SERIAL PRIMARY KEY,
    patient_id      BIGINT NOT NULL,
    anio            INTEGER NOT NULL,
    total_copago    NUMERIC(18,2) NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_acumulado UNIQUE (patient_id, anio)
);

CREATE INDEX IF NOT EXISTS idx_acumulado_patient_anio ON acumulado_copago_anual(patient_id, anio);

-- ─── Outbox transaccional (Contrato B — patrón outbox) ───────────────────────
-- Garantía: nunca hay "comprobante emitido pero evento perdido"
CREATE TABLE IF NOT EXISTS contrato_b_outbox (
    id                      SERIAL PRIMARY KEY,
    evento_id               UUID NOT NULL UNIQUE,           -- idempotencia
    episodio_id             VARCHAR(50),                    -- null si el recaudo no tiene episodio (ej: particular sin admisión)
    recaudo_id              BIGINT NOT NULL,
    payload                 JSONB NOT NULL,                 -- Contrato B completo
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    -- PENDIENTE, ENVIADO, FALLIDO, DEAD_LETTER
    intentos                INTEGER NOT NULL DEFAULT 0,
    proximo_intento         TIMESTAMP,
    enviado_at              TIMESTAMP,
    error_mensaje           TEXT,
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_status ON contrato_b_outbox(status, proximo_intento);
CREATE INDEX IF NOT EXISTS idx_outbox_evento ON contrato_b_outbox(evento_id);
CREATE INDEX IF NOT EXISTS idx_outbox_episodio ON contrato_b_outbox(episodio_id);

-- ─── Tabla principal de recaudos ──────────────────────────────────────────────
-- Máquina de estados: PENDIENTE → PARCIAL → SALDADO → ANULADO
--                     PENDIENTE → NO_APLICA (exentos)
--                     PENDIENTE → ANULADO
CREATE TABLE IF NOT EXISTS recaudos (
    id                      SERIAL PRIMARY KEY,
    numero_comprobante      VARCHAR(30) NOT NULL UNIQUE,    -- REC-YYYYMM-NNNNNN (consecutivo fiscal)
    -- Contrato B v2.0
    evento_id               UUID NOT NULL DEFAULT gen_random_uuid(),
    episodio_id             VARCHAR(50),                    -- del Contrato A (Admisión)
    corrige_comprobante_id  VARCHAR(30),                    -- corrección de recaudo previo
    contrato_version        VARCHAR(10) NOT NULL DEFAULT '2.0',
    -- Paciente y afiliación
    patient_id              BIGINT NOT NULL,
    cajero_id               BIGINT NOT NULL,
    sede_id                 BIGINT,
    eps_id                  VARCHAR(50),                    -- null si particular
    eps_nombre              VARCHAR(255),
    regimen                 VARCHAR(30),                    -- CONTRIBUTIVO, SUBSIDIADO, ESPECIAL, PARTICULAR, ARL, SOAT
    rol_afiliado            VARCHAR(20),                    -- COTIZANTE, BENEFICIARIO
    categoria_ibc           VARCHAR(5),                     -- A, B, C
    -- Servicio
    tipo_servicio           VARCHAR(30),                    -- consulta_general, urgencia, hospitalizacion, procedimiento
    numero_autorizacion     VARCHAR(50),                    -- transportado de Admisión, no validado aquí
    es_pyd                  BOOLEAN NOT NULL DEFAULT FALSE, -- Protección Específica y Detección Temprana
    exencion_codigo         VARCHAR(30),                    -- código de la exención aplicada
    -- Cobro
    tipo_cobro              VARCHAR(25),                    -- cuota_moderadora, copago, particular, exento
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    medio_pago              VARCHAR(20),                    -- EFECTIVO, TARJETA, TRANSFERENCIA
    valor_total             NUMERIC(18,2) NOT NULL DEFAULT 0,
    valor_recibido          NUMERIC(18,2),
    cambio                  NUMERIC(18,2),
    comprobante_inmutable   BOOLEAN NOT NULL DEFAULT TRUE,
    -- Auditoría
    observaciones           TEXT,
    modificado_por          BIGINT,                         -- RN-11: modificación requiere permiso
    modificacion_motivo     TEXT,
    valor_original          NUMERIC(18,2),                  -- valor antes de modificación autorizada
    anulacion_motivo        TEXT,
    anulado_por             BIGINT,
    anulado_at              TIMESTAMP,
    confirmado_at           TIMESTAMP,
    fecha_atencion          DATE,                           -- RN-01: fecha del evento clínico
    fecha_emision_evento    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_recaudos_patient    ON recaudos(patient_id);
CREATE INDEX IF NOT EXISTS idx_recaudos_status     ON recaudos(status);
CREATE INDEX IF NOT EXISTS idx_recaudos_cajero     ON recaudos(cajero_id);
CREATE INDEX IF NOT EXISTS idx_recaudos_fecha      ON recaudos(created_at);
CREATE INDEX IF NOT EXISTS idx_recaudos_comprobante ON recaudos(numero_comprobante);
CREATE INDEX IF NOT EXISTS idx_recaudos_episodio   ON recaudos(episodio_id);
CREATE INDEX IF NOT EXISTS idx_recaudos_evento     ON recaudos(evento_id);

-- ─── Ítems del recaudo ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS recaudo_items (
    id                  SERIAL PRIMARY KEY,
    recaudo_id          BIGINT NOT NULL REFERENCES recaudos(id) ON DELETE CASCADE,
    medical_order_id    BIGINT,
    appointment_id      BIGINT,
    cups_code           VARCHAR(10) NOT NULL,
    cups_description    VARCHAR(500) NOT NULL,
    service_type        VARCHAR(30) NOT NULL,
    ambito              VARCHAR(30) NOT NULL DEFAULT 'AMBULATORIO',
    professional_id     BIGINT,
    professional_name   VARCHAR(255),
    service_date        DATE NOT NULL,
    base_tariff         NUMERIC(18,2) NOT NULL DEFAULT 0,
    descuento_convenio  NUMERIC(18,2) NOT NULL DEFAULT 0,
    cuota_moderadora    NUMERIC(18,2) NOT NULL DEFAULT 0,
    copago              NUMERIC(18,2) NOT NULL DEFAULT 0,
    tope_evento_aplicado BOOLEAN NOT NULL DEFAULT FALSE,
    tope_anual_aplicado  BOOLEAN NOT NULL DEFAULT FALSE,
    valor_cobrado       NUMERIC(18,2) NOT NULL DEFAULT 0,
    exento              BOOLEAN NOT NULL DEFAULT FALSE,
    exencion_codigo     VARCHAR(30),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_recaudo_items_recaudo ON recaudo_items(recaudo_id);
CREATE INDEX IF NOT EXISTS idx_recaudo_items_order   ON recaudo_items(medical_order_id);

-- ─── Órdenes médicas pendientes de recaudo ────────────────────────────────────
CREATE TABLE IF NOT EXISTS medical_orders (
    id                  SERIAL PRIMARY KEY,
    patient_id          BIGINT NOT NULL,
    professional_id     BIGINT NOT NULL,
    appointment_id      BIGINT,
    episodio_id         VARCHAR(50),
    cups_code           VARCHAR(10) NOT NULL,
    cups_description    VARCHAR(500) NOT NULL,
    service_type        VARCHAR(30) NOT NULL,
    ambito              VARCHAR(30) NOT NULL DEFAULT 'AMBULATORIO',
    base_tariff         NUMERIC(18,2) NOT NULL DEFAULT 0,
    iss_multiplier      NUMERIC(5,2) NOT NULL DEFAULT 1.0,
    es_pyd              BOOLEAN NOT NULL DEFAULT FALSE,
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE_RECAUDO',
    order_date          DATE NOT NULL,
    order_notes         TEXT,
    diagnosis_code      VARCHAR(10),
    diagnosis_desc      VARCHAR(500),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_medical_orders_patient    ON medical_orders(patient_id, status);
CREATE INDEX IF NOT EXISTS idx_medical_orders_status     ON medical_orders(status);
-- Nota: idx_medical_orders_appointment e idx_medical_orders_episodio se crean
-- en el bloque de migraciones al final, después del ALTER TABLE que garantiza
-- que las columnas existen en BD ya desplegadas.

-- ─── Tarifas CUPS ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cups_tarifas (
    id              SERIAL PRIMARY KEY,
    cups_code       VARCHAR(10) NOT NULL UNIQUE,
    descripcion     VARCHAR(500) NOT NULL,
    grupo           VARCHAR(100),
    subgrupo        VARCHAR(100),
    tarifa_iss_2001 NUMERIC(18,2) NOT NULL DEFAULT 0,
    tarifa_soat     NUMERIC(18,2),
    unidad_medida   VARCHAR(50),
    es_pyd          BOOLEAN NOT NULL DEFAULT FALSE,  -- exento de cuota moderadora
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cups_code        ON cups_tarifas(cups_code);
CREATE INDEX IF NOT EXISTS idx_cups_descripcion ON cups_tarifas(descripcion);

-- ─── Secuencia para numeración fiscal de comprobantes ────────────────────────
CREATE SEQUENCE IF NOT EXISTS recaudo_seq START 1 INCREMENT 1;

-- ─── Migraciones idempotentes (columnas añadidas después del despliegue inicial) ─
-- Estas sentencias son seguras de re-ejecutar: no hacen nada si la columna ya existe.

-- medical_orders: columnas añadidas en v2
ALTER TABLE medical_orders ADD COLUMN IF NOT EXISTS episodio_id      VARCHAR(50);
ALTER TABLE medical_orders ADD COLUMN IF NOT EXISTS appointment_id   BIGINT;
ALTER TABLE medical_orders ADD COLUMN IF NOT EXISTS diagnosis_code   VARCHAR(10);
ALTER TABLE medical_orders ADD COLUMN IF NOT EXISTS diagnosis_desc   VARCHAR(500);
ALTER TABLE medical_orders ADD COLUMN IF NOT EXISTS order_notes      TEXT;

-- cuotas_moderadoras: columna valor_uvb añadida en v2 (indexación UVB Circular 048/2025)
ALTER TABLE cuotas_moderadoras ADD COLUMN IF NOT EXISTS valor_uvb NUMERIC(10,4);

-- topes_copago: columna valor_uvb añadida en v2
ALTER TABLE topes_copago ADD COLUMN IF NOT EXISTS valor_uvb NUMERIC(10,4) NOT NULL DEFAULT 0;

-- Recrear índices que dependen de columnas nuevas (IF NOT EXISTS los hace idempotentes)
CREATE INDEX IF NOT EXISTS idx_medical_orders_episodio    ON medical_orders(episodio_id);
CREATE INDEX IF NOT EXISTS idx_medical_orders_appointment ON medical_orders(appointment_id);
