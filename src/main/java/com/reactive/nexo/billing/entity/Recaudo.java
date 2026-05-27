package com.reactive.nexo.billing.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    @Column("patient_id")
    private Long patientId;

    @Column("cajero_id")
    private Long cajeroId;

    @Column("sede_id")
    private Long sedeId;

    @Column("eps_nombre")
    private String epsNombre;

    @Column("regimen")
    private String regimen;

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

    @Column("observaciones")
    private String observaciones;

    @Column("anulacion_motivo")
    private String anulacionMotivo;

    @Column("anulado_por")
    private Long anuladoPor;

    @Column("anulado_at")
    private LocalDateTime anuladoAt;

    @Column("confirmado_at")
    private LocalDateTime confirmadoAt;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
