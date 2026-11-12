package it.smarthome3d.storage;

import com.fasterxml.jackson.databind.JsonNode;
import it.smarthome3d.api.ApiException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Independent server boundary for the versioned JSON contract, without frontend imports. */
@Component
public class ProjectValidator {
    public static final int MAX_DOCUMENT=35_000_000, MAX_ASSET=50_000_000, MAX_BUNDLE=150_000_000;
    private static final Set<String> FURNITURE=Set.of("bulb","floor-lamp","ceiling-light","led-strip","radiator","sensor","vacuum","outlet","switch","sofa","table","bed","plant");
    public static void require(boolean condition,String message) { if(!condition)throw ApiException.invalid(message); }
    public static String key(String value) {require(value!=null&&value.matches("[a-zA-Z0-9_-]{1,200}"),"Identificatore non valido");return value;}
    private static boolean label(JsonNode value) {return value!=null&&value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=200;}
    private static String text(JsonNode node,String key) { require(label(node.get(key)),"Campo mancante o non valido: "+key);return node.get(key).asText(); }
    private static double number(JsonNode node,String key,double min,double max) {var value=node.get(key);require(value!=null&&value.isNumber()&&Double.isFinite(value.asDouble())&&value.asDouble()>=min&&value.asDouble()<=max,"Misura non valida: "+key);return value.asDouble();}
    private static void vector(JsonNode v,boolean positive) {require(v!=null&&v.isObject(),"Vettore mancante");for(var axis:List.of("x","y","z"))number(v,axis,positive?.001:-10000,10000);}
    private static void material(JsonNode m) {require(m!=null&&m.isObject()&&m.path("color").asText().matches("#[a-fA-F0-9]{6}"),"Materiale non valido");number(m,"roughness",0,1);}
    private static void list(JsonNode node,String field,int min,int max) {require(node.path(field).isArray()&&node.path(field).size()>=min&&node.path(field).size()<=max,"Lista non valida: "+field);}
    private static String unique(JsonNode node,Set<String> ids) {String id=text(node,"id");require(ids.add(id),"Identificatore duplicato");return id;}
    public static void noSecrets(JsonNode node) {
        if(node.isObject())node.properties().forEach(entry->{require(!entry.getKey().matches("(?i).*(token|password|secret|credential|authorization).*"),"Il documento contiene un campo riservato");noSecrets(entry.getValue());});
        else if(node.isArray())node.forEach(ProjectValidator::noSecrets);
    }
    public void validate(JsonNode p) {
        require(p!=null&&p.isObject()&&p.path("schemaVersion").isIntegralNumber()&&p.path("schemaVersion").asInt(-1)==1,"Versione progetto non supportata");noSecrets(p);
        if(p.has("energy"))it.smarthome3d.energy.EnergyConfig.parse(p.path("energy"),new com.fasterxml.jackson.databind.ObjectMapper());
        if(p.has("dashboard")){
            var dashboard=p.path("dashboard");require(dashboard.isObject()&&dashboard.path("useWeatherFallback").isBoolean(),"Configurazione temperature dashboard non valida");
            for(var key:List.of("indoorTemperatureEntityId","outdoorTemperatureEntityId","outdoorWeatherEntityId")){
                var value=dashboard.path(key);String domain=key.equals("indoorTemperatureEntityId")?"sensor|climate":key.equals("outdoorWeatherEntityId")?"weather":"sensor";
                require(value.isTextual()&&(value.asText().isEmpty()||value.asText().matches("("+domain+")\\.[a-z0-9_]+")),"Sorgente temperatura dashboard non valida");
            }
        }
        Set<String> ids=new HashSet<>(),objects=new HashSet<>();key(unique(p,ids));text(p,"name");
        for(var date:List.of("createdAt","updatedAt")){try{Instant.parse(p.path(date).asText());}catch(Exception e){throw ApiException.invalid("Data progetto non valida");}}
        var building=p.path("building");unique(building,ids);text(building,"name");list(building,"floors",1,20);list(p,"bindings",0,40000);
        var scene=p.path("scene");require(Set.of("low","medium","high").contains(scene.path("quality").asText())&&Set.of("light","dark").contains(scene.path("theme").asText())&&scene.path("showWalls").isBoolean(),"Impostazioni scena non valide");
        require(!scene.has("presentation")||Set.of("map","model").contains(scene.path("presentation").asText()),"Presentazione non valida");vector(scene.path("camera").path("position"),false);vector(scene.path("camera").path("target"),false);
        for(var floor:building.path("floors")){
            unique(floor,ids);text(floor,"name");number(floor,"elevation",-100,100);number(floor,"ceilingHeight",.5,10);
            for(var field:List.of("vertices","walls","rooms","objects"))list(floor,field,0,2000);
            Map<String,JsonNode> vertices=new HashMap<>(),walls=new HashMap<>();Set<String> positions=new HashSet<>(),pairs=new HashSet<>(),rooms=new HashSet<>();
            for(var v:floor.path("vertices")){String id=unique(v,ids);vertices.put(id,v);double x=number(v,"x",-10000,10000),y=number(v,"y",-10000,10000);require(positions.add(String.format(Locale.ROOT,"%.6f/%.6f",x,y)),"Vertici sovrapposti");}
            for(var w:floor.path("walls")){
                String id=unique(w,ids);walls.put(id,w);String a=text(w,"startId"),b=text(w,"endId");require(vertices.containsKey(a)&&vertices.containsKey(b),"Vertice del muro mancante");
                double length=distance(vertices.get(a),vertices.get(b));require(length>=.05&&pairs.add(a.compareTo(b)<0?a+"/"+b:b+"/"+a),"Muro degenere o duplicato");
                number(w,"thickness",.03,1);double height=number(w,"height",.5,10);material(w.path("material"));list(w,"openings",0,50);
                var openings=new ArrayList<JsonNode>();w.path("openings").forEach(openings::add);openings.sort(Comparator.comparingDouble(o->o.path("offset").asDouble()));double end=0;
                for(var o:openings){unique(o,ids);require(Set.of("door","window").contains(o.path("type").asText()),"Tipo apertura non valido");double offset=number(o,"offset",0,10000),width=number(o,"width",.1,10000),h=number(o,"height",.1,10000),sill=number(o,"sill",0,10000);require(offset+1e-6>=end&&offset+width<=length+1e-6&&sill+h<=height+1e-6,"Apertura sovrapposta o fuori muro");end=offset+width;}
            }
            var wallList=new ArrayList<>(walls.values());
            for(int i=0;i<wallList.size();i++)for(int j=i+1;j<wallList.size();j++){var w=wallList.get(i);var v=wallList.get(j);var a=vertices.get(w.path("startId").asText());var b=vertices.get(w.path("endId").asText());var c=vertices.get(v.path("startId").asText());var d=vertices.get(v.path("endId").asText());require(!intersect(a,b,c,d)&&!interior(a,c,d)&&!interior(b,c,d)&&!interior(c,a,b)&&!interior(d,a,b),"Muri intersecati o giunzione non divisa");}
            List<List<JsonNode>> polygons=new ArrayList<>();
            for(var room:floor.path("rooms")){
                rooms.add(unique(room,ids));text(room,"name");material(room.path("material"));list(room,"vertexIds",3,2000);list(room,"wallIds",3,2000);
                require(room.path("vertexIds").size()==room.path("wallIds").size(),"Contorno stanza non valido");var points=new ArrayList<JsonNode>();Set<String> refs=new HashSet<>();
                for(var ref:room.path("vertexIds")){require(ref.isTextual()&&vertices.containsKey(ref.asText())&&refs.add(ref.asText()),"Vertice stanza mancante o duplicato");points.add(vertices.get(ref.asText()));}
                for(var ref:room.path("wallIds"))require(walls.containsKey(ref.asText()),"Muro stanza mancante");
                double area=0;for(int i=0;i<points.size();i++){var a=points.get(i);var b=points.get((i+1)%points.size());area+=a.path("x").asDouble()*b.path("y").asDouble()-b.path("x").asDouble()*a.path("y").asDouble();String va=room.path("vertexIds").get(i).asText(),vb=room.path("vertexIds").get((i+1)%points.size()).asText();boolean edge=false;for(var ref:room.path("wallIds")){var w=walls.get(ref.asText());if(Set.of(w.path("startId").asText(),w.path("endId").asText()).containsAll(List.of(va,vb)))edge=true;}require(edge,"Contorno stanza incoerente");}require(Math.abs(area)>=.02,"Stanza degenere");
            }
            for(var room:floor.path("rooms")){var points=new ArrayList<JsonNode>();room.path("vertexIds").forEach(ref->points.add(vertices.get(ref.asText())));for(int i=0;i<points.size();i++)for(int j=i+2;j<points.size();j++)require(!intersect(points.get(i),points.get((i+1)%points.size()),points.get(j),points.get((j+1)%points.size())),"Stanza auto-intersecata");polygons.add(points);}
            for(int i=0;i<polygons.size();i++)for(int j=i+1;j<polygons.size();j++){var a=polygons.get(i);var b=polygons.get(j);require(a.stream().noneMatch(v->!boundary(v,b)&&inside(v,b))&&b.stream().noneMatch(v->!boundary(v,a)&&inside(v,a)),"Stanze sovrapposte o con fori non supportati");}
            for(var o:floor.path("objects")){objects.add(unique(o,ids));text(o,"name");require(FURNITURE.contains(o.path("type").asText()),"Tipo oggetto non valido");for(var v:List.of("position","rotation","scale","dimensions"))vector(o.path(v),v.equals("scale")||v.equals("dimensions"));material(o.path("material"));require(!o.hasNonNull("roomId")||rooms.contains(o.path("roomId").asText()),"Stanza oggetto mancante");if(o.hasNonNull("assetId"))key(text(o,"assetId"));}
            if(floor.hasNonNull("floorplan")){var ref=floor.path("floorplan");unique(ref,ids);text(ref,"name");key(text(ref,"originalAssetId"));String data=ref.path("dataUrl").asText();require(data.length()<=30_000_000&&data.matches("(?s)^data:image/(png|jpeg);base64,.*"),"Anteprima non valida");number(ref,"width",1,10000);number(ref,"height",1,10000);number(ref,"metersPerPixel",.00001,1);number(ref,"rotation",-360,360);number(ref,"opacity",0,1);number(ref.path("origin"),"x",-10000,10000);number(ref.path("origin"),"y",-10000,10000);require(!ref.has("calibrated")||ref.path("calibrated").isBoolean(),"Calibrazione non valida");}
        }
        Set<String> bound=new HashSet<>();
        for(var b:p.path("bindings")){unique(b,ids);String object=text(b,"objectId");require(objects.contains(object)&&bound.add(object),"Associazione oggetto non valida");require(Set.of("mock","home-assistant").contains(b.path("provider").asText()),"Provider non valido");entity(b.path("entityId"));list(b,"relatedEntityIds",0,2000);b.path("relatedEntityIds").forEach(ProjectValidator::entity);
            if(b.has("positionCalibration")){var c=b.path("positionCalibration");for(var field:List.of("sourceA","sourceB","homeA","homeB")){number(c.path(field),"x",-1e9,1e9);number(c.path(field),"y",-1e9,1e9);}require(c.path("flipY").isBoolean()&&distance(c.path("sourceA"),c.path("sourceB"))>.000001&&distance(c.path("homeA"),c.path("homeB"))>.000001,"Allineamento non valido");if(c.has("mapEntityId"))entity(c.path("mapEntityId"));}
        }
    }
    private static double cross(JsonNode a,JsonNode b,JsonNode c){return (b.path("x").asDouble()-a.path("x").asDouble())*(c.path("y").asDouble()-a.path("y").asDouble())-(b.path("y").asDouble()-a.path("y").asDouble())*(c.path("x").asDouble()-a.path("x").asDouble());}
    private static double dot(JsonNode p,JsonNode a,JsonNode b){return (p.path("x").asDouble()-a.path("x").asDouble())*(p.path("x").asDouble()-b.path("x").asDouble())+(p.path("y").asDouble()-a.path("y").asDouble())*(p.path("y").asDouble()-b.path("y").asDouble());}
    private static boolean interior(JsonNode p,JsonNode a,JsonNode b){return Math.abs(cross(a,b,p))<1e-7&&dot(p,a,b)<-1e-7;}
    private static boolean intersect(JsonNode a,JsonNode b,JsonNode c,JsonNode d){return cross(a,b,c)*cross(a,b,d)<-1e-9&&cross(c,d,a)*cross(c,d,b)<-1e-9;}
    private static boolean boundary(JsonNode p,List<JsonNode> points){for(int i=0;i<points.size();i++){var a=points.get(i);var b=points.get((i+1)%points.size());if(Math.abs(cross(a,b,p))<1e-7&&dot(p,a,b)<=1e-7)return true;}return false;}
    private static boolean inside(JsonNode p,List<JsonNode> points){boolean result=false;double x=p.path("x").asDouble(),y=p.path("y").asDouble();for(int i=0,j=points.size()-1;i<points.size();j=i++){var a=points.get(i);var b=points.get(j);double ax=a.path("x").asDouble(),ay=a.path("y").asDouble(),bx=b.path("x").asDouble(),by=b.path("y").asDouble();if((ay>y)!=(by>y)&&x<(bx-ax)*(y-ay)/(by-ay)+ax)result=!result;}return result;}
    private static void entity(JsonNode id) {require(id.isTextual()&&id.asText().matches("[a-z_]+\\.[a-z0-9_]+"),"Entità non valida");}
    private static double distance(JsonNode a,JsonNode b) {return Math.hypot(a.path("x").asDouble()-b.path("x").asDouble(),a.path("y").asDouble()-b.path("y").asDouble());}
}
