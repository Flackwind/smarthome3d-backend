package it.smarthome3d.ha;

import com.fasterxml.jackson.databind.JsonNode;
import it.smarthome3d.api.ApiException;
import it.smarthome3d.auth.CredentialKey;
import it.smarthome3d.storage.ProjectValidator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Encrypted owner grant on disk; only the short-lived access token is cached in RAM. */
@Service
public class HaProfileConnection {
    private final JdbcTemplate db;private final HaClient ha;private final HaConnections connections;
    private String access,cachedEnvelope;private String issue="";private long expires;
    private record Saved(String url,String clientId,String encrypted) {
        String context(){return "sh3d-owner-1-ha-v1\0"+url+"\0"+clientId;}
    }
    public record Status(boolean connected,String message) {}
    public HaProfileConnection(JdbcTemplate db,HaClient ha,HaConnections connections){this.db=db;this.ha=ha;this.connections=connections;}
    private Saved saved(){return db.query("SELECT instance_url,client_id,encrypted_refresh FROM owner_ha_connection WHERE owner_id=1",(rs,n)->new Saved(rs.getString(1),rs.getString(2),rs.getString(3))).stream().findFirst().orElse(null);}
    public synchronized Status status(CredentialKey key){
        var stored=saved();if(stored==null||key==null||!key.active())return new Status(false,issue);
        if(!stored.url.equals(ha.url()))return new Status(false,"Il collegamento salvato appartiene a un'altra istanza Home Assistant. Verifica l'URL del server e collega nuovamente.");
        try{key.decrypt(stored.encrypted,stored.context());return new Status(true,"");}catch(ApiException e){return new Status(false,e.getMessage());}
    }
    public synchronized void link(CredentialKey key,String clientId,JsonNode grant){
        ProjectValidator.require(clientId!=null&&!clientId.isBlank()&&clientId.length()<=2048&&ha.url().length()<=2048,"Collegamento Home Assistant non valido");
        String refresh=refresh(grant,null);validate(grant);var old=saved();String previous=null;
        if(old!=null&&old.url.equals(ha.url()))try{previous=key.decrypt(old.encrypted,old.context());}catch(ApiException ignored){/* A new explicit authorization can replace an unreadable old grant. */}
        var stored=new Saved(ha.url(),clientId,"");String encrypted=key.encrypt(refresh,stored.context());
        if(db.update("UPDATE owner_ha_connection SET instance_url=?,client_id=?,encrypted_refresh=? WHERE owner_id=1",ha.url(),clientId,encrypted)==0)db.update("INSERT INTO owner_ha_connection(owner_id,instance_url,client_id,encrypted_refresh) VALUES (1,?,?,?)",ha.url(),clientId,encrypted);
        access=grant.path("access_token").asText();expires=System.currentTimeMillis()+grant.path("expires_in").asLong()*1000;cachedEnvelope=encrypted;issue="";
        connections.closeAll();if(previous!=null&&!previous.equals(refresh))ha.revoke(previous);
    }
    public synchronized String access(CredentialKey key){
        if(key==null||!key.active())throw new ApiException(HttpStatus.UNAUTHORIZED,"Accedi nuovamente al server");
        var stored=saved();if(stored==null||!stored.url.equals(ha.url()))throw ApiException.invalid("Collega Home Assistant al profilo server");
        String refresh=key.decrypt(stored.encrypted,stored.context());
        if(access!=null&&stored.encrypted.equals(cachedEnvelope)&&expires-System.currentTimeMillis()>60000)return access;
        JsonNode grant;
        try{grant=ha.refresh(stored.clientId,refresh);}catch(HaAuthorizationException e){db.update("DELETE FROM owner_ha_connection WHERE owner_id=1");clearAccess();issue=e.getMessage();connections.closeAll();throw e;}
        validate(grant);String rotated=refresh(grant,refresh),encrypted=stored.encrypted;
        if(!rotated.equals(refresh)){encrypted=key.encrypt(rotated,stored.context());db.update("UPDATE owner_ha_connection SET encrypted_refresh=? WHERE owner_id=1",encrypted);}
        access=grant.path("access_token").asText();expires=System.currentTimeMillis()+grant.path("expires_in").asLong()*1000;cachedEnvelope=encrypted;return access;
    }
    public synchronized void disconnect(CredentialKey key){
        var stored=saved();String refresh=null;
        if(stored!=null&&stored.url.equals(ha.url())&&key!=null)try{refresh=key.decrypt(stored.encrypted,stored.context());}catch(ApiException ignored){/* A damaged credential can always be removed. */}
        db.update("DELETE FROM owner_ha_connection WHERE owner_id=1");clearAccess();issue="";connections.closeAll();if(refresh!=null)ha.revoke(refresh);
    }
    public synchronized void clearAccess(){access=null;cachedEnvelope=null;expires=0;}
    private static void validate(JsonNode grant){ProjectValidator.require(grant!=null&&grant.path("token_type").asText().equals("Bearer")&&grant.path("access_token").isTextual()&&!grant.path("access_token").asText().isBlank()&&grant.path("access_token").asText().length()<=16384&&grant.path("expires_in").isIntegralNumber()&&grant.path("expires_in").asLong()>=1&&grant.path("expires_in").asLong()<=86400,"Risposta Home Assistant non valida");}
    private static String refresh(JsonNode grant,String previous){String token=grant==null?"":grant.path("refresh_token").asText(previous==null?"":previous);ProjectValidator.require(!token.isBlank()&&token.length()<=8192,"Risposta Home Assistant non valida");return token;}
}
