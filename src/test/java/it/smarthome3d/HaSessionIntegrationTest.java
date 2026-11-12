package it.smarthome3d;

import com.fasterxml.jackson.databind.*;
import it.smarthome3d.ha.HaClient;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:ha;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sh3d.origins=http://localhost:4300"})
@Import(HaSessionIntegrationTest.Fixture.class)
class HaSessionIntegrationTest {
    @LocalServerPort int port;@Autowired ObjectMapper json;@Autowired FakeHa fixture;
    final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
    String csrf;
    URI uri(String path){return URI.create("http://localhost:"+port+path);}
    JsonNode getSession()throws Exception{var response=http.send(HttpRequest.newBuilder(uri("/api/v1/session")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);var data=json.readTree(response.body());csrf=data.path("csrfToken").asText();return data;}
    HttpResponse<String> post(String path,Object body)throws Exception{return http.send(HttpRequest.newBuilder(uri("/api/v1"+path)).header("X-SH3D-CSRF",csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    @Test void preservesOwnerAuthorizationOnLogoutAndOnlyRevokesOnExplicitHaDisconnect()throws Exception{
        assertThat(getSession().path("setupRequired").asBoolean()).isTrue();assertThat(post("/auth/setup",Map.of("username","test-owner","password","test-only-password-42!")).statusCode()).isEqualTo(200);getSession();
        var begin=post("/ha/authorize",Map.of("origin","http://localhost:4300"));assertThat(begin.statusCode()).isEqualTo(200);var authorization=json.readTree(begin.body());
        String state=authorization.path("state").asText();fixture.challenge=form(URI.create(authorization.path("authorizeUrl").asText()).getRawQuery()).get("code_challenge");
        assertThat(post("/ha/authorize/complete",Map.of("code","test-code","state","wrong-state")).statusCode()).isEqualTo(400);
        assertThat(post("/ha/authorize/complete",Map.of("code","test-code","state",state)).statusCode()).isEqualTo(400);assertThat(fixture.exchanges.get()).isZero();
        authorization=json.readTree(post("/ha/authorize",Map.of("origin","http://localhost:4300")).body());fixture.challenge=form(URI.create(authorization.path("authorizeUrl").asText()).getRawQuery()).get("code_challenge");
        assertThat(post("/ha/authorize/complete",Map.of("code","test-code","state",authorization.path("state").asText())).statusCode()).isEqualTo(200);
        var restored=getSession();assertThat(restored.path("authenticated").asBoolean()).isTrue();assertThat(restored.path("homeAssistant").path("connected").asBoolean()).isTrue();assertThat(restored.toString()).doesNotContain("test-upstream-access","test-upstream-refresh");
        var messages=new LinkedBlockingQueue<String>();var closed=new CountDownLatch(1);
        var socket=http.newWebSocketBuilder().header("Origin","http://localhost:4300").buildAsync(URI.create("ws://localhost:"+port+"/api/v1/ha/ws"),new WebSocket.Listener(){
            @Override public void onOpen(WebSocket ws){ws.request(1);}
            @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){messages.add(data.toString());ws.request(1);return null;}
            @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){closed.countDown();return null;}
        }).get(10,TimeUnit.SECONDS);
        assertThat(json.readTree(messages.poll(5,TimeUnit.SECONDS)).path("type").asText()).isEqualTo("auth_required");socket.sendText("{\"type\":\"auth\"}",true).join();assertThat(json.readTree(messages.poll(10,TimeUnit.SECONDS)).path("type").asText()).isEqualTo("auth_ok");assertThat(fixture.refreshes.get()).isEqualTo(1);
        socket.sendText("{\"id\":1,\"type\":\"get_states\"}",true).join();String snapshot=messages.poll(5,TimeUnit.SECONDS);assertThat(snapshot).contains("vacuum.test").doesNotContain("test-upstream-access","access_token");
        socket.sendText("{\"id\":2,\"type\":\"auth/long_lived_access_token\"}",true).join();assertThat(json.readTree(messages.poll(5,TimeUnit.SECONDS)).path("success").asBoolean()).isFalse();
        assertThat(post("/auth/logout",Map.of()).statusCode()).isEqualTo(200);assertThat(closed.await(5,TimeUnit.SECONDS)).isTrue();assertThat(fixture.revoked.get()).isFalse();assertThat(getSession().path("authenticated").asBoolean()).isFalse();socket.abort();
        assertThat(post("/auth/login",Map.of("username","test-owner","password","test-only-password-42!")).statusCode()).isEqualTo(200);
        assertThat(getSession().path("homeAssistant").path("connected").asBoolean()).isTrue();assertThat(fixture.exchanges.get()).isEqualTo(1);
        assertThat(post("/ha/disconnect",Map.of()).statusCode()).isEqualTo(200);assertThat(fixture.revoked.get()).isTrue();assertThat(getSession().path("homeAssistant").path("connected").asBoolean()).isFalse();
    }
    static Map<String,String> form(String encoded){var result=new HashMap<String,String>();for(var field:encoded.split("&")){var parts=field.split("=",2);result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length==2?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}return result;}
    @TestConfiguration
    static class Fixture implements WebSocketConfigurer {
        final FakeHa ha=new FakeHa();
        @Bean FakeHa fakeHa(){return ha;}
        @Bean @Primary HaClient fakeEndpoint(Environment env,ObjectMapper json){return new HaClient("",json){@Override public String url(){return "http://localhost:"+env.getProperty("local.server.port")+"/fake-ha";}@Override public URI endpoint(String path){return URI.create(url()+path);}};}
        @Bean @Order(0) SecurityFilterChain fakeSecurity(HttpSecurity http)throws Exception{return http.securityMatcher("/fake-ha/**").csrf(c->c.disable()).authorizeHttpRequests(c->c.anyRequest().permitAll()).build();}
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(new TextWebSocketHandler(){
            @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{session.sendMessage(new TextMessage("{\"type\":\"auth_required\"}"));}
            @Override protected void handleTextMessage(WebSocketSession session,TextMessage message)throws Exception{var data=new ObjectMapper().readTree(message.getPayload());if(data.path("type").asText().equals("auth")){assertThat(data.path("access_token").asText()).isEqualTo("test-upstream-access-renewed");session.sendMessage(new TextMessage("{\"type\":\"auth_ok\"}"));}else session.sendMessage(new TextMessage("{\"id\":"+data.path("id").asInt()+",\"type\":\"result\",\"success\":true,\"result\":[{\"entity_id\":\"vacuum.test\",\"state\":\"docked\",\"attributes\":{\"access_token\":\"test-upstream-access\"}}]}"));}
        },"/fake-ha/api/websocket").setAllowedOrigins("*");}
    }
    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class FakeHa {
        volatile String challenge;final AtomicInteger exchanges=new AtomicInteger(),refreshes=new AtomicInteger();final AtomicBoolean revoked=new AtomicBoolean();
        @PostMapping(value="/fake-ha/auth/token",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        Map<String,Object> token(@RequestBody String body)throws Exception{var data=form(body);if(data.get("grant_type").equals("authorization_code")){exchanges.incrementAndGet();assertThat(data.get("client_id")).isEqualTo("http://localhost:4300/");String digest=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(data.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));assertThat(digest).isEqualTo(challenge);return Map.of("access_token","test-upstream-access","refresh_token","test-upstream-refresh","token_type","Bearer","expires_in",1);}refreshes.incrementAndGet();assertThat(data.get("refresh_token")).isEqualTo("test-upstream-refresh");return Map.of("access_token","test-upstream-access-renewed","token_type","Bearer","expires_in",1800);}
        @PostMapping("/fake-ha/auth/revoke") void revoke(@RequestBody String body){revoked.set(form(body).get("token").equals("test-upstream-refresh"));}
    }
}
