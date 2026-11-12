package it.smarthome3d;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import it.smarthome3d.auth.*;
import it.smarthome3d.ha.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class ProfileRestartIntegrationTest {
    @TempDir Path directory;
    static final ObjectMapper JSON=new ObjectMapper();
    static final String PASSWORD="restart-test-owner-password-42!",REFRESH="restart-test-private-refresh",ACCESS="restart-test-private-access";
    ConfigurableApplicationContext start(String database,String ha){return new SpringApplicationBuilder(Application.class).run("--server.address=127.0.0.1","--server.port=0","--spring.datasource.url="+database,"--sh3d.ha-url="+ha,"--sh3d.origins=http://localhost:4300","--debug=false","--logging.level.root=WARN","--spring.main.banner-mode=off");}
    @Test void restoresEncryptedOwnerGrantAndSelectedHouseAfterARealApplicationRestart()throws Exception{
        String database="jdbc:h2:file:"+directory.resolve("profile")+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        try(var upstream=new Upstream()){
            try(var first=start(database,upstream.url())){
                var browser=new Browser(first);browser.session();browser.post("/auth/setup",Map.of("username","restart-owner","password",PASSWORD),200);browser.session();
                browser.put("/projects/house-first",JSON.readTree(ApiIntegrationTest.minimal("house-first")),true,201);
                browser.put("/projects/house-other",JSON.readTree(ApiIntegrationTest.minimal("house-other")),true,201);
                browser.put("/profile/active-project",Map.of("projectId","house-first"),false,200);
                browser.authorize();assertThat(browser.session().at("/homeAssistant/connected").asBoolean()).isTrue();
                var db=first.getBean(JdbcTemplate.class);String encrypted=db.queryForObject("SELECT encrypted_refresh FROM owner_ha_connection",String.class);assertThat(encrypted).startsWith("v1:").doesNotContain(REFRESH,ACCESS,PASSWORD);
                byte[] salt=db.queryForObject("SELECT key_salt FROM owner_profile",byte[].class);assertThat(salt).hasSize(32);
                browser.post("/auth/logout",Map.of(),200);assertThat(browser.session().path("authenticated").asBoolean()).isFalse();assertThat(upstream.revokes.get()).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM owner_ha_connection",Integer.class)).isEqualTo(1);
            }
            try(var reopened=start(database,upstream.url())){
                var browser=new Browser(reopened);assertThat(browser.session().path("authenticated").asBoolean()).isFalse();
                browser.post("/auth/login",Map.of("username","restart-owner","password","incorrect-password"),401);assertThat(upstream.refreshes.get()).isZero();browser.session();
                browser.post("/auth/login",Map.of("username","restart-owner","password",PASSWORD),200);
                String session=browser.session().toString();assertThat(session).contains("\"connected\":true").doesNotContain(REFRESH,ACCESS,PASSWORD,"encrypted_refresh");
                assertThat(browser.get("/profile").path("activeProject").asText()).isEqualTo("house-first");assertThat(browser.get("/projects")).hasSize(2);
                var connection=reopened.getBean(HaProfileConnection.class);var profile=reopened.getBean(OwnerProfile.class);var db=reopened.getBean(JdbcTemplate.class);
                try(var key=profile.unlock(PASSWORD);var wrong=profile.unlock("incorrect-password")){
                    var otherInstance=new HaProfileConnection(db,new HaClient("http://127.0.0.1:1",JSON),new HaConnections());assertThat(otherInstance.status(key).connected()).isFalse();assertThatThrownBy(()->otherInstance.access(key)).hasMessageContaining("Collega Home Assistant");
                    assertThatThrownBy(()->connection.access(wrong)).hasMessageContaining("non è leggibile");assertThat(db.queryForObject("SELECT COUNT(*) FROM owner_ha_connection",Integer.class)).isEqualTo(1);
                    upstream.rotate.set(true);assertThat(connection.access(key)).isEqualTo(ACCESS);assertThat(upstream.refreshes.get()).isEqualTo(1);
                    String encrypted=db.queryForObject("SELECT encrypted_refresh FROM owner_ha_connection",String.class);assertThat(encrypted).doesNotContain(REFRESH,upstream.refresh.get(),ACCESS);
                    connection.clearAccess();upstream.error.set(503);assertThatThrownBy(()->connection.access(key)).hasMessageContaining("Verifica connessione");assertThat(connection.status(key).connected()).isTrue();
                    upstream.error.set(0);assertThat(connection.access(key)).isEqualTo(ACCESS);assertThat(upstream.lastRefresh.get()).endsWith("-rotated");
                    byte[] backup=browser.bytes("/projects/house-first/backup");try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(backup))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertThat(entry.getName()).doesNotContain("profile","credential");assertThat(new String(zip.readAllBytes(),StandardCharsets.UTF_8)).doesNotContain(REFRESH,ACCESS,encrypted,"key_salt","encrypted_refresh");}}
                    db.update("UPDATE owner_ha_connection SET encrypted_refresh=? WHERE owner_id=1","v1:damaged");assertThat(connection.status(key).connected()).isFalse();assertThatThrownBy(()->connection.access(key)).hasMessageContaining("non è leggibile");browser.authorize();assertThat(browser.session().at("/homeAssistant/connected").asBoolean()).isTrue();assertThat(db.queryForObject("SELECT encrypted_refresh FROM owner_ha_connection",String.class)).isNotEqualTo("v1:damaged");
                    connection.clearAccess();upstream.error.set(400);assertThatThrownBy(()->connection.access(key)).isInstanceOf(HaAuthorizationException.class);assertThat(connection.status(key).connected()).isFalse();assertThat(db.queryForObject("SELECT COUNT(*) FROM owner_ha_connection",Integer.class)).isZero();assertThat(browser.get("/projects")).hasSize(2);
                    upstream.error.set(0);browser.authorize();assertThat(browser.session().at("/homeAssistant/connected").asBoolean()).isTrue();browser.post("/ha/disconnect",Map.of(),200);assertThat(browser.session().at("/homeAssistant/connected").asBoolean()).isFalse();assertThat(upstream.revokes.get()).isEqualTo(1);
                }
            }
            String disk=new String(Files.readAllBytes(directory.resolve("profile.mv.db")),StandardCharsets.ISO_8859_1);assertThat(disk).doesNotContain(REFRESH,ACCESS,PASSWORD);
        }
    }
    static class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();final String base;String csrf;
        Browser(ConfigurableApplicationContext app){base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1";}
        JsonNode session()throws Exception{var result=get("/session");csrf=result.path("csrfToken").asText();return result;}
        JsonNode get(String path)throws Exception{return JSON.readTree(bytes(path));}
        byte[] bytes(String path)throws Exception{var response=http.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(response.statusCode()).isEqualTo(200);return response.body();}
        JsonNode post(String path,Object body,int status)throws Exception{return send("POST",path,body,false,status);}
        JsonNode put(String path,Object body,boolean create,int status)throws Exception{return send("PUT",path,body,create,status);}
        JsonNode send(String method,String path,Object body,boolean create,int status)throws Exception{var request=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json").header("X-SH3D-CSRF",csrf);if(create)request.header("If-None-Match","*");var response=http.send(request.method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).as(path).isEqualTo(status);return response.body().isBlank()?JSON.nullNode():JSON.readTree(response.body());}
        void authorize()throws Exception{var result=post("/ha/authorize",Map.of("origin","http://localhost:4300"),200);post("/ha/authorize/complete",Map.of("code","test-code","state",result.path("state").asText()),200);}
    }
    static class Upstream implements AutoCloseable {
        final HttpServer server;final AtomicReference<String> refresh=new AtomicReference<>(REFRESH),lastRefresh=new AtomicReference<>();final AtomicInteger refreshes=new AtomicInteger(),revokes=new AtomicInteger(),error=new AtomicInteger();final AtomicBoolean rotate=new AtomicBoolean();
        Upstream()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/auth/token",this::token);server.createContext("/auth/revoke",exchange->{revokes.incrementAndGet();respond(exchange,200,Map.of());});server.start();}
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void token(HttpExchange exchange)throws java.io.IOException{
            var form=HaSessionIntegrationTest.form(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));if(error.get()!=0){respond(exchange,error.get(),Map.of("error","invalid_grant"));return;}
            if("refresh_token".equals(form.get("grant_type"))){lastRefresh.set(form.get("refresh_token"));if(!refresh.get().equals(lastRefresh.get())||!"http://localhost:4300/".equals(form.get("client_id"))){respond(exchange,400,Map.of("error","invalid_grant"));return;}refreshes.incrementAndGet();if(rotate.getAndSet(false)){refresh.set(REFRESH+"-rotated");respond(exchange,200,Map.of("access_token",ACCESS,"refresh_token",refresh.get(),"token_type","Bearer","expires_in",1800));}else respond(exchange,200,Map.of("access_token",ACCESS,"token_type","Bearer","expires_in",1800));}
            else{refresh.set(REFRESH);respond(exchange,200,Map.of("access_token",ACCESS,"refresh_token",REFRESH,"token_type","Bearer","expires_in",1));}
        }
        static void respond(HttpExchange exchange,int status,Object body)throws java.io.IOException{byte[] bytes=JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var stream=exchange.getResponseBody()){stream.write(bytes);}exchange.close();}
        @Override public void close(){server.stop(0);}
    }
}
