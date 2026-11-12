package it.smarthome3d.energy;

import com.fasterxml.jackson.databind.*;
import it.smarthome3d.storage.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static it.smarthome3d.storage.ProjectValidator.require;

@Service
public class EnergyStorage {
    public record Manual(String month,String utility,double amount,Double cost) {}
    public record Total(double amount,Double cost,double knownCost,double unpriced,double observedHours,Set<String> qualities) {}
    public record Source(String id,String name,Total total) {}
    public record Month(String month,Total electricity,Total gas,List<Source> devices) {}
    public record Archive(int version,List<EnergyMath.Bucket> buckets,List<Manual> monthly) {}
    private final JdbcTemplate db;private final ProjectStorage projects;private final ObjectMapper json;
    public EnergyStorage(JdbcTemplate db,ProjectStorage projects,ObjectMapper json){this.db=db;this.projects=projects;this.json=json;}
    @Transactional public synchronized void record(String project,List<EnergyMath.Bucket> rows){
        projects.get(project);
        for(var b:rows){
            validate(b);
            int n=db.update("UPDATE energy_buckets SET source_name=?,amount=amount+?,cost=cost+?,unpriced=unpriced+?,observed_seconds=observed_seconds+? WHERE project_id=? AND hour_utc=? AND source_id=? AND quality=?",b.name(),b.amount(),b.cost(),b.unpriced(),b.seconds(),project,b.hour(),b.source(),b.quality());
            if(n==0)db.update("INSERT INTO energy_buckets(project_id,hour_utc,source_id,source_name,utility,quality,amount,cost,unpriced,observed_seconds) VALUES (?,?,?,?,?,?,?,?,?,?)",project,b.hour(),b.source(),b.name(),b.utility(),b.quality(),b.amount(),b.cost(),b.unpriced(),b.seconds());
        }
    }
    @Transactional public synchronized void manual(String project,Manual value){
        projects.get(project);validate(value);db.update("DELETE FROM energy_monthly WHERE project_id=? AND month_id=? AND utility=?",project,value.month,value.utility);
        db.update("INSERT INTO energy_monthly(project_id,month_id,utility,amount,cost) VALUES (?,?,?,?,?)",project,value.month,value.utility,value.amount,value.cost);
    }
    public void removeManual(String project,String month,String utility){projects.get(project);validate(new Manual(month,utility,0,null));db.update("DELETE FROM energy_monthly WHERE project_id=? AND month_id=? AND utility=?",project,month,utility);}
    public List<Month> year(String project,int year){
        projects.get(project);require(year>=2000&&year<=2200,"Anno non valido");
        long start=LocalDate.of(year,1,1).atStartOfDay(EnergyMath.ZONE).toEpochSecond(),end=LocalDate.of(year+1,1,1).atStartOfDay(EnergyMath.ZONE).toEpochSecond();
        var rows=buckets(project,start,end);var manuals=monthly(project);var result=new ArrayList<Month>();
        for(int m=1;m<=12;m++){
            String month=YearMonth.of(year,m).toString();var groups=new LinkedHashMap<String,List<EnergyMath.Bucket>>();for(var b:rows)if(YearMonth.from(Instant.ofEpochSecond(b.hour()).atZone(EnergyMath.ZONE)).toString().equals(month))groups.computeIfAbsent(b.source(),k->new ArrayList<>()).add(b);
            var sources=new ArrayList<Source>();for(var e:groups.entrySet())if(!e.getKey().startsWith("$"))sources.add(new Source(e.getKey(),e.getValue().getLast().name(),total(e.getValue())));
            sources.sort(Comparator.comparingDouble((Source s)->s.total.amount).reversed());
            Total electricity=total(groups.get("$electricity")),gas=total(groups.get("$gas"));
            for(var value:manuals)if(value.month.equals(month)){var t=new Total(value.amount,value.cost,value.cost==null?0:value.cost,value.cost==null?value.amount:0,0,Set.of("manual"));if(value.utility.equals("gas"))gas=t;else electricity=t;}
            result.add(new Month(month,electricity,gas,sources));
        }
        return result;
    }
    private static Total total(List<EnergyMath.Bucket> rows){
        if(rows==null||rows.isEmpty())return null;double amount=0,cost=0,unpriced=0,seconds=0;var qualities=new HashSet<String>();
        for(var b:rows){amount+=b.amount();cost+=b.cost();unpriced+=b.unpriced();seconds+=b.seconds();qualities.add(b.quality());}
        return new Total(amount,unpriced>1e-9?null:cost,cost,unpriced,seconds/3600,Set.copyOf(qualities));
    }
    private List<EnergyMath.Bucket> buckets(String project,long start,long end){return db.query("SELECT hour_utc,source_id,source_name,utility,quality,amount,cost,unpriced,observed_seconds FROM energy_buckets WHERE project_id=? AND hour_utc>=? AND hour_utc<? ORDER BY hour_utc,source_id",(rs,n)->new EnergyMath.Bucket(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getDouble(6),rs.getDouble(7),rs.getDouble(8),rs.getDouble(9)),project,start,end);}
    public List<Manual> monthly(String project){return db.query("SELECT month_id,utility,amount,cost FROM energy_monthly WHERE project_id=? ORDER BY month_id,utility",(rs,n)->new Manual(rs.getString(1),rs.getString(2),rs.getDouble(3),rs.getObject(4)==null?null:rs.getDouble(4)),project);}
    public Archive archive(String project){return new Archive(1,buckets(project,0,Long.MAX_VALUE),monthly(project));}
    @Transactional public void restore(String project,JsonNode node){
        ProjectValidator.noSecrets(node);Archive archive;
        try{archive=json.treeToValue(node,Archive.class);}catch(Exception e){throw it.smarthome3d.api.ApiException.invalid("Storico consumi non valido");}
        require(archive!=null&&archive.version==1&&archive.buckets!=null&&archive.monthly!=null&&archive.buckets.size()<=500000&&archive.monthly.size()<=5000,"Storico consumi troppo grande o non valido");
        var keys=new HashSet<String>();for(var b:archive.buckets){validate(b);require(keys.add(b.hour()+"/"+b.source()+"/"+b.quality()),"Rilevazione duplicata nel backup");}record(project,archive.buckets);
        keys.clear();for(var m:archive.monthly){validate(m);require(keys.add(m.month+"/"+m.utility),"Consuntivo duplicato nel backup");manual(project,m);}
    }
    private static void validate(Manual m){require(m!=null&&m.month!=null&&m.month.matches("[0-9]{4}-[0-9]{2}")&&m.utility!=null&&Set.of("electricity","gas").contains(m.utility),"Consuntivo non valido");try{int year=YearMonth.parse(m.month).getYear();require(year>=2000&&year<=2200,"Anno non valido");}catch(java.time.DateTimeException e){throw it.smarthome3d.api.ApiException.invalid("Mese non valido");}range(m.amount,1e9);if(m.cost!=null)range(m.cost,1e9);}
    private static void validate(EnergyMath.Bucket b){
        require(b!=null&&b.hour()>=946684800L&&b.hour()<7289654400L&&b.hour()%3600==0&&b.source()!=null&&b.source().matches("(?:[a-zA-Z0-9_-]{1,200}|\\$electricity|\\$gas)")&&b.name()!=null&&!b.name().isBlank()&&b.name().length()<=200,"Rilevazione non valida");
        require(b.utility()!=null&&Set.of("electricity","gas").contains(b.utility())&&b.utility().equals(b.source().equals("$gas")?"gas":"electricity")&&b.quality()!=null&&Set.of("sensor","nominal","thermal").contains(b.quality()),"Tipo rilevazione non valido");
        range(b.amount(),1e9);range(b.cost(),1e11);range(b.unpriced(),b.amount()+1e-9);range(b.seconds(),3600);
    }
    private static void range(double value,double max){require(Double.isFinite(value)&&value>=0&&value<=max,"Valore consumi non valido");}
}
