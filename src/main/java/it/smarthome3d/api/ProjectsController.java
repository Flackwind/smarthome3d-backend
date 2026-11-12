package it.smarthome3d.api;

import com.fasterxml.jackson.databind.*;
import it.smarthome3d.storage.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ProjectsController {
    private final ProjectStorage storage;private final BackupService backups;
    public ProjectsController(ProjectStorage storage,BackupService backups){this.storage=storage;this.backups=backups;}
    @GetMapping("/projects") public List<ProjectStorage.Document> list(){return storage.list();}
    @GetMapping("/projects/{id}") public ResponseEntity<JsonNode> get(@PathVariable String id){var d=storage.get(id);return ResponseEntity.ok().eTag("\""+d.revision()+"\"").body(d.project());}
    @PutMapping("/projects/{id}") public ResponseEntity<Void> save(@PathVariable String id,@RequestBody JsonNode project,@RequestHeader(value="If-Match",required=false)String expected,@RequestHeader(value="If-None-Match",required=false)String absent){
        ProjectValidator.require(absent==null||absent.equals("*"),"Precondizione non valida");ProjectValidator.require(expected==null||absent==null,"Precondizioni incompatibili");
        var saved=storage.save(id,project,expected,"*".equals(absent));return ResponseEntity.status(absent==null?200:201).eTag("\""+saved.revision()+"\"").build();
    }
    @DeleteMapping("/projects/{id}") public ResponseEntity<Void> delete(@PathVariable String id,@RequestHeader(value="If-Match",required=false)String expected){storage.delete(id,expected);return ResponseEntity.noContent().build();}
    @GetMapping("/projects/{id}/assets") public List<ProjectStorage.AssetInfo> assets(@PathVariable String id){return storage.assets(id);}
    @GetMapping("/assets/{id}") public ResponseEntity<byte[]> asset(@PathVariable String id){var asset=storage.asset(id);return ResponseEntity.ok().header("X-Project-Id",asset.projectId()).header("X-Asset-Name",URLEncoder.encode(asset.name(),StandardCharsets.UTF_8).replace("+","%20")).header("Content-Disposition",ContentDisposition.attachment().filename(asset.name(),StandardCharsets.UTF_8).build().toString()).contentType(MediaType.parseMediaType(asset.mediaType())).body(asset.content());}
    @PutMapping(value="/projects/{projectId}/assets/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> upload(@PathVariable String projectId,@PathVariable String id,@RequestPart("file")MultipartFile file)throws IOException {
        storage.saveAsset(new ProjectStorage.Asset(id,projectId,file.getOriginalFilename(),file.getContentType()==null?"application/octet-stream":file.getContentType(),file.getBytes()));return ResponseEntity.noContent().build();
    }
    @PostMapping(value="/imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectStorage.Document migrate(@RequestPart("project")JsonNode project,@RequestPart("assets")JsonNode metadata,@RequestPart(value="files",required=false)List<MultipartFile> files)throws IOException {
        ProjectValidator.require(metadata.isArray()&&metadata.size()<=2000,"Lista file non valida");ProjectValidator.noSecrets(metadata);Map<String,MultipartFile> incoming=new HashMap<>();long size=project.toString().getBytes(StandardCharsets.UTF_8).length;
        for(var file:files==null?List.<MultipartFile>of():files){size+=file.getSize();ProjectValidator.require(incoming.put(ProjectValidator.key(file.getOriginalFilename()),file)==null&&size<=ProjectValidator.MAX_BUNDLE,"File duplicato o importazione troppo grande");}
        List<ProjectStorage.Asset> assets=new ArrayList<>();for(var item:metadata){String id=item.path("id").asText();var file=incoming.remove(id);ProjectValidator.require(file!=null,"File dichiarato mancante");assets.add(new ProjectStorage.Asset(id,"",item.path("name").asText(),item.path("mediaType").asText(),file.getBytes()));}ProjectValidator.require(incoming.isEmpty(),"File non dichiarato");return storage.importProject(project,assets);
    }
    @GetMapping("/projects/{id}/backup") public ResponseEntity<byte[]> backup(@PathVariable String id)throws IOException{return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header("Content-Disposition","attachment; filename=\"smart-home-3d-"+ProjectValidator.key(id)+".zip\"").body(backups.export(id));}
    @PostMapping(value="/backups/restore",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProjectStorage.Document restore(@RequestPart("file")MultipartFile file)throws IOException{ProjectValidator.require(file.getSize()<=ProjectValidator.MAX_BUNDLE,"Archivio troppo grande");return backups.restore(file.getInputStream());}
}
