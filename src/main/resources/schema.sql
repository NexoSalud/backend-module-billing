-- ============================================================
-- MÓDULO DE RECAUDO / FACTURACIÓN - NexoSalud
-- Normativa: Resolución 3374/2000 RIPS, Acuerdo 260/2004 CRES,
--            Ley 1438/2011, Decreto 4747/2007
-- ============================================================

-- Tabla de órdenes médicas (procedimientos, labs, imágenes, etc.)
-- Generadas desde Historia Clínica con estado PENDIENTE_RECAUDO
CREATE TABLE IF NOT EXISTS medical_orders (
    id                  SERIAL PRIMARY KEY,
    patient_id          BIGINT NOT NULL,
    professional_id     BIGINT NOT NULL,
    appointment_id      BIGINT,                          -- cita origen (puede ser null para órdenes ambulatorias)
    cups_code           VARCHAR(10) NOT NULL,            -- Código CUPS (Clasificación Única de Procedimientos en Salud)
    cups_description    VARCHAR(500) NOT NULL,
    service_type        VARCHAR(30) NOT NULL,            -- CONSULTA, PROCEDIMIENTO, LABORATORIO, IMAGEN, MEDICAMENTO, OTRO
    ambito              VARCHAR(30) NOT NULL DEFAULT 'AMBULATORIO', -- AMBULATORIO, URGENCIAS, HOSPITALIZACION
    base_tariff         NUMERIC(18,2) NOT NULL DEFAULT 0,
    iss_multiplier      NUMERIC(5,2) NOT NULL DEFAULT 1.0, -- Multiplicador ISS/SOAT
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDIENTE_RECAUDO',
    -- PENDIENTE_RECAUDO, EN_RECAUDO, RECAUDADO, ANULADO, EXENTO
    order_date          DATE NOT NULL,
    order_notes         TEXT,
    diagnosis_code      VARCHAR(10),                     -- CIE-10
    diagnosis_desc      VARCHAR(500),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_medical_orders_patient ON medical_orders(patient_id, status);
CREATE INDEX IF NOT EXISTS idx_medical_orders_status ON medical_orders(status);
CREATE INDEX IF NOT EXISTS idx_medical_orders_appointment ON medical_orders(appointment_id);

-- Tabla principal de recaudos (transacciones de cobro)
CREATE TABLE IF NOT EXISTS recaudos (
    id                  SERIAL PRIMARY KEY,
    numero_comprobante  VARCHAR(30) NOT NULL UNIQUE,     -- REC-YYYYMM-NNNNNN
    patient_id          BIGINT NOT NULL,
    cajero_id           BIGINT NOT NULL,                 -- employee_id del facturador
    sede_id             BIGINT,
    eps_nombre          VARCHAR(255),
    regimen             VARCHAR(30),                     -- CONTRIBUTIVO, SUBSIDIADO, ESPECIAL, PARTICULAR
    status              VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    -- BORRADOR, CONFIRMADO, ANULADO
    medio_pago          VARCHAR(20),                     -- EFECTIVO, TARJETA, TRANSFERENCIA
    valor_total         NUMERIC(18,2) NOT NULL DEFAULT 0,
    valor_recibido      NUMERIC(18,2),
    cambio              NUMERIC(18,2),
    observaciones       TEXT,
    anulacion_motivo    TEXT,
    anulado_por         BIGINT,
    anulado_at          TIMESTAMP,
    confirmado_at       TIMESTAMP,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_recaudos_patient ON recaudos(patient_id);
CREATE INDEX IF NOT EXISTS idx_recaudos_status ON recaudos(status);
CREATE INDEX IF NOT EXISTS idx_recaudos_cajero ON recaudos(cajero_id);
CREATE INDEX IF NOT EXISTS idx_recaudos_fecha ON recaudos(created_at);
CREATE INDEX IF NOT EXISTS idx_recaudos_comprobante ON recaudos(numero_comprobante);

-- Ítems del recaudo (servicios cobrados en cada transacción)
CREATE TABLE IF NOT EXISTS recaudo_items (
    id                  SERIAL PRIMARY KEY,
    recaudo_id          BIGINT NOT NULL REFERENCES recaudos(id) ON DELETE CASCADE,
    medical_order_id    BIGINT REFERENCES medical_orders(id),
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
    cuota_moderadora    NUMERIC(18,2) NOT NULL DEFAULT 0,  -- Valor real a cobrar al paciente
    copago              NUMERIC(18,2) NOT NULL DEFAULT 0,
    valor_cobrado       NUMERIC(18,2) NOT NULL DEFAULT 0,  -- = cuota_moderadora + copago
    exento              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_recaudo_items_recaudo ON recaudo_items(recaudo_id);
CREATE INDEX IF NOT EXISTS idx_recaudo_items_order ON recaudo_items(medical_order_id);

-- Secuencia para numeración de comprobantes
CREATE SEQUENCE IF NOT EXISTS recaudo_seq START 1 INCREMENT 1;

-- Tabla de tarifas CUPS (referencia normativa)
-- Basada en ISS 2001 + actualizaciones SOAT
CREATE TABLE IF NOT EXISTS cups_tarifas (
    id                  SERIAL PRIMARY KEY,
    cups_code           VARCHAR(10) NOT NULL UNIQUE,
    descripcion         VARCHAR(500) NOT NULL,
    grupo              VARCHAR(100),
    subgrupo           VARCHAR(100),
    tarifa_iss_2001     NUMERIC(18,2) NOT NULL DEFAULT 0,
    tarifa_soat         NUMERIC(18,2),
    unidad_medida       VARCHAR(50),
    activo              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cups_code ON cups_tarifas(cups_code);
CREATE INDEX IF NOT EXISTS idx_cups_descripcion ON cups_tarifas(descripcion);

-- Tabla de cuotas moderadoras por régimen (Acuerdo 260/2004 CRES actualizado)
CREATE TABLE IF NOT EXISTS cuotas_moderadoras (
    id                  SERIAL PRIMARY KEY,
    regimen             VARCHAR(30) NOT NULL,            -- CONTRIBUTIVO, SUBSIDIADO
    categoria           VARCHAR(10) NOT NULL,            -- A, B, C (por IBC)
    tipo_servicio       VARCHAR(50) NOT NULL,            -- CONSULTA_MEDICA, CONSULTA_ESPECIALISTA, etc.
    valor               NUMERIC(18,2) NOT NULL,
    vigencia_desde      DATE NOT NULL,
    vigencia_hasta      DATE,
    activo              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Datos iniciales de cuotas moderadoras (Acuerdo 260/2004 - valores 2024 actualizados)
INSERT INTO cuotas_moderadoras (regimen, categoria, tipo_servicio, valor, vigencia_desde) VALUES
-- Régimen Contributivo - Categoría A (IBC < 2 SMLMV)
('CONTRIBUTIVO', 'A', 'CONSULTA_MEDICA_GENERAL',    4200,  '2024-01-01'),
('CONTRIBUTIVO', 'A', 'CONSULTA_ESPECIALISTA',       7200,  '2024-01-01'),
('CONTRIBUTIVO', 'A', 'URGENCIAS',                   24200, '2024-01-01'),
('CONTRIBUTIVO', 'A', 'LABORATORIO',                 4200,  '2024-01-01'),
('CONTRIBUTIVO', 'A', 'IMAGEN_DIAGNOSTICA',          7200,  '2024-01-01'),
('CONTRIBUTIVO', 'A', 'PROCEDIMIENTO_AMBULATORIO',   7200,  '2024-01-01'),
-- Régimen Contributivo - Categoría B (IBC 2-5 SMLMV)
('CONTRIBUTIVO', 'B', 'CONSULTA_MEDICA_GENERAL',     7200,  '2024-01-01'),
('CONTRIBUTIVO', 'B', 'CONSULTA_ESPECIALISTA',       12500, '2024-01-01'),
('CONTRIBUTIVO', 'B', 'URGENCIAS',                   36300, '2024-01-01'),
('CONTRIBUTIVO', 'B', 'LABORATORIO',                 7200,  '2024-01-01'),
('CONTRIBUTIVO', 'B', 'IMAGEN_DIAGNOSTICA',          12500, '2024-01-01'),
('CONTRIBUTIVO', 'B', 'PROCEDIMIENTO_AMBULATORIO',   12500, '2024-01-01'),
-- Régimen Contributivo - Categoría C (IBC > 5 SMLMV)
('CONTRIBUTIVO', 'C', 'CONSULTA_MEDICA_GENERAL',     12500, '2024-01-01'),
('CONTRIBUTIVO', 'C', 'CONSULTA_ESPECIALISTA',       24200, '2024-01-01'),
('CONTRIBUTIVO', 'C', 'URGENCIAS',                   60500, '2024-01-01'),
('CONTRIBUTIVO', 'C', 'LABORATORIO',                 12500, '2024-01-01'),
('CONTRIBUTIVO', 'C', 'IMAGEN_DIAGNOSTICA',          24200, '2024-01-01'),
('CONTRIBUTIVO', 'C', 'PROCEDIMIENTO_AMBULATORIO',   24200, '2024-01-01'),
-- Régimen Subsidiado (copago, no cuota moderadora)
('SUBSIDIADO',   'A', 'CONSULTA_MEDICA_GENERAL',     0,     '2024-01-01'),
('SUBSIDIADO',   'A', 'CONSULTA_ESPECIALISTA',       0,     '2024-01-01'),
('SUBSIDIADO',   'B', 'CONSULTA_MEDICA_GENERAL',     2100,  '2024-01-01'),
('SUBSIDIADO',   'B', 'CONSULTA_ESPECIALISTA',       4200,  '2024-01-01'),
('SUBSIDIADO',   'C', 'CONSULTA_MEDICA_GENERAL',     4200,  '2024-01-01'),
('SUBSIDIADO',   'C', 'CONSULTA_ESPECIALISTA',       7200,  '2024-01-01')
ON CONFLICT DO NOTHING;
