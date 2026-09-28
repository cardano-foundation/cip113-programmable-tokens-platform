package org.cardanofoundation.cip113.controller;
import lombok.RequiredArgsConstructor;
import org.cardanofoundation.cip113.service.InitialMintAttestationService;
import org.cardanofoundation.cip113.service.InitialMintAttestationStore;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("${apiPrefix}/rwa-token/create-attested")
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InitialMintAttestationController {
    private final InitialMintAttestationService service;
    @GetMapping("/config") public Map<String, String> config() { return service.config(); }
    @PostMapping("/prepare") public InitialMintAttestationService.View prepare(@RequestBody byte[] body,
            @RequestHeader HttpHeaders headers) throws Exception { return service.prepare(body, headers); }
    @PostMapping("/{id}/anchor") public InitialMintAttestationService.View anchor(@PathVariable String id,
            @RequestBody byte[] body, @RequestHeader HttpHeaders headers) throws Exception { return service.anchor(id, body, headers); }
    @PostMapping("/{id}/finalize") public RwaTokenModuleHandler.ChainBuildResult finalizeCreation(@PathVariable String id,
            @RequestBody byte[] body, @RequestHeader HttpHeaders headers) throws Exception { return service.finalizeCreation(id, body, headers); }
    @PostMapping("/{id}/approve-and-build") public RwaTokenModuleHandler.ChainBuildResult approveAndBuild(@PathVariable String id,
            @RequestBody byte[] body, @RequestHeader HttpHeaders headers) throws Exception { return service.approveAndBuild(id, body, headers); }
    @PostMapping("/{id}/cancel") public InitialMintAttestationService.View cancel(@PathVariable String id,
            @RequestBody byte[] body, @RequestHeader HttpHeaders headers) throws Exception { return service.cancel(id, body, headers); }
    @GetMapping("/{id}") public InitialMintAttestationService.View get(@PathVariable String id,
            @RequestHeader("X-Session-Id") String sessionId) throws Exception { return service.get(id, sessionId); }
    @GetMapping("/{id}/recovery") public InitialMintAttestationStore.Recovery recovery(@PathVariable String id,
            @RequestHeader("X-Session-Id") String sessionId) throws Exception { return service.recovery(id, sessionId); }
    @PostMapping("/{id}/archive-expired") public InitialMintAttestationStore.Recovery archiveExpired(@PathVariable String id,
            @RequestBody byte[] body, @RequestHeader HttpHeaders headers) throws Exception { return service.archiveExpired(id, body, headers); }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(RuntimeException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<?> timeout(TimeoutException ex) { return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(Map.of("error", ex.getMessage())); }
    @ExceptionHandler(InterruptedException.class)
    public ResponseEntity<?> interrupted(InterruptedException ex) {
        Thread.currentThread().interrupt();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Signing wait cancelled; the saved initial mint can be resumed"));
    }
}
