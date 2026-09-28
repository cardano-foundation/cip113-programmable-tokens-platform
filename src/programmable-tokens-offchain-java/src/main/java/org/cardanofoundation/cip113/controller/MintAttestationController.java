package org.cardanofoundation.cip113.controller;

import org.cardanofoundation.cip113.service.MintAttestationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("${apiPrefix}/keri/mint-attestations")
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MintAttestationController {
    private final MintAttestationService service;
    @GetMapping("/config") public Map<String, String> config() { return service.config(); }
    @PostMapping("/prepare")
    public MintAttestationService.View prepare(@RequestBody byte[] body, @RequestHeader HttpHeaders headers) throws Exception {
        return service.prepare(body, headers);
    }
    @PostMapping("/{id}/anchor")
    public MintAttestationService.View anchor(@PathVariable String id, @RequestBody byte[] body,
                                              @RequestHeader HttpHeaders headers) throws Exception {
        return service.anchor(id, body, headers);
    }
    @PostMapping("/{id}/build-chain")
    public MintAttestationService.Chain buildChain(@PathVariable String id, @RequestBody byte[] body,
                                                    @RequestHeader HttpHeaders headers) throws Exception {
        return service.buildChain(id, body, headers);
    }
    @GetMapping("/{id}")
    public MintAttestationService.View status(@PathVariable String id, @RequestHeader("X-Session-Id") String sessionId) throws Exception {
        return service.get(id, sessionId);
    }
    @GetMapping("/documents/{digest}")
    public ResponseEntity<byte[]> document(@PathVariable String digest) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"mint-intent-" + digest + ".json\"")
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .body(service.documentByDigest(digest));
    }
    @GetMapping("/documents/{digest}/preimage")
    public ResponseEntity<byte[]> preimage(@PathVariable String digest) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .body(service.preimageByDigest(digest));
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(RuntimeException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<?> timeout(TimeoutException ex) { return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(Map.of("error", ex.getMessage())); }
    @ExceptionHandler(InterruptedException.class)
    public ResponseEntity<?> interrupted(InterruptedException ex) {
        Thread.currentThread().interrupt();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Mint signing wait cancelled; the saved request can be retried"));
    }
}
