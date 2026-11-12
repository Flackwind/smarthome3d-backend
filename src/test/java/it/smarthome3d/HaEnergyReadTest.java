package it.smarthome3d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.smarthome3d.api.ApiException;
import it.smarthome3d.ha.HaClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HaEnergyReadTest {
    @Test void readsOnlyStatesWithBearerAndRejectsRedirectsOrUnsafeResponses()throws Exception{
        var status=new AtomicInteger(200);var unexpected=new AtomicInteger();var body=new AtomicReference<>("[{\"entity_id\":\"sensor.power\",\"state\":\"120\",\"attributes\":{\"unit_of_measurement\":\"W\"}}]");var failure=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/states",e->{
            if(!e.getRequestMethod().equals("GET")||!"Bearer test-access-in-memory".equals(e.getRequestHeaders().getFirst("Authorization"))||e.getRequestURI().getQuery()!=null)failure.set("Invalid request");
            if(status.get()==302)e.getResponseHeaders().set("Location","/unexpected");byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status.get(),bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.createContext("/unexpected",e->{unexpected.incrementAndGet();e.sendResponseHeaders(204,-1);e.close();});server.start();
        try{
            var client=new HaClient("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());assertThat(client.states("test-access-in-memory").get(0).path("state").asText()).isEqualTo("120");assertThat(failure).hasNullValue();
            status.set(302);assertThatThrownBy(()->client.states("test-access-in-memory")).isInstanceOf(ApiException.class);assertThat(unexpected).hasValue(0);
            status.set(200);body.set("{}");assertThatThrownBy(()->client.states("test-access-in-memory")).isInstanceOf(ApiException.class);
            status.set(503);body.set("private upstream body test-access-in-memory");assertThatThrownBy(()->client.states("test-access-in-memory")).isInstanceOf(ApiException.class).hasMessageNotContaining("private").hasMessageNotContaining("test-access");
        }finally{server.stop(0);}
    }
}
