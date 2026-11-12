package it.smarthome3d.energy;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.smarthome3d.auth.*;
import it.smarthome3d.ha.*;
import it.smarthome3d.storage.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import static org.assertj.core.api.Assertions.*;

class EnergyCollectorTest {
    static class Time extends Clock {Instant now=Instant.parse("2026-11-06T12:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}void tick(long seconds){now=now.plusSeconds(seconds);}}
    @Test void samplesOnceForAllHousesPersistsOnlyAggregatesAndPausesWithoutAnUnlockedSession()throws Exception{
        var source=new DriverManagerDataSource("jdbc:h2:mem:collector;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);var db=new JdbcTemplate(source);var json=new ObjectMapper();var projects=new ProjectStorage(db,json,new ProjectValidator());var storage=new EnergyStorage(db,projects,json);var sessions=new OwnerSessions();var time=new Time();var calls=new AtomicInteger();
        var ha=new HaClient("",json){@Override public JsonNode states(String access){assertThat(access).isEqualTo("test-access-only-in-ram");calls.incrementAndGet();return json.createArrayNode().add(json.createObjectNode().put("entity_id","light.casa").put("state","on").set("attributes",json.createObjectNode().put("private_data","must-not-be-saved")));}};
        var connection=new HaProfileConnection(db,ha,null){@Override public synchronized String access(CredentialKey key){assertThat(key.active()).isTrue();return "test-access-only-in-ram";}};
        var collector=new EnergyCollector(sessions,ha,connection,projects,storage,json,time);
        var config=EnergyMathTest.config("nominal","");
        for(String id:List.of("home-one","home-two")){
            var project=json.createObjectNode();project.put("schemaVersion",1).put("id",id).put("name","Casa").put("createdAt",time.now.toString()).put("updatedAt",time.now.toString());project.set("building",json.readTree("{\"id\":\"building\",\"name\":\"Casa\",\"floors\":[{\"id\":\"floor\",\"name\":\"Piano\",\"elevation\":0,\"ceilingHeight\":2.7,\"vertices\":[],\"walls\":[],\"rooms\":[],\"objects\":[]}]}"));project.putArray("bindings");project.set("scene",json.readTree("{\"quality\":\"medium\",\"theme\":\"dark\",\"showWalls\":true,\"camera\":{\"position\":{\"x\":1,\"y\":1,\"z\":1},\"target\":{\"x\":0,\"y\":0,\"z\":0}}}"));project.set("energy",json.valueToTree(config));projects.save(id,project,null,true);
        }
        collector.collect();assertThat(calls).hasValue(0);
        var session=new MockHttpSession();var key=CredentialKey.derive("test-password-only",new byte[32]);session.setAttribute(CredentialKey.SESSION,key);sessions.add(session);time.tick(60);collector.collect();assertThat(calls).hasValue(1);assertThat(storage.year("home-one",2026).get(10).electricity()).isNull();
        time.tick(60);collector.collect();assertThat(calls).hasValue(2);assertThat(storage.year("home-one",2026).get(10).electricity().amount()).isCloseTo(.1/60,within(1e-9));assertThat(storage.year("home-two",2026).get(10).electricity().amount()).isCloseTo(.1/60,within(1e-9));
        assertThat(json.writeValueAsString(storage.archive("home-one"))).doesNotContain("private_data","must-not-be-saved","test-access","attributes");
        time.tick(600);collector.collect();assertThat(storage.year("home-one",2026).get(10).electricity().amount()).isCloseTo(.1/60,within(1e-9));key.close();time.tick(60);collector.collect();assertThat(calls).hasValue(3);assertThat(collector.status("home-one").message()).contains("pausa");
        session.invalidate();assertThat(sessions.key()).isNull();
    }
}
