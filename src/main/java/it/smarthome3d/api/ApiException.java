package it.smarthome3d.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    public static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "Risorsa non trovata"); }
    public static ApiException conflict() { return new ApiException(HttpStatus.PRECONDITION_FAILED, "Il progetto è stato modificato da un altro dispositivo. Riaprilo prima di salvare."); }
}
