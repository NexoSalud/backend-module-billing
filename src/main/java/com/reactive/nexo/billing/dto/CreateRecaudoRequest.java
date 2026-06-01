package com.reactive.nexo.billing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class CreateRecaudoRequest {

    @NotNull
    private Long patientId;

    @NotNull
    private Long cajeroId;

    private Long sedeId;

    // Contrato B v2.0
    private String episodioId;           // del Contrato A
    private String corrigeComprobanteId; // corrección de recaudo previo

    // Afiliación
    private String epsId;
    private String epsNombre;
    private String regimen;
    private String rolAfiliado;          // COTIZANTE, BENEFICIARIO
    private String categoriaIbc;         // A, B, C

    // Servicio
    private String tipoServicio;         // consulta_general, urgencia, hospitalizacion, procedimiento
    private String numeroAutorizacion;   // transportado de Admisión
    private Boolean esPyd;               // Protección Específica y Detección Temprana
    private String exencionCodigo;       // código de exención si aplica

    // Cobro
    private String tipoCobro;            // cuota_moderadora, copago, particular, exento
    private String medioPago;
    private BigDecimal valorRecibido;
    private String observaciones;
    private LocalDate fechaAtencion;     // RN-01: fecha del evento clínico

    @NotNull
    private List<RecaudoItemRequest> items;
}
