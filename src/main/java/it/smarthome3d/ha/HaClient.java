package it.smarthome3d.ha;

import com.fasterxml.jackson.databind.*;
import it.smarthome3d.api.ApiException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

@Component
public class HaClient {
    public static final String PENDING="sh3d.ha.pending";
    private final URI base;private final ObjectMapper json;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public HaClient(@Value("${sh3d.ha-url:}")String url,ObjectMapper json){
        this.json=json;
        if(url.isBlank()){base=null;return;}
        try{var candidate=URI.create(url.replaceAll("/$",""));if(!Set.of("http","https").contains(candidate.getScheme())||candidate.getHost()==null||candidate.getUserInfo()!=null||candidate.getQuery()!=null||candidate.getFragment()!=null)throw new IllegalArgumentException();base=candidate;}
        catch(IllegalArgumentException e){throw new IllegalStateException("SH3D_HA_URL deve contenere l’URL HTTP(S) dell’istanza, senza credenziali");}
    }
    public String url(){return base==null?"":base.toString();}
    public URI endpoint(String path){if(base==null)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Configura SH3D_HA_URL nel backend per collegare Home Assistant");return URI.create(base+path);}
    public JsonNode exchange(String clientId,String code,String verifier){return post(Map.of("grant_type","authorization_code","client_id",clientId,"code",code,"code_verifier",verifier));}
    public JsonNode refresh(String clientId,String token){return post(Map.of("grant_type","refresh_token","client_id",clientId,"refresh_token",token));}
    /** Read-only snapshot for energy sampling; the response is never persisted wholesale. */
    public JsonNode states(String access){
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{var request=HttpRequest.newBuilder(endpoint("/api/states")).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+access).GET().build();
            pending=http.sendAsync(request,info->new LimitedBody(20_000_000));var response=pending.get(20,TimeUnit.SECONDS);
            if(response.statusCode()!=200)throw new IllegalStateException();var value=json.readTree(response.body());if(value==null||!value.isArray())throw new IllegalStateException();return value;
        }catch(Exception e){if(pending!=null)pending.cancel(true);if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ApiException(HttpStatus.BAD_GATEWAY,"Rilevazione HA non disponibile. Verifica connessione e autorizzazione.");}
    }
    /** Bound memory while streaming; the future deadline also covers a stalled response body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            try{for(var buffer:buffers){int count=buffer.remaining();if(count>limit-bytes.size())throw new IllegalStateException();byte[] chunk=new byte[count];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
            catch(Exception e){subscription.cancel();result.completeExceptionally(new IllegalStateException("HA response limit"));}
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private JsonNode post(Map<String,String> data){
        try{var request=HttpRequest.newBuilder(endpoint("/auth/token")).timeout(Duration.ofSeconds(12)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(data))).build();var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(data.get("grant_type").equals("refresh_token")&&Set.of(400,401,403).contains(response.statusCode()))throw new HaAuthorizationException();
            if(response.statusCode()!=200||response.body().length()>1_000_000)throw new IllegalStateException();return json.readTree(response.body());}
        catch(HaAuthorizationException e){throw e;}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ApiException(HttpStatus.BAD_GATEWAY,"Home Assistant non ha autorizzato la sessione. Verifica connessione e accesso.");}
    }
    public void revoke(String refresh){try{http.send(HttpRequest.newBuilder(endpoint("/auth/revoke")).timeout(Duration.ofSeconds(5)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token",refresh)))).build(),HttpResponse.BodyHandlers.discarding());}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();}}
    public static String form(Map<String,String> fields){return fields.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
}
