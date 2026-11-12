package it.smarthome3d.auth;

import it.smarthome3d.api.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

@Service
public class OwnerService {
    private final JdbcTemplate db; private final PasswordEncoder encoder;
    private final Map<String, Attempts> attempts = new LinkedHashMap<>();
    private record Attempts(int count, Instant until) {}
    public OwnerService(JdbcTemplate db, PasswordEncoder encoder) { this.db=db; this.encoder=encoder; }
    public boolean setupRequired() { return db.queryForObject("SELECT COUNT(*) FROM owner_account", Integer.class)==0; }
    @Transactional public void setup(String username, String password) {
        if(!setupRequired()) throw new ApiException(HttpStatus.CONFLICT,"Il proprietario è già configurato");
        if(username==null||!username.matches("[\\p{L}\\p{N}_.-]{1,80}")||password==null||password.length()<12||password.length()>512) throw ApiException.invalid("Nome utente non valido o password inferiore a 12 caratteri");
        db.update("INSERT INTO owner_account (id,username,password_hash) VALUES (1,?,?)",username,encoder.encode(password));
    }
    public synchronized String login(String username,String password,String address) {
        var now=Instant.now(); attempts.entrySet().removeIf(e -> e.getValue().until.isBefore(now));
        var previous=attempts.get(address); if(previous!=null&&previous.count>=10) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"Troppi tentativi. Riprova tra 15 minuti.");
        if(attempts.size()>4096) attempts.remove(attempts.keySet().iterator().next());
        var owners=db.queryForList("SELECT username,password_hash FROM owner_account WHERE id=1");
        if(password!=null&&password.length()<=512&&!owners.isEmpty()&&Objects.equals(owners.getFirst().get("username"),username)&&encoder.matches(password,(String)owners.getFirst().get("password_hash"))) { attempts.remove(address); return username; }
        attempts.put(address,new Attempts(previous==null?1:previous.count+1,now.plusSeconds(900)));
        throw new ApiException(HttpStatus.UNAUTHORIZED,"Nome utente o password non validi");
    }
}
