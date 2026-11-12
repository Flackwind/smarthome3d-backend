package it.smarthome3d;

import com.fasterxml.jackson.databind.*;
import it.smarthome3d.api.ApiException;
import it.smarthome3d.energy.*;
import it.smarthome3d.storage.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:energy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sh3d.ha-url=","sh3d.energy-interval-ms=3600000"})
@AutoConfigureMockMvc
class EnergyIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired ProjectStorage projects;@Autowired EnergyStorage energy;@Autowired BackupService backups;
    MockHttpSession session;String csrf;
    @BeforeEach void setup()throws Exception{
        db.update("DELETE FROM projects");db.update("DELETE FROM owner_account");
        var initial=mvc.perform(get("/api/v1/session")).andReturn();session=(MockHttpSession)initial.getRequest().getSession();csrf=json.readTree(initial.getResponse().getContentAsString()).path("csrfToken").asText();
        var login=mvc.perform(post("/api/v1/auth/setup").session(session).header("X-SH3D-CSRF",csrf).contentType("application/json").content("{\"username\":\"energy-owner\",\"password\":\"test-energy-password-42!\"}")).andExpect(status().isOk()).andReturn();session=(MockHttpSession)login.getRequest().getSession();csrf=json.readTree(mvc.perform(get("/api/v1/session").session(session)).andReturn().getResponse().getContentAsString()).path("csrfToken").asText();
        projects.save("energy-home",json.readTree(ApiIntegrationTest.minimal("energy-home")),null,true);
    }
    @Test void authenticatesWritesPersistsManualOverridesAndPreservesObservedDetail()throws Exception{
        String path="/api/v1/projects/energy-home/energy";mvc.perform(get(path).param("year","2026")).andExpect(status().isUnauthorized());mvc.perform(put(path+"/monthly").session(session).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        energy.record("energy-home",List.of(new EnergyMath.Bucket(Instant.parse("2026-11-06T12:00:00Z").getEpochSecond(),"$electricity","Casa","electricity","nominal",1,.25,0,3600),new EnergyMath.Bucket(Instant.parse("2026-11-06T12:00:00Z").getEpochSecond(),"lamp","Lampada","electricity","nominal",1,.25,0,3600)));
        mvc.perform(put(path+"/monthly").session(session).header("X-SH3D-CSRF",csrf).contentType("application/json").content("{\"month\":\"2026-11\",\"utility\":\"electricity\",\"amount\":250,\"cost\":65}")).andExpect(status().isOk());
        mvc.perform(get(path).session(session).param("year","2026")).andExpect(jsonPath("$.months[0].electricity").doesNotExist()).andExpect(jsonPath("$.months[10].electricity.amount").value(250)).andExpect(jsonPath("$.months[10].electricity.qualities[0]").value("manual")).andExpect(jsonPath("$.months[10].devices[0].total.amount").value(1));
        mvc.perform(delete(path+"/monthly/2026-11/electricity").session(session).header("X-SH3D-CSRF",csrf)).andExpect(status().isOk());mvc.perform(get(path).session(session).param("year","2026")).andExpect(jsonPath("$.months[10].electricity.amount").value(1));
        mvc.perform(put(path+"/monthly").session(session).header("X-SH3D-CSRF",csrf).contentType("application/json").content("{\"month\":\"2026-13\",\"utility\":\"gas\",\"amount\":-1}")).andExpect(status().isBadRequest());mvc.perform(get(path).session(session).param("year","1")).andExpect(status().isBadRequest());
        projects.delete("energy-home","\"1\"");assertThat(db.queryForObject("SELECT COUNT(*) FROM energy_buckets",Integer.class)).isZero();
    }
    @Test void zipIncludesHistoryRestoresAtomicallyAndAcceptsOldBackups()throws Exception{
        long hour=Instant.parse("2026-11-01T23:00:00Z").getEpochSecond();energy.record("energy-home",List.of(new EnergyMath.Bucket(hour,"$gas","Gas","gas","thermal",.5,.4,0,3600)));energy.manual("energy-home",new EnergyStorage.Manual("2026-10","gas",80,null));
        byte[] zip=backups.export("energy-home");var entries=unzip(zip);assertThat(json.readTree(entries.get("manifest.json")).path("formatVersion").asInt()).isEqualTo(2);assertThat(new String(entries.get("energy.json"),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("token","password","attributes");
        var restored=backups.restore(new ByteArrayInputStream(zip));String id=restored.project().path("id").asText();assertThat(id).isNotEqualTo("energy-home");assertThat(energy.year(id,2026).get(10).gas().amount()).isEqualTo(.5);assertThat(energy.year(id,2026).get(9).gas().cost()).isNull();
        int count=projects.list().size();var broken=json.readTree(entries.get("energy.json"));((com.fasterxml.jackson.databind.node.ObjectNode)broken.path("buckets").get(0)).put("amount",-1);entries.put("energy.json",json.writeValueAsBytes(broken));assertThatThrownBy(()->backups.restore(new ByteArrayInputStream(zip(entries)))).isInstanceOf(ApiException.class);assertThat(projects.list()).hasSize(count);
        entries.remove("energy.json");var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(entries.get("manifest.json"));manifest.put("formatVersion",1);entries.put("manifest.json",json.writeValueAsBytes(manifest));assertThat(backups.restore(new ByteArrayInputStream(zip(entries))).project().path("name").asText()).isEqualTo("Casa test");
    }
    @Test void rejectsInvalidEnergyConfigurationAtTheProjectBoundary()throws Exception{
        var project=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(ApiIntegrationTest.minimal("bad-energy"));project.set("energy",json.readTree("{\"version\":1.9,\"enabled\":true}"));mvc.perform(put("/api/v1/projects/bad-energy").session(session).header("X-SH3D-CSRF",csrf).header("If-None-Match","*").contentType("application/json").content(project.toString())).andExpect(status().isBadRequest());
    }
    private Map<String,byte[]> unzip(byte[] bytes)throws Exception{var entries=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){for(ZipEntry e;(e=zip.getNextEntry())!=null;)entries.put(e.getName(),zip.readAllBytes());}return entries;}
    private byte[] zip(Map<String,byte[]> entries)throws Exception{var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var e:entries.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return bytes.toByteArray();}
}
