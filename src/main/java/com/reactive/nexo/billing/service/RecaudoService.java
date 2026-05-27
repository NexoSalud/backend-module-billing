package com.reactive.nexo.billing.service;

import com.reactive.nexo.billing.dto.*;
import com.reactive.nexo.billing.entity.Recaudo;
import com.reactive.nexo.billing.entity.RecaudoItem;
import com.reactive.nexo.billing.repository.MedicalOrderRepository;
import com.reactive.nexo.billing.repository.RecaudoItemRepository;
import com.reactive.nexo.billing.repository.RecaudoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecaudoService {

    private final RecaudoRepository recaudoRepository;
    private final RecaudoItemRepository itemRepository;
    private final MedicalOrderRepository orderRepository;
    private final DatabaseClient databaseClient;

    /**
     * Crea un recaudo en estado BORRADOR con sus ítems.
     * Marca las órdenes médicas como EN_RECAUDO.
     */
    public Mono<RecaudoResponse> create(CreateRecaudoRequest req) {
        return generateNumeroComprobante()
                .flatMap(numero -> {
                    BigDecimal total = req.getItems().stream()
                            .map(i -> i.getValorCobrado() != null ? i.getValorCobrado() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    Recaudo recaudo = Recaudo.builder()
                            .numeroComprobante(numero)
                            .patientId(req.getPatientId())
                            .cajeroId(req.getCajeroId())
                            .sedeId(req.getSedeId())
                            .epsNombre(req.getEpsNombre())
                            .regimen(req.getRegimen())
                            .status("BORRADOR")
                            .medioPago(req.getMedioPago())
                            .valorTotal(total)
                            .valorRecibido(req.getValorRecibido())
                            .cambio(req.getValorRecibido() != null ? req.getValorRecibido().subtract(total) : null)
                            .observaciones(req.getObservaciones())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return recaudoRepository.save(recaudo);
                })
                .flatMap(saved -> saveItems(saved.getId(), req.getItems())
                        .then(markOrdersInRecaudo(req.getItems()))
                        .then(buildResponse(saved)));
    }

    /**
     * Confirma el recaudo: cambia estado a CONFIRMADO y marca órdenes como RECAUDADO.
     */
    public Mono<RecaudoResponse> confirmar(Long id, ConfirmarRecaudoRequest req) {
        return recaudoRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Recaudo no encontrado")))
                .flatMap(recaudo -> {
                    if (!"BORRADOR".equals(recaudo.getStatus())) {
                        return Mono.error(new RuntimeException("Solo se pueden confirmar recaudos en estado BORRADOR"));
                    }
                    recaudo.setStatus("CONFIRMADO");
                    recaudo.setMedioPago(req.getMedioPago());
                    recaudo.setValorRecibido(req.getValorRecibido());
                    recaudo.setCambio(req.getValorRecibido().subtract(recaudo.getValorTotal()));
                    recaudo.setObservaciones(req.getObservaciones());
                    recaudo.setConfirmadoAt(LocalDateTime.now());
                    recaudo.setUpdatedAt(LocalDateTime.now());
                    return recaudoRepository.save(recaudo);
                })
                .flatMap(saved -> markOrdersRecaudado(saved.getId())
                        .then(buildResponse(saved)));
    }

    /**
     * Anula un recaudo confirmado. Revierte las órdenes a PENDIENTE_RECAUDO.
     */
    public Mono<RecaudoResponse> anular(Long id, Long anuladoPor, String motivo) {
        return recaudoRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Recaudo no encontrado")))
                .flatMap(recaudo -> {
                    if ("ANULADO".equals(recaudo.getStatus())) {
                        return Mono.error(new RuntimeException("El recaudo ya está anulado"));
                    }
                    recaudo.setStatus("ANULADO");
                    recaudo.setAnulacionMotivo(motivo);
                    recaudo.setAnuladoPor(anuladoPor);
                    recaudo.setAnuladoAt(LocalDateTime.now());
                    recaudo.setUpdatedAt(LocalDateTime.now());
                    return recaudoRepository.save(recaudo);
                })
                .flatMap(saved -> revertOrdersToPending(saved.getId())
                        .then(buildResponse(saved)));
    }

    public Mono<RecaudoResponse> getById(Long id) {
        return recaudoRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Recaudo no encontrado")))
                .flatMap(this::buildResponse);
    }

    public Mono<RecaudoResponse> getByComprobante(String numero) {
        return recaudoRepository.findByNumeroComprobante(numero)
                .switchIfEmpty(Mono.error(new RuntimeException("Comprobante no encontrado")))
                .flatMap(this::buildResponse);
    }

    /**
     * Lista paginada de recaudos con filtros.
     */
    public Mono<PagedResponse<RecaudoResponse>> findAll(int page, int size,
                                                         String status, Long cajeroId,
                                                         Long patientId, String search) {
        StringBuilder sql = new StringBuilder(
                "SELECT r.* FROM recaudos r WHERE 1=1");
        if (status != null && !status.isBlank()) sql.append(" AND r.status = :status");
        if (cajeroId != null) sql.append(" AND r.cajero_id = :cajeroId");
        if (patientId != null) sql.append(" AND r.patient_id = :patientId");
        if (search != null && !search.isBlank()) sql.append(" AND r.numero_comprobante ILIKE :search");
        sql.append(" ORDER BY r.created_at DESC LIMIT :size OFFSET :offset");

        String countSql = "SELECT COUNT(*) FROM recaudos r WHERE 1=1"
                + (status != null && !status.isBlank() ? " AND r.status = :status" : "")
                + (cajeroId != null ? " AND r.cajero_id = :cajeroId" : "")
                + (patientId != null ? " AND r.patient_id = :patientId" : "")
                + (search != null && !search.isBlank() ? " AND r.numero_comprobante ILIKE :search" : "");

        DatabaseClient.GenericExecuteSpec exec = databaseClient.sql(sql.toString());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql(countSql);

        if (status != null && !status.isBlank()) { exec = exec.bind("status", status); count = count.bind("status", status); }
        if (cajeroId != null) { exec = exec.bind("cajeroId", cajeroId); count = count.bind("cajeroId", cajeroId); }
        if (patientId != null) { exec = exec.bind("patientId", patientId); count = count.bind("patientId", patientId); }
        if (search != null && !search.isBlank()) { exec = exec.bind("search", "%" + search + "%"); count = count.bind("search", "%" + search + "%"); }

        exec = exec.bind("size", size).bind("offset", (long) page * size);

        Flux<RecaudoResponse> content = exec.map((row, meta) -> mapRowToRecaudo(row)).all()
                .flatMap(this::buildResponse);
        Mono<Long> total = count.map((row, meta) -> row.get(0, Long.class)).one().defaultIfEmpty(0L);

        return Mono.zip(content.collectList(), total)
                .map(t -> {
                    int totalPages = (int) Math.ceil((double) t.getT2() / size);
                    return new PagedResponse<>(t.getT1(), page, size, t.getT2(), totalPages, page >= totalPages - 1);
                });
    }

    public Mono<DashboardStatsResponse> getDashboardStats() {
        return Mono.zip(
                recaudoRepository.sumTodayConfirmed().defaultIfEmpty(BigDecimal.ZERO),
                recaudoRepository.countTodayConfirmed().defaultIfEmpty(0L),
                recaudoRepository.countTodayAnulados().defaultIfEmpty(0L)
        ).map(t -> {
            BigDecimal total = t.getT1();
            long txns = t.getT2();
            BigDecimal promedio = txns > 0 ? total.divide(BigDecimal.valueOf(txns), 0, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
            return DashboardStatsResponse.builder()
                    .totalRecaudadoHoy(total)
                    .transaccionesHoy(txns)
                    .promedioPorTransaccion(promedio)
                    .anulacionesHoy(t.getT3())
                    .build();
        });
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private Mono<String> generateNumeroComprobante() {
        return recaudoRepository.nextSequence()
                .map(seq -> {
                    String yyyymm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
                    return String.format("REC-%s-%06d", yyyymm, seq);
                });
    }

    private Mono<Void> saveItems(Long recaudoId, List<RecaudoItemRequest> items) {
        List<RecaudoItem> entities = items.stream().map(i -> RecaudoItem.builder()
                .recaudoId(recaudoId)
                .medicalOrderId(i.getMedicalOrderId())
                .appointmentId(i.getAppointmentId())
                .cupsCode(i.getCupsCode())
                .cupsDescription(i.getCupsDescription())
                .serviceType(i.getServiceType())
                .ambito(i.getAmbito() != null ? i.getAmbito() : "AMBULATORIO")
                .professionalId(i.getProfessionalId())
                .professionalName(i.getProfessionalName())
                .serviceDate(i.getServiceDate())
                .baseTariff(i.getBaseTariff() != null ? i.getBaseTariff() : BigDecimal.ZERO)
                .descuentoConvenio(i.getDescuentoConvenio() != null ? i.getDescuentoConvenio() : BigDecimal.ZERO)
                .cuotaModeradora(i.getCuotaModeradora() != null ? i.getCuotaModeradora() : BigDecimal.ZERO)
                .copago(i.getCopago() != null ? i.getCopago() : BigDecimal.ZERO)
                .valorCobrado(i.getValorCobrado() != null ? i.getValorCobrado() : BigDecimal.ZERO)
                .exento(i.getExento() != null ? i.getExento() : false)
                .createdAt(LocalDateTime.now())
                .build()).collect(Collectors.toList());
        return itemRepository.saveAll(entities).then();
    }

    private Mono<Void> markOrdersInRecaudo(List<RecaudoItemRequest> items) {
        return Flux.fromIterable(items)
                .filter(i -> i.getMedicalOrderId() != null)
                .flatMap(i -> orderRepository.findById(i.getMedicalOrderId())
                        .flatMap(o -> { o.setStatus("EN_RECAUDO"); o.setUpdatedAt(LocalDateTime.now()); return orderRepository.save(o); }))
                .then();
    }

    private Mono<Void> markOrdersRecaudado(Long recaudoId) {
        return itemRepository.findByRecaudoId(recaudoId)
                .filter(i -> i.getMedicalOrderId() != null)
                .flatMap(i -> orderRepository.findById(i.getMedicalOrderId())
                        .flatMap(o -> { o.setStatus("RECAUDADO"); o.setUpdatedAt(LocalDateTime.now()); return orderRepository.save(o); }))
                .then();
    }

    private Mono<Void> revertOrdersToPending(Long recaudoId) {
        return itemRepository.findByRecaudoId(recaudoId)
                .filter(i -> i.getMedicalOrderId() != null)
                .flatMap(i -> orderRepository.findById(i.getMedicalOrderId())
                        .flatMap(o -> { o.setStatus("PENDIENTE_RECAUDO"); o.setUpdatedAt(LocalDateTime.now()); return orderRepository.save(o); }))
                .then();
    }

    private Mono<RecaudoResponse> buildResponse(Recaudo r) {
        return itemRepository.findByRecaudoId(r.getId())
                .map(this::itemToResponse)
                .collectList()
                .map(items -> RecaudoResponse.builder()
                        .id(r.getId())
                        .numeroComprobante(r.getNumeroComprobante())
                        .patientId(r.getPatientId())
                        .cajeroId(r.getCajeroId())
                        .sedeId(r.getSedeId())
                        .epsNombre(r.getEpsNombre())
                        .regimen(r.getRegimen())
                        .status(r.getStatus())
                        .medioPago(r.getMedioPago())
                        .valorTotal(r.getValorTotal())
                        .valorRecibido(r.getValorRecibido())
                        .cambio(r.getCambio())
                        .observaciones(r.getObservaciones())
                        .confirmadoAt(r.getConfirmadoAt())
                        .createdAt(r.getCreatedAt())
                        .items(items)
                        .build());
    }

    private RecaudoItemResponse itemToResponse(RecaudoItem i) {
        return RecaudoItemResponse.builder()
                .id(i.getId())
                .recaudoId(i.getRecaudoId())
                .medicalOrderId(i.getMedicalOrderId())
                .appointmentId(i.getAppointmentId())
                .cupsCode(i.getCupsCode())
                .cupsDescription(i.getCupsDescription())
                .serviceType(i.getServiceType())
                .ambito(i.getAmbito())
                .professionalId(i.getProfessionalId())
                .professionalName(i.getProfessionalName())
                .serviceDate(i.getServiceDate())
                .baseTariff(i.getBaseTariff())
                .descuentoConvenio(i.getDescuentoConvenio())
                .cuotaModeradora(i.getCuotaModeradora())
                .copago(i.getCopago())
                .valorCobrado(i.getValorCobrado())
                .exento(i.getExento())
                .build();
    }

    private Recaudo mapRowToRecaudo(io.r2dbc.spi.Row row) {
        return Recaudo.builder()
                .id(row.get("id", Long.class))
                .numeroComprobante(row.get("numero_comprobante", String.class))
                .patientId(row.get("patient_id", Long.class))
                .cajeroId(row.get("cajero_id", Long.class))
                .sedeId(row.get("sede_id", Long.class))
                .epsNombre(row.get("eps_nombre", String.class))
                .regimen(row.get("regimen", String.class))
                .status(row.get("status", String.class))
                .medioPago(row.get("medio_pago", String.class))
                .valorTotal(row.get("valor_total", BigDecimal.class))
                .valorRecibido(row.get("valor_recibido", BigDecimal.class))
                .cambio(row.get("cambio", BigDecimal.class))
                .observaciones(row.get("observaciones", String.class))
                .confirmadoAt(row.get("confirmado_at", LocalDateTime.class))
                .createdAt(row.get("created_at", LocalDateTime.class))
                .build();
    }
}
