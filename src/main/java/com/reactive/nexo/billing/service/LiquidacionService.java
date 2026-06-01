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
 * Implementa la lógica de cobro v2.1 según:
 * - Acuerdo 260/2004 CRES: categorías A/B/C, cuotas moderadoras, copagos
 * - Circular Externa 048/2025 + Res. 3488/2025: indexación UVB 2026
 * - Decreto 1652/2022: catálogo de exenciones
 * - Ley 1751/2015: prohibición de condicionar atención en urgencias
 * - RN-03: copago solo para beneficiarios; cotizante paga $0 en hospitalización
 * - RN-04: cuota moderadora fija por categoría, sin diferenciación por tipo_cita
 * - RN-05: servicios PyD → $0
 * - RN-06: dos topes simultáneos (por evento y anual)
 * - RN-08: exención corta el flujo antes del tipo de cobro
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiquidacionService {

    private final CupsTarifaRepository cupsTarifaRepository;
    private final DatabaseClient databaseClient;

    /**
     * Paso 3-5 del flujo de cálculo.
     * Evalúa exenciones, determina tipo de cobro y calcula el valor exacto.
     *
     * @param cupsCode      código CUPS del servicio
     * @param serviceType   tipo de servicio (CONSULTA, LABORATORIO, IMAGEN, PROCEDIMIENTO, URGENCIAS)
     * @param regimen       régimen del paciente
     * @param rolAfiliado   COTIZANTE o BENEFICIARIO (RN-03)
     * @param categoria     A, B o C (por IBC)
     * @param tarifaBase    valor del servicio resuelto por motor de tarifas
     * @param esPyd         marcador PyD del Contrato A (RN-05)
     * @param exencionCodigo código de exención si aplica (Paso 3)
     * @param patientId     para consultar acumulado anual (RN-06/07)
     * @param fechaAtencion fecha del evento clínico (RN-01)
     */
    public Mono<LiquidacionResponse> liquidar(
            String cupsCode, String serviceType,
            String regimen, String rolAfiliado, String categoria,
            BigDecimal tarifaBase, boolean esPyd, String exencionCodigo,
            Long patientId, LocalDate fechaAtencion) {

        // ── Paso 3: Exenciones (cortan el flujo) ─────────────────────────────
        if (esPyd) {
            return Mono.just(buildExento(cupsCode, serviceType, tarifaBase,
                    "PYD", "Servicio PyD — exento de cuota moderadora (RN-05)"));
        }
        if (exencionCodigo != null && !exencionCodigo.isBlank()) {
            return Mono.just(buildExento(cupsCode, serviceType, tarifaBase,
                    exencionCodigo, "Exención aplicada: " + exencionCodigo + " (Decreto 1652/2022)"));
        }
        if ("URGENCIA_VITAL".equalsIgnoreCase(exencionCodigo)) {
            return Mono.just(buildExento(cupsCode, serviceType, tarifaBase,
                    "URGENCIA_VITAL", "Urgencia vital — Ley 1751/2015"));
        }

        // ── Paso 4: Determinar tipo de cobro ─────────────────────────────────
        String regimenUp = regimen != null ? regimen.toUpperCase() : "PARTICULAR";
        String rolUp = rolAfiliado != null ? rolAfiliado.toUpperCase() : "COTIZANTE";

        // ARL, SOAT, póliza → $0 al paciente
        if ("ARL".equals(regimenUp) || "SOAT".equals(regimenUp) || "POLIZA".equals(regimenUp)) {
            return Mono.just(buildExento(cupsCode, serviceType, tarifaBase,
                    "TERCERO_PAGADOR", "Tercero pagador (" + regimenUp + ") — $0 al paciente"));
        }

        // Particular → tarifa completa
        if ("PARTICULAR".equals(regimenUp) || "SIN_AFILIACION".equals(regimenUp)) {
            return Mono.just(buildParticular(cupsCode, serviceType, tarifaBase));
        }

        // RN-03: cotizante en hospitalización/cirugía/procedimiento → $0 copago
        boolean esHospitalizacion = "PROCEDIMIENTO".equalsIgnoreCase(serviceType)
                || "HOSPITALIZACION".equalsIgnoreCase(serviceType);
        if ("COTIZANTE".equals(rolUp) && esHospitalizacion) {
            return Mono.just(buildExento(cupsCode, serviceType, tarifaBase,
                    "COTIZANTE_SIN_COPAGO", "Cotizante — no paga copago en hospitalización/procedimiento (RN-03)"));
        }

        // ── Paso 5: Calcular valor exacto ─────────────────────────────────────
        String tipoServicioNorm = mapServiceType(serviceType);

        // 5a. Cuota moderadora (consulta externa, ambulatorio)
        boolean esCuotaModeradora = "CONTRIBUTIVO".equals(regimenUp)
                && !esHospitalizacion;

        if (esCuotaModeradora) {
            return calcularCuotaModeradora(cupsCode, serviceType, tipoServicioNorm,
                    regimenUp, categoria, tarifaBase, fechaAtencion);
        }

        // 5b. Copago (hospitalización, procedimientos — beneficiarios / subsidiado)
        if (tarifaBase != null && tarifaBase.compareTo(BigDecimal.ZERO) > 0) {
            return calcularCopago(cupsCode, serviceType, regimenUp, categoria,
                    tarifaBase, patientId, fechaAtencion);
        }

        // Fallback: cuota moderadora sin tarifa base
        return calcularCuotaModeradora(cupsCode, serviceType, tipoServicioNorm,
                regimenUp, categoria, tarifaBase, fechaAtencion);
    }

    // ── Sobrecarga simplificada (compatibilidad con código existente) ─────────
    public Mono<LiquidacionResponse> liquidar(String cupsCode, String serviceType,
                                               String regimen, String categoria,
                                               BigDecimal tarifaBase) {
        return liquidar(cupsCode, serviceType, regimen, "COTIZANTE", categoria,
                tarifaBase, false, null, null, LocalDate.now());
    }

    // ─── Cálculo cuota moderadora ─────────────────────────────────────────────

    private Mono<LiquidacionResponse> calcularCuotaModeradora(
            String cupsCode, String serviceType, String tipoServicioNorm,
            String regimen, String categoria, BigDecimal tarifaBase, LocalDate fechaAtencion) {

        String sql = "SELECT valor_pesos FROM cuotas_moderadoras " +
                "WHERE regimen = :regimen AND categoria = :categoria " +
                "AND tipo_servicio = :tipoServicio AND activo = true " +
                "AND vigencia_desde <= :fecha " +
                "AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha) " +
                "ORDER BY vigencia_desde DESC LIMIT 1";

        LocalDate fecha = fechaAtencion != null ? fechaAtencion : LocalDate.now();

        return databaseClient.sql(sql)
                .bind("regimen", regimen)
                .bind("categoria", categoria != null ? categoria.toUpperCase() : "B")
                .bind("tipoServicio", tipoServicioNorm)
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

    // ─── Cálculo copago con dos topes (RN-06) ────────────────────────────────

    private Mono<LiquidacionResponse> calcularCopago(
            String cupsCode, String serviceType, String regimen, String categoria,
            BigDecimal tarifaBase, Long patientId, LocalDate fechaAtencion) {

        String catUp = categoria != null ? categoria.toUpperCase() : "B";
        LocalDate fecha = fechaAtencion != null ? fechaAtencion : LocalDate.now();
        int anio = fecha.getYear();

        // Porcentaje de copago según régimen y categoría
        BigDecimal porcentaje = getPorcentajeCopago(regimen, catUp);

        // Copago bruto
        BigDecimal copagoBruto = tarifaBase.multiply(porcentaje)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);

        // Tope por evento
        return getTope(catUp, "POR_EVENTO", fecha)
                .flatMap(topeEvento -> {
                    BigDecimal copagoPorEvento = copagoBruto.min(topeEvento);
                    boolean alertaTopeEvento = copagoBruto.compareTo(topeEvento) > 0;

                    // Tope anual
                    return getTope(catUp, "ANUAL", fecha)
                            .flatMap(topeAnual -> getAcumuladoAnual(patientId, anio)
                                    .map(acumulado -> {
                                        BigDecimal disponibleAnual = topeAnual.subtract(acumulado).max(BigDecimal.ZERO);
                                        BigDecimal copagoFinal = copagoPorEvento.min(disponibleAnual);
                                        boolean alertaTopeAnual = copagoPorEvento.compareTo(disponibleAnual) > 0;

                                        return LiquidacionResponse.builder()
                                                .cupsCode(cupsCode)
                                                .serviceType(serviceType)
                                                .tarifaBase(tarifaBase)
                                                .descuentoConvenio(BigDecimal.ZERO)
                                                .cuotaModeradora(BigDecimal.ZERO)
                                                .copago(copagoFinal)
                                                .valorACobrar(copagoFinal)
                                                .regimen(regimen)
                                                .categoria(catUp)
                                                .tipoCobro("copago")
                                                .normativaAplicada("Acuerdo 260/2004 CRES — dos topes (RN-06)")
                                                .exento(false)
                                                .alertaTopeEvento(alertaTopeEvento)
                                                .alertaTopeAnual(alertaTopeAnual)
                                                .build();
                                    }));
                });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Mono<BigDecimal> getTope(String categoria, String tipoTope, LocalDate fecha) {
        String sql = "SELECT valor_pesos FROM topes_copago " +
                "WHERE categoria = :categoria AND tipo_tope = :tipoTope AND activo = true " +
                "AND vigencia_desde <= :fecha " +
                "AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha) " +
                "ORDER BY vigencia_desde DESC LIMIT 1";
        return databaseClient.sql(sql)
                .bind("categoria", categoria)
                .bind("tipoTope", tipoTope)
                .bind("fecha", fecha)
                .map((row, meta) -> row.get("valor_pesos", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.valueOf(Long.MAX_VALUE)); // sin tope si no hay dato
    }

    private Mono<BigDecimal> getAcumuladoAnual(Long patientId, int anio) {
        if (patientId == null) return Mono.just(BigDecimal.ZERO);
        String sql = "SELECT COALESCE(total_copago, 0) FROM acumulado_copago_anual " +
                "WHERE patient_id = :patientId AND anio = :anio";
        return databaseClient.sql(sql)
                .bind("patientId", patientId)
                .bind("anio", anio)
                .map((row, meta) -> row.get(0, BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    private BigDecimal getPorcentajeCopago(String regimen, String categoria) {
        // Porcentajes Acuerdo 260/2004 — beneficiarios contributivo
        if ("CONTRIBUTIVO".equalsIgnoreCase(regimen)) {
            return switch (categoria) {
                case "A" -> BigDecimal.valueOf(11.5);
                case "B" -> BigDecimal.valueOf(17.3);
                case "C" -> BigDecimal.valueOf(23.0);
                default  -> BigDecimal.valueOf(17.3);
            };
        }
        // Subsidiado
        return switch (categoria) {
            case "A" -> BigDecimal.ZERO;
            case "B" -> BigDecimal.valueOf(5.0);
            case "C" -> BigDecimal.valueOf(10.0);
            default  -> BigDecimal.valueOf(5.0);
        };
    }

    private LiquidacionResponse buildExento(String cupsCode, String serviceType,
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

    private LiquidacionResponse buildParticular(String cupsCode, String serviceType,
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
            case "CONSULTA"      -> "CONSULTA_MEDICA_GENERAL";
            case "LABORATORIO"   -> "LABORATORIO";
            case "IMAGEN"        -> "IMAGEN_DIAGNOSTICA";
            case "PROCEDIMIENTO" -> "PROCEDIMIENTO_AMBULATORIO";
            case "URGENCIAS", "URGENCIA" -> "URGENCIAS";
            default              -> "CONSULTA_MEDICA_GENERAL";
        };
    }
}
