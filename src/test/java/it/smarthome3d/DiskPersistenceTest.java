package it.smarthome3d;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.smarthome3d.storage.*;
import it.smarthome3d.energy.*;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class DiskPersistenceTest {
    @TempDir Path directory;
    @Test void reopensProjectsAndOriginalFilesFromDiskWithANewRepository()throws Exception{
        String url="jdbc:h2:file:"+directory.resolve("home")+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";var firstSource=new DriverManagerDataSource(url,"sa","");new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(firstSource);
        var json=new ObjectMapper();var first=new ProjectStorage(new JdbcTemplate(firstSource),json,new ProjectValidator());first.save("persistent",json.readTree(ApiIntegrationTest.minimal("persistent")),null,true);first.saveAsset(new ProjectStorage.Asset("source","persistent","pianta.pdf","application/pdf",new byte[]{1,2,3,4}));
        var energy=new EnergyStorage(new JdbcTemplate(firstSource),first,json);energy.record("persistent",List.of(new EnergyMath.Bucket(1793966400L,"$electricity","Casa","electricity","sensor",2,.6,0,3600)));energy.manual("persistent",new EnergyStorage.Manual("2026-10","gas",45,40.));
        var reopened=new ProjectStorage(new JdbcTemplate(new DriverManagerDataSource(url,"sa","")),json,new ProjectValidator());assertThat(reopened.get("persistent").project().path("name").asText()).isEqualTo("Casa test");assertThat(reopened.asset("source").content()).containsExactly(1,2,3,4);assertThat(directory.resolve("home.mv.db")).exists();
        var reopenedEnergy=new EnergyStorage(new JdbcTemplate(new DriverManagerDataSource(url,"sa","")),reopened,json);assertThat(reopenedEnergy.year("persistent",2026).get(10).electricity().amount()).isEqualTo(2);assertThat(reopenedEnergy.year("persistent",2026).get(9).gas().amount()).isEqualTo(45);
    }
}
