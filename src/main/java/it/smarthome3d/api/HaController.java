package it.smarthome3d.api;

import it.smarthome3d.ha.*;
import it.smarthome3d.auth.CredentialKey;
import it.smarthome3d.storage.ProjectValidator;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ha")
public class HaController {
    private final HaClient ha;private final HaProfileConnection connection;private final List<String> origins;
    private record Pending(String state,String verifier,String clientId,long created) { @Override public String toString(){return "Pending authorization";} }
    public record Begin(String origin) {} public record Complete(String code,String state) {}
    public HaController(HaClient ha,HaProfileConnection connection,@Value("${sh3d.origins}")List<String> origins){this.ha=ha;this.connection=connection;this.origins=origins;}
    @PostMapping("/authorize") public Map<String,String> begin(@RequestBody Begin body,HttpSession session)throws Exception{
        key(session);
        ProjectValidator.require(origins.contains(body.origin())&&java.net.URI.create(body.origin()).getRawPath().isEmpty(),"Origine non consentita");ha.endpoint("/auth/authorize");
        var random=new SecureRandom();byte[] nonce=new byte[32],verifier=new byte[32];random.nextBytes(nonce);random.nextBytes(verifier);var encoder=Base64.getUrlEncoder().withoutPadding();
        String state=encoder.encodeToString(nonce),secret=encoder.encodeToString(verifier),clientId=body.origin()+"/";
        String challenge=encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII)));
        session.setAttribute(HaClient.PENDING,new Pending(state,secret,clientId,System.currentTimeMillis()));
        return Map.of("state",state,"authorizeUrl",ha.endpoint("/auth/authorize")+"?"+HaClient.form(Map.of("client_id",clientId,"redirect_uri",body.origin()+"/auth/server-callback","state",state,"code_challenge",challenge,"code_challenge_method","S256")));
    }
    @PostMapping("/authorize/complete") public void complete(@RequestBody Complete body,HttpSession session){
        Pending pending; synchronized(session){pending=(Pending)session.getAttribute(HaClient.PENDING);session.removeAttribute(HaClient.PENDING);}
        ProjectValidator.require(pending!=null&&body.state()!=null&&body.state().length()<200&&MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.UTF_8),body.state().getBytes(StandardCharsets.UTF_8))&&System.currentTimeMillis()-pending.created()<300000&&body.code()!=null&&!body.code().isBlank()&&body.code().length()<4096,"Autorizzazione scaduta o non valida");
        var key=key(session);var grant=ha.exchange(pending.clientId(),body.code(),pending.verifier());
        try{connection.link(key,pending.clientId(),grant);}catch(RuntimeException error){String refresh=grant.path("refresh_token").asText();if(!refresh.isBlank())ha.revoke(refresh);throw error;}
    }
    @PostMapping("/authorize/cancel") public void cancel(@RequestBody Complete body,HttpSession session){synchronized(session){if(session.getAttribute(HaClient.PENDING) instanceof Pending pending&&Objects.equals(pending.state(),body.state()))session.removeAttribute(HaClient.PENDING);}}
    @PostMapping("/disconnect") public void disconnect(HttpSession session){session.removeAttribute(HaClient.PENDING);connection.disconnect(key(session));}
    private static CredentialKey key(HttpSession session){if(session.getAttribute(CredentialKey.SESSION) instanceof CredentialKey key&&key.active())return key;throw new ApiException(HttpStatus.UNAUTHORIZED,"Accedi nuovamente al server per collegare Home Assistant");}
}
