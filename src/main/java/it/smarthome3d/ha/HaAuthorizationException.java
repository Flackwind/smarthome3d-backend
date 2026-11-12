package it.smarthome3d.ha;

import it.smarthome3d.api.ApiException;
import org.springframework.http.HttpStatus;

public final class HaAuthorizationException extends ApiException {
    public HaAuthorizationException(){super(HttpStatus.BAD_GATEWAY,"Home Assistant ha revocato o rifiutato il collegamento. Collega nuovamente Home Assistant.");}
}
