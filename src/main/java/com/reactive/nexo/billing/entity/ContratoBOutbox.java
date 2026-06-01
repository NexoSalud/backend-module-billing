package com.reactive.nexo.billing.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox transaccional para el Contrato B (Recaudo → Facturación).
 * Garantía: nunca hay "comprobante emitido pero evento perdido".
 * El evento se escribe en la misma transacción que el comprobante.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("contrato_b_outbox")
public class ContratoBOutbox {

    @Id
    private Long id;

    @Column("evento_id")
    private UUID eventoId;

    @Column("episodio_id")
    private String episodioId;

    @Column("recaudo_id")
    private Long recaudoId;

    @Column("payload")
    private String payload;  // JSON del Contrato B

    @Column("status")
    private String status;  // PENDIENTE, ENVIADO, FALLIDO, DEAD_LETTER

    @Column("intentos")
    private Integer intentos;

    @Column("proximo_intento")
    private LocalDateTime proximoIntento;

    @Column("enviado_at")
    private LocalDateTime enviadoAt;

    @Column("error_mensaje")
    private String errorMensaje;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
