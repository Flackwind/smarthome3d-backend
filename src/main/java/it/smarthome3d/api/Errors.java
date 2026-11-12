package it.smarthome3d.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("message", "La risorsa esiste già o è stata modificata. Aggiorna e riprova.")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size() { return ResponseEntity.status(413).body(Map.of("message", "File o archivio troppo grande")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("message", "Richiesta non valida o risorsa temporaneamente non disponibile")); }
}
