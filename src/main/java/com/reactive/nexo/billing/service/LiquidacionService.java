package com.reactive.nexo.billing.service;

import com.reactive.nexo.billing.dto.LiquidacionResponse;
import com.reactive.nexo.billing.repository.CupsTarifaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Servicio de liquidación automática — NexoSalud HIS Módulo de Recaudo.
 *
 * Normativa aplicada (recaudo_issues.md):
 * - Acuerdo 260/2004 CRES → categorías A/B/C, cuotas moderadoras, copagos
 * - Circular Externa 048/2025 + Res. 3488/2025 → indexación UVB 2026
 * - Decreto 1652/2022 → catálogo de exenciones
 * - Decreto 780/2016 art. 2.1.3.14 → combinaciones de afiliación prohibidas
 * - Ley 1751/2015 → prohibición de condicionar atención en urgencias
 * - Ley 100/1993 art. 279 → régimen de excepción/especial
 *
 * Reglas de negocio:
 * - RN-01: afiliación, categoría y tarifa vigentes a la fecha de atención
 * - RN-03: copago SOLO para beneficiarios; cotizante paga $0 en hospitalización
 * - RN-04: cuota moderadora fija por categoría, sin diferenciación por tipo_cita
 * - RN-05: servicios PyD → $0
 * - RN-06: dos topes simultáneos (por evento y anual)
 * - RN-08: exención corta el flujo antes del tipo de cobro
 *
 * Roles válidos por régimen (sección 2 del documento normativo):
 * - CONTRIBUTIVO: COTIZANTE o BENEFICIARIO
 * - SUBSIDIADO: N/A (titular del subsidio, no cotizante) → copago según nivel
 * - ESPECIAL/EXCEPCION: según reglamento propio → exento provisional
 * - ARL/SOAT/POLIZA: N/A → $0 (tercero pagador)
 * - PARTICULAR: N/A → tarifa plena
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiquidacionService {

    private final CupsTarifaRepository cupsTarifaRepository;
    private final DatabaseClient databaseClient;

    /**
     * Punto de entrada principal. Pasos 3-5 del flujo de cálculo.
     */
    public Mono<LiquidacionResponse> liquidar(
            String cupsCode, String serviceType,
            String regimen, String rolAfiliado, String categoria,
            BigDecimal tarifaBase, boolean esPyd, String exencionCodigo,
            Long patientId, LocalDate fechaAtencion) {

        String regimenUp = regimen != null ? regimen.toUpperCase() : "PARTICULAR";
        String rolUp     = rolAfiliado != null ? rolAfiliado.toUpperCase() : "N/A";

        // ── Paso 3: Exenciones — cortan el flujo (RN-08) ─────────────────────
        if (esPyd) {
            return Mono.just(exento(cupsCode, serviceType, tarifaBase,
                    "PYD", "Servicio PyD — exento de cuota moderadora (RN-05)"));
        }
        if (exencionCodigo != null && !exencionCodigo.isBlank()) {
            return Mono.just(exento(cupsCode, serviceType, tarifaBase,
                    exencionCodigo, "Exención: " + exencionCodigo + " (Decreto 1652/2022)"));
        }

        // ── Paso 4: Tipo de cobro según régimen ───────────────────────────────

        // ARL / SOAT / Póliza → tercero pagador, $0 al paciente
        if ("ARL".equals(regimenUp) || "SOAT".equals(regimenUp) || "POLIZA".equals(regimenUp)) {
            return Mono.just(exento(cupsCode, serviceType, tarifaBase,
                    "TERCERO_PAGADOR", "Tercero pagador (" + regimenUp + ") — $0 al paciente"));
        }

        // Particular / sin afiliación verificable → tarifa plena
        if ("PARTICULAR".equals(regimenUp) || "NO_ASEGURADO_PPNA".equals(regimenUp)
                || "SIN_AFILIACION".equals(regimenUp)) {
            return Mono.just(particular(cupsCode, serviceType, tarifaBase));
        }

        // Rol N/A explícito sin régimen conocido → particular conservador (RN-10)
        if ("N/A".equals(rolUp) && !"SUBSIDIADO".equals(regimenUp)) {
            return Mono.just(particular(cupsCode, serviceType, tarifaBase));
        }

        // Especial / Excepción → sin reglas SGSSS, exento provisional (Ley 100 art. 279)
        if ("ESPECIAL".equals(regimenUp) || "EXCEPCION".equals(regimenUp)) {
            return Mono.just(exento(cupsCode, serviceType, tarifaBase,
                    "REGIMEN_ESPECIAL",
                    "Régimen de excepción/especial — tarifa según contrato IPS-entidad (Ley 100/1993 art. 279)"));
        }

        boolean esHospitalizacion = "PROCEDIMIENTO".equalsIgnoreCase(serviceType)
                || "HOSPITALIZACION".equalsIgnoreCase(serviceType);

        // RN-03: cotizante en hospitalización/procedimiento → $0 copago
        if ("COTIZANTE".equals(rolUp) && esHospitalizacion) {
            return Mono.just(exento(cupsCode, serviceType, tarifaBase,
                    "COTIZANTE_SIN_COPAGO",
                    "Cotizante — no paga copago en hospitalización/procedimiento (RN-03)"));
        }

        // ── Paso 5: Calcular valor exacto ─────────────────────────────────────
        LocalDate fecha = fechaAtencion != null ? fechaAtencion : LocalDate.now();

        // 5a. CONTRIBUTIVO ambulatorio → cuota moderadora fija (RN-04)
        if ("CONTRIBUTIVO".equals(regimenUp) && !esHospitalizacion) {
            return cuotaModeradora(cupsCode, serviceType, regimenUp, categoria, tarifaBase, fecha);
        }

        // 5b. SUBSIDIADO ambulatorio → copago según categoría/nivel Sisbén
        //     Subsidiado categoría A → exento (nivel 1)
        if ("SUBSIDIADO".equals(regimenUp) && !esHospitalizacion) {
            if ("A".equalsIgnoreCase(categoria)) {
                return Mono.just(exento(cupsCode, serviceType, tarifaBase,
                        "SUBSIDIADO_A", "Subsidiado Nivel 1 — exento de copago (Decreto 1652/2022)"));
            }
            BigDecimal base = tarifaBase != null && tarifaBase.compareTo(BigDecimal.ZERO) > 0
                    ? tarifaBase : BigDecimal.valueOf(50000);
            return copago(cupsCode, serviceType, regimenUp, categoria, base, patientId, fecha);
        }

        // 5c. Copago hospitalización/procedimiento (beneficiarios contributivo o subsidiado)
        if (tarifaBase != null && tarifaBase.compareTo(BigDecimal.ZERO) > 0) {
            return copago(cupsCode, serviceType, regimenUp, categoria, tarifaBase, patientId, fecha);
        }

        // Fallback: cuota moderadora sin tarifa base
        return cuotaModeradora(cupsCode, serviceType, regimenUp, categoria, tarifaBase, fecha);
    }

    /** Sobrecarga de compatibilidad con código existente. */
    public Mono<LiquidacionResponse> liquidar(String cupsCode, String serviceType,
                                               String regimen, String categoria,
                                               BigDecimal tarifaBase) {
        return liquidar(cupsCode, serviceType, regimen, "COTIZANTE", categoria,
                tarifaBase, false, null, null, LocalDate.now());
    }

    // ── Cuota moderadora (CONTRIBUTIVO ambulatorio) ───────────────────────────

    private Mono<LiquidacionResponse> cuotaModeradora(
            String cupsCode, String serviceType,
            String regimen, String categoria, BigDecimal tarifaBase, LocalDate fecha) {

        String sql = "SELECT valor_pesos FROM cuotas_moderadoras " +
                "WHERE regimen = :regimen AND categoria = :categoria " +
                "AND tipo_servicio = :tipoServicio AND activo = true " +
                "AND vigencia_desde <= :fecha " +
                "AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha) " +
                "ORDER BY vigencia_desde DESC LIMIT 1";

        return databaseClient.sql(sql)
                .bind("regimen", regimen)
                .bind("categoria", categoria != null ? categoria.toUpperCase() : "B")
                .bind("tipoServicio", mapServiceType(serviceType))
                .bind("fecha", fecha)
                .map((row, meta) -> row.get("valor_pesos", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO)
                .map(cuotaValor -> LiquidacionResponse.builder()
                        .cupsCode(cupsCode)
                        .serviceType(serviceType)
                        .tarifaBase(tarifaBase != null ? tarifaBase : BigDecimal.ZERO)
                        .descuentoConvenio(BigDecimal.ZERO)
                        .cuotaModeradora(cuotaValor)
                        .copago(BigDecimal.ZERO)
                        .valorACobrar(cuotaValor)
                        .regimen(regimen)
                        .categoria(categoria)
                        .tipoCobro("cuota_moderadora")
                        .normativaAplicada("Acuerdo 260/2004 CRES + Circular 048/2025 (UVB 2026)")
                        .exento(false)
                        .alertaTopeEvento(false)
                        .alertaTopeAnual(false)
                        .build());
    }

    // ── Copago con dos topes (RN-06) ─────────────────────────────────────────

    private Mono<LiquidacionResponse> copago(
            String cupsCode, String serviceType, String regimen, String categoria,
            BigDecimal tarifaBase, Long patientId, LocalDate fecha) {

        String catUp = categoria != null ? categoria.toUpperCase() : "B";
        BigDecimal porcentaje = porcentajeCopago(regimen, catUp);
        BigDecimal copagoBruto = tarifaBase
                .multiply(porcentaje)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);

        return tope(catUp, "POR_EVENTO", fecha).flatMap(topeEvento -> {
            BigDecimal porEvento = copagoBruto.min(topeEvento);
            boolean alertaEvento = copagoBruto.compareTo(topeEvento) > 0;

            return tope(catUp, "ANUAL", fecha).flatMap(topeAnual ->
                    acumuladoAnual(patientId, fecha.getYear()).map(acumulado -> {
                        BigDecimal disponible = topeAnual.subtract(acumulado).max(BigDecimal.ZERO);
                        BigDecimal final_ = porEvento.min(disponible);
                        boolean alertaAnual = porEvento.compareTo(disponible) > 0;

                        return LiquidacionResponse.builder()
                                .cupsCode(cupsCode)
                                .serviceType(serviceType)
                                .tarifaBase(tarifaBase)
                                .descuentoConvenio(BigDecimal.ZERO)
                                .cuotaModeradora(BigDecimal.ZERO)
                                .copago(final_)
                                .valorACobrar(final_)
                                .regimen(regimen)
                                .categoria(catUp)
                                .tipoCobro("copago")
                                .normativaAplicada("Acuerdo 260/2004 CRES — dos topes (RN-06/07)")
                                .exento(false)
                                .alertaTopeEvento(alertaEvento)
                                .alertaTopeAnual(alertaAnual)
                                .build();
                    }));
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Mono<BigDecimal> tope(String categoria, String tipoTope, LocalDate fecha) {
        String sql = "SELECT valor_pesos FROM topes_copago " +
                "WHERE categoria = :cat AND tipo_tope = :tipo AND activo = true " +
                "AND vigencia_desde <= :fecha " +
                "AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha) " +
                "ORDER BY vigencia_desde DESC LIMIT 1";
        return databaseClient.sql(sql)
                .bind("cat", categoria)
                .bind("tipo", tipoTope)
                .bind("fecha", fecha)
                .map((row, meta) -> row.get("valor_pesos", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.valueOf(Long.MAX_VALUE));
    }

    private Mono<BigDecimal> acumuladoAnual(Long patientId, int anio) {
        if (patientId == null) return Mono.just(BigDecimal.ZERO);
        return databaseClient.sql(
                "SELECT COALESCE(total_copago,0) FROM acumulado_copago_anual " +
                "WHERE patient_id=:pid AND anio=:anio")
                .bind("pid", patientId)
                .bind("anio", anio)
                .map((row, meta) -> row.get(0, BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    private BigDecimal porcentajeCopago(String regimen, String categoria) {
        if ("CONTRIBUTIVO".equalsIgnoreCase(regimen)) {
            return switch (categoria) {
                case "A" -> BigDecimal.valueOf(11.5);
                case "C" -> BigDecimal.valueOf(23.0);
                default  -> BigDecimal.valueOf(17.3); // B
            };
        }
        // Subsidiado
        return switch (categoria) {
            case "A" -> BigDecimal.ZERO;
            case "C" -> BigDecimal.valueOf(10.0);
            default  -> BigDecimal.valueOf(5.0); // B
        };
    }

    private LiquidacionResponse exento(String cupsCode, String serviceType,
                                        BigDecimal tarifaBase, String codigo, String motivo) {
        return LiquidacionResponse.builder()
                .cupsCode(cupsCode)
                .serviceType(serviceType)
                .tarifaBase(tarifaBase != null ? tarifaBase : BigDecimal.ZERO)
                .descuentoConvenio(BigDecimal.ZERO)
                .cuotaModeradora(BigDecimal.ZERO)
                .copago(BigDecimal.ZERO)
                .valorACobrar(BigDecimal.ZERO)
                .tipoCobro("exento")
                .normativaAplicada(motivo)
                .exento(true)
                .motivoExencion(motivo)
                .alertaTopeEvento(false)
                .alertaTopeAnual(false)
                .build();
    }

    private LiquidacionResponse particular(String cupsCode, String serviceType,
                                             BigDecimal tarifaBase) {
        BigDecimal valor = tarifaBase != null ? tarifaBase : BigDecimal.ZERO;
        return LiquidacionResponse.builder()
                .cupsCode(cupsCode)
                .serviceType(serviceType)
                .tarifaBase(valor)
                .descuentoConvenio(BigDecimal.ZERO)
                .cuotaModeradora(BigDecimal.ZERO)
                .copago(BigDecimal.ZERO)
                .valorACobrar(valor)
                .tipoCobro("particular")
                .normativaAplicada("Tarifa propia IPS — paciente particular (RN-10)")
                .exento(false)
                .alertaTopeEvento(false)
                .alertaTopeAnual(false)
                .build();
    }

    private String mapServiceType(String serviceType) {
        if (serviceType == null) return "CONSULTA_MEDICA_GENERAL";
        return switch (serviceType.toUpperCase()) {
            case "CONSULTA"               -> "CONSULTA_MEDICA_GENERAL";
            case "LABORATORIO"            -> "LABORATORIO";
            case "IMAGEN"                 -> "IMAGEN_DIAGNOSTICA";
            case "PROCEDIMIENTO"          -> "PROCEDIMIENTO_AMBULATORIO";
            case "URGENCIAS", "URGENCIA"  -> "URGENCIAS";
            default                       -> "CONSULTA_MEDICA_GENERAL";
        };
    }
}
