package com.reactive.nexo.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Contrato B v2.0 — Evento financiero que Recaudo publica hacia Facturación.
 *
 * Regla de oro: solo datos financieros. Sin CUPS, CIE-10 ni valor_neto_eps.
 * Ref: NexoSalud_Contrato_B_Recaudo_Facturacion_v1_1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContratoBEvent {

    @JsonProperty("contrato_version")
    private String contratoVersion;          // "2.0"

    @JsonProperty("evento_id")
    private UUID eventoId;                   // UUID único del mensaje (idempotencia)

    @JsonProperty("episodio_id")
    private String episodioId;               // del Contrato A (Admisión)

    @JsonProperty("paciente_id")
    private String pacienteId;

    @JsonProperty("eps_id")
    private String epsId;                    // null si particular

    @JsonProperty("fecha_atencion")
    private LocalDate fechaAtencion;         // RN-01: determina tarifa y afiliación vigentes

    @JsonProperty("tipo_servicio")
    private String tipoServicio;             // consulta_general | urgencia | hospitalizacion | procedimiento

    @JsonProperty("numero_autorizacion")
    private String numeroAutorizacion;       // transportado de Admisión, no validado aquí

    @JsonProperty("valor_recaudado_paciente")
    private Long valorRecaudadoPaciente;     // entero, sin decimales. 0 para exentos

    @JsonProperty("tipo_cobro")
    private String tipoCobro;                // cuota_moderadora | copago | particular | exento

    @JsonProperty("comprobante_recaudo_id")
    private String comprobanteRecaudoId;

    @JsonProperty("comprobante_inmutable")
    private Boolean comprobanteInmutable;    // siempre true

    @JsonProperty("corrige_comprobante_id")
    private String corrigeComprobanteId;     // null en caso normal; apunta al original en corrección

    @JsonProperty("fecha_emision_evento")
    private LocalDateTime fechaEmisionEvento;
}
