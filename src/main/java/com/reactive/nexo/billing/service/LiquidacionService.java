package com.reactive.nexo.billing.service;

import com.reactive.nexo.billing.dto.LiquidacionResponse;
import com.reactive.nexo.billing.repository.CupsTarifaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Servicio de liquidación automática según normativa colombiana.
 *
 * Normativa aplicada:
 * - Acuerdo 260/2004 CRES: Cuotas moderadoras y copagos
 * - Resolución 3374/2000: RIPS (Registro Individual de Prestación de Servicios)
 * - Ley 1438/2011: Reforma al sistema de salud
 * - Decreto 4747/2007: Relaciones entre prestadores y aseguradoras
 */
@Service
@RequiredArgsConstructor
public class LiquidacionService {

    private final CupsTarifaRepository cupsTarifaRepository;
    private final DatabaseClient databaseClient;

    /**
     * Calcula la cuota moderadora o copago para un servicio dado el régimen y categoría del paciente.
     * Categoría A: IBC < 2 SMLMV, B: 2-5 SMLMV, C: > 5 SMLMV
     */
    public Mono<LiquidacionResponse> liquidar(String cupsCode, String serviceType,
                                               String regimen, String categoria,
                                               BigDecimal tarifaBase) {
        // Mapear serviceType a tipo_servicio de la tabla cuotas_moderadoras
        String tipoServicioNorm = mapServiceType(serviceType);

        // Buscar cuota moderadora vigente
        String sql = "SELECT valor FROM cuotas_moderadoras " +
                     "WHERE regimen = :regimen AND categoria = :categoria " +
                     "AND tipo_servicio = :tipoServicio AND activo = true " +
                     "AND vigencia_desde <= CURRENT_DATE " +
                     "AND (vigencia_hasta IS NULL OR vigencia_hasta >= CURRENT_DATE) " +
                     "ORDER BY vigencia_desde DESC LIMIT 1";

        return databaseClient.sql(sql)
                .bind("regimen", regimen.toUpperCase())
                .bind("categoria", categoria.toUpperCase())
                .bind("tipoServicio", tipoServicioNorm)
                .map((row, meta) -> row.get("valor", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO)
                .map(cuotaValor -> buildLiquidacion(cupsCode, serviceType, regimen, categoria,
                        tarifaBase, cuotaValor));
    }

    private LiquidacionResponse buildLiquidacion(String cupsCode, String serviceType,
                                                   String regimen, String categoria,
                                                   BigDecimal tarifaBase, BigDecimal cuotaValor) {
        // Régimen subsidiado nivel 1 (categoría A) es exento de copago
        boolean exento = "SUBSIDIADO".equalsIgnoreCase(regimen) && "A".equalsIgnoreCase(categoria);
        boolean esParticular = "PARTICULAR".equalsIgnoreCase(regimen) || "ARL".equalsIgnoreCase(regimen);

        BigDecimal valorACobrar;
        BigDecimal descuentoConvenio = BigDecimal.ZERO;
        BigDecimal copago = BigDecimal.ZERO;
        BigDecimal cuotaFinal;
        String motivoExencion = null;
        String normativa = "Acuerdo 260/2004 CRES";

        if (exento) {
            cuotaFinal = BigDecimal.ZERO;
            valorACobrar = BigDecimal.ZERO;
            motivoExencion = "Régimen Subsidiado Nivel 1 - Exento según Acuerdo 260/2004";
        } else if (esParticular) {
            // Particular paga tarifa completa ISS
            cuotaFinal = BigDecimal.ZERO;
            valorACobrar = tarifaBase != null ? tarifaBase : BigDecimal.ZERO;
            normativa = "Tarifa ISS 2001 - Paciente Particular";
        } else {
            cuotaFinal = cuotaValor;
            valorACobrar = cuotaValor;
        }

        return LiquidacionResponse.builder()
                .cupsCode(cupsCode)
                .serviceType(serviceType)
                .tarifaBase(tarifaBase != null ? tarifaBase : BigDecimal.ZERO)
                .descuentoConvenio(descuentoConvenio)
                .cuotaModeradora(cuotaFinal)
                .copago(copago)
                .valorACobrar(valorACobrar)
                .regimen(regimen)
                .categoria(categoria)
                .normativaAplicada(normativa)
                .exento(exento)
                .motivoExencion(motivoExencion)
                .build();
    }

    /**
     * Mapea el tipo de servicio del sistema al tipo de la tabla de cuotas moderadoras.
     */
    private String mapServiceType(String serviceType) {
        if (serviceType == null) return "CONSULTA_MEDICA_GENERAL";
        return switch (serviceType.toUpperCase()) {
            case "CONSULTA" -> "CONSULTA_MEDICA_GENERAL";
            case "LABORATORIO" -> "LABORATORIO";
            case "IMAGEN" -> "IMAGEN_DIAGNOSTICA";
            case "PROCEDIMIENTO" -> "PROCEDIMIENTO_AMBULATORIO";
            case "URGENCIAS" -> "URGENCIAS";
            default -> "CONSULTA_MEDICA_GENERAL";
        };
    }
}
