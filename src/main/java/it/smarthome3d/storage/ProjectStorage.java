package it.smarthome3d.storage;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.smarthome3d.api.ApiException;
import java.util.*;
import java.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectStorage {
    public record Document(JsonNode project,long revision) {}
    public record Asset(String id,String projectId,String name,String mediaType,byte[] content) {}
    public record AssetInfo(String id,String projectId,String name,String mediaType,long size) {}
    private final JdbcTemplate db; private final ObjectMapper json; private final ProjectValidator validator;
    public ProjectStorage(JdbcTemplate db,ObjectMapper json,ProjectValidator validator){this.db=db;this.json=json;this.validator=validator;}
    public List<Document> list(){return db.query("SELECT document,revision FROM projects ORDER BY id",(rs,n)->new Document(read(rs.getString(1)),rs.getLong(2)));}
    public Document get(String id){ProjectValidator.key(id);return db.query("SELECT document,revision FROM projects WHERE id=?",(rs,n)->new Document(read(rs.getString(1)),rs.getLong(2)),id).stream().findFirst().orElseThrow(ApiException::missing);}
    @Transactional public Document save(String id,JsonNode document,String expected,boolean create){
        ProjectValidator.key(id);validator.validate(document);ProjectValidator.require(id.equals(document.path("id").asText()),"ID del progetto incoerente");
        String encoded=document.toString();ProjectValidator.require(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=ProjectValidator.MAX_DOCUMENT,"Progetto troppo grande");
        if(create){db.update("INSERT INTO projects(id,revision,document) VALUES (?,1,?)",id,encoded);return new Document(document,1);}
        long revision=revision(expected);int updated=db.update("UPDATE projects SET document=?,revision=revision+1 WHERE id=? AND revision=?",encoded,id,revision);
        if(updated==0)throw ApiException.conflict();return new Document(document,revision+1);
    }
    @Transactional public void delete(String id,String expected){ProjectValidator.key(id);if(db.update("DELETE FROM projects WHERE id=? AND revision=?",id,revision(expected))==0)throw ApiException.conflict();}
    public static long revision(String etag){if(etag==null)throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,"È richiesta la revisione del progetto");try{ProjectValidator.require(etag.matches("\"[1-9][0-9]*\""),"Revisione non valida");return Long.parseLong(etag.substring(1,etag.length()-1));}catch(NumberFormatException e){throw ApiException.invalid("Revisione non valida");}}
    public List<AssetInfo> assets(String projectId){get(projectId);return db.query("SELECT id,project_id,name,media_type,OCTET_LENGTH(content) FROM assets WHERE project_id=? ORDER BY id",(rs,n)->new AssetInfo(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),projectId);}
    public Asset asset(String id){ProjectValidator.key(id);return db.query("SELECT id,project_id,name,media_type,content FROM assets WHERE id=?",(rs,n)->new Asset(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBytes(5)),id).stream().findFirst().orElseThrow(ApiException::missing);}
    @Transactional public void saveAsset(Asset asset){
        ProjectValidator.key(asset.id);get(asset.projectId);ProjectValidator.require(asset.name!=null&&!asset.name.isBlank()&&asset.name.length()<=200&&!asset.name.contains("\r")&&!asset.name.contains("\n"),"Nome file non valido");
        ProjectValidator.require(asset.content.length>0&&asset.content.length<=ProjectValidator.MAX_ASSET,"File vuoto o troppo grande");
        ProjectValidator.require(Set.of("image/png","image/jpeg","application/pdf","model/gltf-binary","application/octet-stream").contains(asset.mediaType),"Formato file non supportato");
        var existing=db.queryForList("SELECT project_id,name,media_type,content FROM assets WHERE id=?",asset.id);
        if(!existing.isEmpty()){var old=existing.getFirst();if(asset.projectId.equals(old.get("project_id"))&&asset.name.equals(old.get("name"))&&asset.mediaType.equals(old.get("media_type"))&&Arrays.equals(asset.content,(byte[])old.get("content")))return;throw new ApiException(HttpStatus.CONFLICT,"L’asset esiste già: carica il nuovo file con un nuovo identificatore");}
        db.update("INSERT INTO assets(id,project_id,name,media_type,content) VALUES (?,?,?,?,?)",asset.id,asset.projectId,asset.name,asset.mediaType,asset.content);
    }
    @Transactional public Document importProject(JsonNode original,List<Asset> assets){
        validator.validate(original);var project=(ObjectNode)original.deepCopy();String id=UUID.randomUUID().toString();project.put("id",id);String now=java.time.Instant.now().toString();project.put("createdAt",now);project.put("updatedAt",now);
        Map<String,String> replacements=new HashMap<>();for(var a:assets){ProjectValidator.require(replacements.put(a.id,UUID.randomUUID().toString())==null,"Asset duplicato nell’importazione");}
        for(var f:project.path("building").path("floors")){var fp=f.path("floorplan");if(fp.isObject())((ObjectNode)fp).put("originalAssetId",mapped(fp.path("originalAssetId").asText(),replacements));for(var o:f.path("objects"))if(o.hasNonNull("assetId"))((ObjectNode)o).put("assetId",mapped(o.path("assetId").asText(),replacements));}
        var result=save(id,project,null,true);for(var a:assets)saveAsset(new Asset(replacements.get(a.id),id,a.name,a.mediaType,a.content));return result;
    }
    private static String mapped(String id,Map<String,String> replacements){if(!replacements.containsKey(id))throw ApiException.invalid("Manca un file originale o un modello GLB referenziato. Il progetto locale resta disponibile.");return replacements.get(id);}
    public JsonNode read(String text){try{return json.readTree(text);}catch(IOException e){throw ApiException.invalid("JSON non valido");}}
}
