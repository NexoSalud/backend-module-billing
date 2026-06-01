package com.reactive.nexo.billing.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("recaudos")
public class Recaudo {

    @Id
    private Long id;

    @Column("numero_comprobante")
    private String numeroComprobante;

    // Contrato B v2.0
    @Column("evento_id")
    private UUID eventoId;

    @Column("episodio_id")
    private String episodioId;

    @Column("corrige_comprobante_id")
    private String corrigeComprobanteId;

    @Column("contrato_version")
    private String contratoVersion;

    // Paciente y afiliación
    @Column("patient_id")
    private Long patientId;

    @Column("cajero_id")
    private Long cajeroId;

    @Column("sede_id")
    private Long sedeId;

    @Column("eps_id")
    private String epsId;

    @Column("eps_nombre")
    private String epsNombre;

    @Column("regimen")
    private String regimen;

    @Column("rol_afiliado")
    private String rolAfiliado;  // COTIZANTE, BENEFICIARIO

    @Column("categoria_ibc")
    private String categoriaIbc;  // A, B, C

    // Servicio
    @Column("tipo_servicio")
    private String tipoServicio;

    @Column("numero_autorizacion")
    private String numeroAutorizacion;

    @Column("es_pyd")
    private Boolean esPyd;

    @Column("exencion_codigo")
    private String exencionCodigo;

    // Cobro
    @Column("tipo_cobro")
    private String tipoCobro;  // cuota_moderadora, copago, particular, exento

    @Column("status")
    private String status;

    @Column("medio_pago")
    private String medioPago;

    @Column("valor_total")
    private BigDecimal valorTotal;

    @Column("valor_recibido")
    private BigDecimal valorRecibido;

    @Column("cambio")
    private BigDecimal cambio;

    @Column("comprobante_inmutable")
    private Boolean comprobanteInmutable;

    // Auditoría
    @Column("observaciones")
    private String observaciones;

    @Column("modificado_por")
    private Long modificadoPor;

    @Column("modificacion_motivo")
    private String modificacionMotivo;

    @Column("valor_original")
    private BigDecimal valorOriginal;

    @Column("anulacion_motivo")
    private String anulacionMotivo;

    @Column("anulado_por")
    private Long anuladoPor;

    @Column("anulado_at")
    private LocalDateTime anuladoAt;

    @Column("confirmado_at")
    private LocalDateTime confirmadoAt;

    @Column("fecha_atencion")
    private LocalDate fechaAtencion;

    @Column("fecha_emision_evento")
    private LocalDateTime fechaEmisionEvento;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
