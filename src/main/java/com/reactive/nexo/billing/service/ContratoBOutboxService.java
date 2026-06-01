package com.reactive.nexo.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.reactive.nexo.billing.dto.ContratoBEvent;
import com.reactive.nexo.billing.entity.ContratoBOutbox;
import com.reactive.nexo.billing.entity.Recaudo;
import com.reactive.nexo.billing.repository.ContratoBOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Servicio del outbox transaccional para el Contrato B.
 *
 * Patrón outbox: el evento se persiste en la misma transacción que el comprobante.
 * Un publicador relé (futuro) lo envía al bus con reintentos y dead-letter.
 * Garantía: nunca hay "comprobante emitido pero evento perdido".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContratoBOutboxService {

    private final ContratoBOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * Construye y persiste el evento Contrato B en el outbox.
     * Debe llamarse dentro de la misma transacción que emite el comprobante.
     */
    public Mono<ContratoBOutbox> publicar(Recaudo recaudo) {
        ContratoBEvent evento = buildEvento(recaudo);

        try {
            String payload = objectMapper.writeValueAsString(evento);

            ContratoBOutbox outbox = ContratoBOutbox.builder()
                    .eventoId(evento.getEventoId())
                    .episodioId(recaudo.getEpisodioId())
                    .recaudoId(recaudo.getId())
                    .payload(payload)
                    .status("PENDIENTE")
                    .intentos(0)
                    .proximoIntento(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            return outboxRepository.save(outbox)
                    .doOnSuccess(o -> log.info("ContratoBOutbox - evento {} persistido para episodio {}",
                            o.getEventoId(), o.getEpisodioId()))
                    .doOnError(e -> log.error("ContratoBOutbox - error al persistir evento: {}", e.getMessage()));

        } catch (Exception e) {
            log.error("ContratoBOutbox - error serializando evento: {}", e.getMessage());
            return Mono.error(new RuntimeException("Error al construir Contrato B: " + e.getMessage()));
        }
    }

    /**
     * Construye el evento Contrato B v2.0 desde el recaudo confirmado.
     * Solo datos financieros — sin CUPS, CIE-10 ni valor_neto_eps.
     */
    private ContratoBEvent buildEvento(Recaudo recaudo) {
        return ContratoBEvent.builder()
                .contratoVersion("2.0")
                .eventoId(recaudo.getEventoId() != null ? recaudo.getEventoId() : UUID.randomUUID())
                .episodioId(recaudo.getEpisodioId())
                .pacienteId(String.valueOf(recaudo.getPatientId()))
                .epsId(recaudo.getEpsId())
                .fechaAtencion(recaudo.getFechaAtencion())
                .tipoServicio(recaudo.getTipoServicio())
                .numeroAutorizacion(recaudo.getNumeroAutorizacion())
                .valorRecaudadoPaciente(recaudo.getValorTotal() != null
                        ? recaudo.getValorTotal().longValue() : 0L)
                .tipoCobro(recaudo.getTipoCobro())
                .comprobanteRecaudoId(recaudo.getNumeroComprobante())
                .comprobanteInmutable(true)
                .corrigeComprobanteId(recaudo.getCorrigeComprobanteId())
                .fechaEmisionEvento(recaudo.getFechaEmisionEvento() != null
                        ? recaudo.getFechaEmisionEvento() : LocalDateTime.now())
                .build();
    }

    /**
     * Marca un evento como enviado exitosamente.
     */
    public Mono<Void> marcarEnviado(Long outboxId) {
        return outboxRepository.findById(outboxId)
                .flatMap(o -> {
                    o.setStatus("ENVIADO");
                    o.setEnviadoAt(LocalDateTime.now());
                    o.setUpdatedAt(LocalDateTime.now());
                    return outboxRepository.save(o);
                })
                .then();
    }

    /**
     * Registra un fallo de envío con backoff exponencial.
     * Tras 5 intentos pasa a DEAD_LETTER con alerta.
     */
    public Mono<Void> registrarFallo(Long outboxId, String error) {
        return outboxRepository.findById(outboxId)
                .flatMap(o -> {
                    int intentos = o.getIntentos() + 1;
                    o.setIntentos(intentos);
                    o.setErrorMensaje(error);
                    o.setUpdatedAt(LocalDateTime.now());

                    if (intentos >= 5) {
                        o.setStatus("DEAD_LETTER");
                        log.error("ContratoBOutbox - evento {} en DEAD_LETTER tras {} intentos. Episodio: {}",
                                o.getEventoId(), intentos, o.getEpisodioId());
                    } else {
                        o.setStatus("FALLIDO");
                        // Backoff exponencial: 1min, 2min, 4min, 8min
                        long minutosEspera = (long) Math.pow(2, intentos - 1);
                        o.setProximoIntento(LocalDateTime.now().plusMinutes(minutosEspera));
                        log.warn("ContratoBOutbox - evento {} fallido (intento {}), próximo en {} min",
                                o.getEventoId(), intentos, minutosEspera);
                    }
                    return outboxRepository.save(o);
                })
                .then();
    }
}
