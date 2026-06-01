package com.reactive.nexo.billing.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecaudoResponse {
    private Long id;
    private String numeroComprobante;
    // Contrato B
    private UUID eventoId;
    private String episodioId;
    private String corrigeComprobanteId;
    private String contratoVersion;
    // Paciente
    private Long patientId;
    private String patientName;
    private String patientIdentification;
    private Long cajeroId;
    private String cajeroName;
    private Long sedeId;
    private String epsId;
    private String epsNombre;
    private String regimen;
    private String rolAfiliado;
    private String categoriaIbc;
    // Servicio
    private String tipoServicio;
    private String numeroAutorizacion;
    private Boolean esPyd;
    private String exencionCodigo;
    // Cobro
    private String tipoCobro;
    private String status;
    private String medioPago;
    private BigDecimal valorTotal;
    private BigDecimal valorRecibido;
    private BigDecimal cambio;
    private Boolean comprobanteInmutable;
    private String observaciones;
    private LocalDate fechaAtencion;
    private LocalDateTime confirmadoAt;
    private LocalDateTime createdAt;
    private List<RecaudoItemResponse> items;
    // Alertas para el cajero
    private Boolean alertaTopeEvento;
    private Boolean alertaTopeAnual;
    private Boolean alertaDeudaAnterior;
}
