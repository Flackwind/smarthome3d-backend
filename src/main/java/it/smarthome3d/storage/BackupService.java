package it.smarthome3d.storage;

import com.fasterxml.jackson.databind.*;
import it.smarthome3d.api.ApiException;
import it.smarthome3d.energy.EnergyStorage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class BackupService {
    private final ProjectStorage storage;private final ObjectMapper json;private final EnergyStorage energy;
    public BackupService(ProjectStorage storage,ObjectMapper json,EnergyStorage energy){this.storage=storage;this.json=json;this.energy=energy;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] export(String id) throws IOException {
        var document=storage.get(id);var assets=storage.assets(id);
        byte[] history=json.writeValueAsBytes(energy.archive(id));
        long size=document.project().toString().getBytes(StandardCharsets.UTF_8).length+history.length+assets.stream().mapToLong(ProjectStorage.AssetInfo::size).sum();
        ProjectValidator.require(size<=ProjectValidator.MAX_BUNDLE,"Backup superiore al limite di 150 MB");
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
            entry(zip,"project.json",json.writeValueAsBytes(document.project()));
            var manifest=json.createObjectNode();manifest.put("formatVersion",2);manifest.set("assets",json.valueToTree(assets));entry(zip,"manifest.json",json.writeValueAsBytes(manifest));
            entry(zip,"energy.json",history);
            for(var info:assets)entry(zip,"assets/"+info.id(),storage.asset(info.id()).content());
        }
        return bytes.toByteArray();
    }
    @Transactional(rollbackFor=Exception.class)
    public ProjectStorage.Document restore(InputStream input) throws IOException {
        Map<String,byte[]> entries=new HashMap<>();long total=0;
        try(var zip=new ZipInputStream(input,StandardCharsets.UTF_8)){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                String path=entry.getName();ProjectValidator.require(!entry.isDirectory()&&(path.equals("project.json")||path.equals("manifest.json")||path.equals("energy.json")||path.matches("assets/[a-zA-Z0-9_-]{1,200}"))&&!entries.containsKey(path)&&entries.size()<2003,"Archivio non valido: percorso o voce duplicata");
                int limit=path.equals("project.json")?ProjectValidator.MAX_DOCUMENT:path.equals("manifest.json")?1_000_000:path.equals("energy.json")?ProjectValidator.MAX_BUNDLE:ProjectValidator.MAX_ASSET;
                byte[] data=zip.readNBytes(limit+1);total+=data.length;ProjectValidator.require(data.length<=limit&&total<=ProjectValidator.MAX_BUNDLE,"Archivio espanso troppo grande");entries.put(path,data);
            }
        }
        ProjectValidator.require(entries.containsKey("project.json")&&entries.containsKey("manifest.json"),"Archivio incompleto");
        var manifest=json.readTree(entries.remove("manifest.json"));var project=json.readTree(entries.remove("project.json"));
        int version=manifest.path("formatVersion").asInt();ProjectValidator.noSecrets(manifest);ProjectValidator.require(manifest.path("formatVersion").isIntegralNumber()&&(version==1||version==2)&&manifest.path("assets").isArray(),"Versione archivio non supportata");
        byte[] history=entries.remove("energy.json");ProjectValidator.require((version==2)==(history!=null),"Storico consumi mancante o non dichiarato");
        List<ProjectStorage.Asset> assets=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(var meta:manifest.path("assets")){String id=ProjectValidator.key(meta.path("id").asText());byte[] content=entries.remove("assets/"+id);ProjectValidator.require(ids.add(id)&&content!=null&&content.length==meta.path("size").asLong(-1),"File dell’archivio mancante o incoerente");assets.add(new ProjectStorage.Asset(id,"",meta.path("name").asText(),meta.path("mediaType").asText(),content));}
        ProjectValidator.require(entries.isEmpty(),"Archivio con file non dichiarati");var result=storage.importProject(project,assets);if(history!=null)energy.restore(result.project().path("id").asText(),json.readTree(history));return result;
    }
    private static void entry(ZipOutputStream zip,String name,byte[] bytes)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
}
