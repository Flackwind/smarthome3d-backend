package it.smarthome3d;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.smarthome3d.storage.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sh3d.origins=http://localhost:4300","sh3d.ha-url="})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
    record Browser(MockHttpSession session,String csrf){}
    @BeforeEach void clean(){db.update("DELETE FROM assets");db.update("DELETE FROM projects");db.update("DELETE FROM owner_account");}
    Browser browserStatus(MockHttpSession session)throws Exception{var result=mvc.perform(get("/api/v1/session").session(session==null?new MockHttpSession():session)).andExpect(status().isOk()).andReturn();var body=json.readTree(result.getResponse().getContentAsString());return new Browser((MockHttpSession)result.getRequest().getSession(false),body.path("csrfToken").asText());}
    Browser owner()throws Exception{var initial=browserStatus(null);var result=mvc.perform(post("/api/v1/auth/setup").session(initial.session).header("X-SH3D-CSRF",initial.csrf).contentType("application/json").content("{\"username\":\"test-owner\",\"password\":\"test-only-password-42!\"}")).andExpect(status().isOk()).andReturn();return browserStatus((MockHttpSession)result.getRequest().getSession(false));}
    static String minimal(String id){return """
        {"schemaVersion":1,"id":"%s","name":"Casa test","createdAt":"2026-11-03T10:00:00Z","updatedAt":"2026-11-03T10:00:00Z","building":{"id":"building","name":"Casa test","floors":[{"id":"floor","name":"Piano terra","elevation":0,"ceilingHeight":2.7,"vertices":[],"walls":[],"rooms":[],"objects":[]}]},"bindings":[],"scene":{"quality":"medium","theme":"dark","showWalls":true,"camera":{"position":{"x":4,"y":14,"z":15},"target":{"x":4,"y":0,"z":4}}}}
        """.formatted(id);}
    void create(Browser browser,String id)throws Exception{mvc.perform(put("/api/v1/projects/"+id).session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-None-Match","*").contentType("application/json").content(minimal(id))).andExpect(status().isCreated()).andExpect(header().string("ETag","\"1\""));}
    @Test void protectsSetupSessionsCsrfAndOrigins()throws Exception{
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());var anonymous=browserStatus(null);
        mvc.perform(post("/api/v1/auth/setup").session(anonymous.session).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/session").header("Origin","https://untrusted.example")).andExpect(status().isForbidden());
        var browser=owner();mvc.perform(get("/api/v1/session").session(browser.session)).andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.username").value("test-owner")).andExpect(jsonPath("$.setupRequired").value(false));
        assertThat(db.queryForObject("SELECT password_hash FROM owner_account",String.class)).doesNotContain("test-only-password");
        mvc.perform(post("/api/v1/auth/setup").session(browser.session).header("X-SH3D-CSRF",browser.csrf).contentType("application/json").content("{\"username\":\"other\",\"password\":\"test-other-password\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/logout").session(browser.session).header("X-SH3D-CSRF",browser.csrf)).andExpect(status().isOk()).andExpect(cookie().maxAge("SH3D_SESSION",0));
        var next=browserStatus(null);mvc.perform(post("/api/v1/auth/login").session(next.session).header("X-SH3D-CSRF",next.csrf).contentType("application/json").content("{\"username\":\"test-owner\",\"password\":\"test-only-password-42!\"}")).andExpect(status().isOk());
    }
    @Test void atomicallyRejectsStaleWritesAndDeletes()throws Exception{
        var browser=owner();create(browser,"project-one");var project=(ObjectNode)json.readTree(minimal("project-one"));project.put("name","Modifica PC");
        mvc.perform(put("/api/v1/projects/project-one").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-Match","\"1\"").contentType("application/json").content(project.toString())).andExpect(status().isOk()).andExpect(header().string("ETag","\"2\""));
        project.put("name","Modifica vecchia del telefono");mvc.perform(put("/api/v1/projects/project-one").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-Match","\"1\"").contentType("application/json").content(project.toString())).andExpect(status().isPreconditionFailed());
        mvc.perform(get("/api/v1/projects/project-one").session(browser.session)).andExpect(jsonPath("$.name").value("Modifica PC")).andExpect(header().string("ETag","\"2\""));
        mvc.perform(delete("/api/v1/projects/project-one").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-Match","\"1\"")).andExpect(status().isPreconditionFailed());
        mvc.perform(delete("/api/v1/projects/project-one").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-Match","\"2\"")).andExpect(status().isNoContent());assertThat(db.queryForObject("SELECT COUNT(*) FROM projects",Integer.class)).isZero();
    }
    @Test void protectsTheOwnerProfileAndRemembersTheSelectedProjectAcrossLogins()throws Exception{
        mvc.perform(get("/api/v1/profile")).andExpect(status().isUnauthorized());var first=owner();create(first,"house-one");create(first,"house-two");
        mvc.perform(put("/api/v1/profile/active-project").session(first.session).contentType("application/json").content("{\"projectId\":\"house-one\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/profile/active-project").session(first.session).header("X-SH3D-CSRF",first.csrf).contentType("application/json").content("{\"projectId\":\"missing\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/profile/active-project").session(first.session).header("X-SH3D-CSRF",first.csrf).contentType("application/json").content("{\"projectId\":\"house-one\"}")).andExpect(status().isOk());
        var second=browserStatus(null);var login=mvc.perform(post("/api/v1/auth/login").session(second.session).header("X-SH3D-CSRF",second.csrf).contentType("application/json").content("{\"username\":\"test-owner\",\"password\":\"test-only-password-42!\"}")).andExpect(status().isOk()).andReturn();
        var restored=browserStatus((MockHttpSession)login.getRequest().getSession(false));mvc.perform(get("/api/v1/profile").session(restored.session)).andExpect(jsonPath("$.activeProject").value("house-one"));
        mvc.perform(delete("/api/v1/projects/house-one").session(first.session).header("X-SH3D-CSRF",first.csrf).header("If-Match","\"1\"")).andExpect(status().isNoContent());mvc.perform(get("/api/v1/profile").session(restored.session)).andExpect(jsonPath("$.activeProject").value(""));
    }
    @Test void rejectsSecretFieldsAndMalformedProjectsBeforePersistence()throws Exception{
        var browser=owner();var project=(ObjectNode)json.readTree(minimal("unsafe"));project.withObject("/scene").put("access_token","fake-not-a-real-token");
        mvc.perform(put("/api/v1/projects/unsafe").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-None-Match","*").contentType("application/json").content(project.toString())).andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fake-not-a-real-token"))));assertThat(db.queryForObject("SELECT COUNT(*) FROM projects",Integer.class)).isZero();
        project.remove("scene");mvc.perform(put("/api/v1/projects/unsafe").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-None-Match","*").contentType("application/json").content(project.toString())).andExpect(status().isBadRequest());
    }
    @Test void preservesBinaryAssetsBacksUpAndRestoresIntoANewProject()throws Exception{
        var browser=owner();create(browser,"assets-project");byte[] bytes=new byte[]{0,1,2,(byte)255,20,42};var file=new MockMultipartFile("file","modello.glb","model/gltf-binary",bytes);
        mvc.perform(multipart("/api/v1/projects/assets-project/assets/glb-one").file(file).with(r->{r.setMethod("PUT");return r;}).session(browser.session).header("X-SH3D-CSRF",browser.csrf)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/assets/glb-one").session(browser.session)).andExpect(content().bytes(bytes)).andExpect(header().string("X-Project-Id","assets-project"));
        byte[] backup=mvc.perform(get("/api/v1/projects/assets-project/backup").session(browser.session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var restored=mvc.perform(multipart("/api/v1/backups/restore").file(new MockMultipartFile("file","casa.zip","application/zip",backup)).session(browser.session).header("X-SH3D-CSRF",browser.csrf)).andExpect(status().isOk()).andReturn();var imported=json.readTree(restored.getResponse().getContentAsString());String id=imported.path("project").path("id").asText();assertThat(id).isNotEqualTo("assets-project");
        var assets=mvc.perform(get("/api/v1/projects/"+id+"/assets").session(browser.session)).andExpect(status().isOk()).andReturn();var metadata=json.readTree(assets.getResponse().getContentAsString());String assetId=metadata.get(0).path("id").asText();assertThat(assetId).isNotEqualTo("glb-one");mvc.perform(get("/api/v1/assets/"+assetId).session(browser.session)).andExpect(content().bytes(bytes));
        mvc.perform(delete("/api/v1/projects/assets-project").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-Match","\"1\"")).andExpect(status().isNoContent());mvc.perform(get("/api/v1/assets/glb-one").session(browser.session)).andExpect(status().isNotFound());mvc.perform(get("/api/v1/assets/"+assetId).session(browser.session)).andExpect(status().isOk());
    }
    @Test void rejectsZipTraversalAndRollsBackIncompleteImports()throws Exception{
        var browser=owner();var data=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(data)){zip.putNextEntry(new ZipEntry("../outside"));zip.write(new byte[]{1});zip.closeEntry();}
        mvc.perform(multipart("/api/v1/backups/restore").file(new MockMultipartFile("file","invalid.zip","application/zip",data.toByteArray())).session(browser.session).header("X-SH3D-CSRF",browser.csrf)).andExpect(status().isBadRequest());
        var project=(ObjectNode)json.readTree(minimal("import-original"));var object=json.readTree("{\"id\":\"lamp\",\"name\":\"Lampada\",\"type\":\"bulb\",\"assetId\":\"missing-glb\",\"position\":{\"x\":0,\"y\":0,\"z\":0},\"rotation\":{\"x\":0,\"y\":0,\"z\":0},\"scale\":{\"x\":1,\"y\":1,\"z\":1},\"dimensions\":{\"x\":1,\"y\":1,\"z\":1},\"material\":{\"color\":\"#ffffff\",\"roughness\":0.8}}");((com.fasterxml.jackson.databind.node.ArrayNode)project.at("/building/floors/0/objects")).add(object);
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("project","project.json","application/json",json.writeValueAsBytes(project))).file(new MockMultipartFile("assets","assets.json","application/json","[]".getBytes(StandardCharsets.UTF_8))).session(browser.session).header("X-SH3D-CSRF",browser.csrf)).andExpect(status().isBadRequest());assertThat(db.queryForObject("SELECT COUNT(*) FROM projects",Integer.class)).isZero();
    }
    @Test void rollsBackAlreadyWrittenFilesWhenALaterFileIsRejected()throws Exception{
        var browser=owner();String metadata="[{\"id\":\"first\",\"name\":\"Pianta.pdf\",\"mediaType\":\"application/pdf\"},{\"id\":\"second\",\"name\":\"NotAllowed.svg\",\"mediaType\":\"image/svg+xml\"}]";
        mvc.perform(multipart("/api/v1/imports")
            .file(new MockMultipartFile("project","project.json","application/json",minimal("source").getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("assets","assets.json","application/json",metadata.getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files","first","application/octet-stream",new byte[]{1,2,3}))
            .file(new MockMultipartFile("files","second","application/octet-stream",new byte[]{4,5,6}))
            .session(browser.session).header("X-SH3D-CSRF",browser.csrf)).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM projects",Integer.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM assets",Integer.class)).isZero();
    }

    @Test void preservesDashboardSourcesAcrossStorageAndBackupAndRejectsInvalidReplacements()throws Exception{
        var browser=owner();var project=(ObjectNode)json.readTree(minimal("temperatures"));
        var dashboard=json.readTree("{\"indoorTemperatureEntityId\":\"climate.living\",\"outdoorTemperatureEntityId\":\"sensor.outdoor\",\"outdoorWeatherEntityId\":\"weather.home\",\"useWeatherFallback\":true}");
        project.set("dashboard",dashboard);
        mvc.perform(put("/api/v1/projects/temperatures").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-None-Match","*").contentType("application/json").content(project.toString())).andExpect(status().isCreated());
        var invalid=project.deepCopy();invalid.withObject("/dashboard").put("outdoorWeatherEntityId","sensor.outdoor");
        mvc.perform(put("/api/v1/projects/temperatures").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-Match","\"1\"").contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
        invalid=project.deepCopy();invalid.withObject("/dashboard").put("useWeatherFallback","yes");
        mvc.perform(put("/api/v1/projects/temperatures").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-Match","\"1\"").contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/projects/temperatures").session(browser.session)).andExpect(header().string("ETag","\"1\"")).andExpect(jsonPath("$.dashboard.outdoorWeatherEntityId").value("weather.home"));
        byte[] backup=mvc.perform(get("/api/v1/projects/temperatures/backup").session(browser.session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var restored=mvc.perform(multipart("/api/v1/backups/restore").file(new MockMultipartFile("file","temperatures.zip","application/zip",backup)).session(browser.session).header("X-SH3D-CSRF",browser.csrf)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(restored.getResponse().getContentAsString()).path("project").path("dashboard")).isEqualTo(dashboard);
    }

    @Test void rejectsFractionalSchemaVersion()throws Exception{var browser=owner();var project=(ObjectNode)json.readTree(minimal("fractional"));project.put("schemaVersion",1.5);mvc.perform(put("/api/v1/projects/fractional").session(browser.session).header("X-SH3D-CSRF",browser.csrf).header("If-None-Match","*").contentType("application/json").content(project.toString())).andExpect(status().isBadRequest());assertThat(db.queryForObject("SELECT COUNT(*) FROM projects",Integer.class)).isZero();}

}
