package it.smarthome3d.energy;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.smarthome3d.auth.OwnerSessions;
import it.smarthome3d.ha.*;
import it.smarthome3d.storage.ProjectStorage;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class EnergyCollector {
    public record Status(String lastSample,String message,List<String> issues) {}
    private record Baseline(String config,Instant time,EnergyMath.Frame frame) {}
    private final OwnerSessions sessions;private final HaClient ha;private final HaProfileConnection connection;private final ProjectStorage projects;private final EnergyStorage storage;private final ObjectMapper json;
    private final Map<String,Baseline> baselines=new HashMap<>();private final Map<String,Status> statuses=new HashMap<>();private Instant lastAttempt=Instant.EPOCH;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public EnergyCollector(OwnerSessions sessions,HaClient ha,HaProfileConnection connection,ProjectStorage projects,EnergyStorage storage,ObjectMapper json){this(sessions,ha,connection,projects,storage,json,Clock.systemUTC());}
    public EnergyCollector(OwnerSessions sessions,HaClient ha,HaProfileConnection connection,ProjectStorage projects,EnergyStorage storage,ObjectMapper json,Clock clock){this.sessions=sessions;this.ha=ha;this.connection=connection;this.projects=projects;this.storage=storage;this.json=json;this.clock=clock;}
    public synchronized Status status(String id){
        var p=projects.get(id).project();if(!p.path("energy").path("enabled").asBoolean())return new Status("","Raccolta disattivata",List.of());
        if(sessions.key()==null)return new Status("","Raccolta in pausa: accedi al server per sbloccare Home Assistant",List.of());
        return statuses.getOrDefault(id,new Status("","In attesa della prima rilevazione",List.of()));
    }
    @Scheduled(fixedDelayString="${sh3d.energy-interval-ms:60000}",initialDelayString="${sh3d.energy-interval-ms:60000}")
    public synchronized void collect(){
        Instant attempt=clock.instant();if(attempt.isBefore(lastAttempt.plusSeconds(10)))return;lastAttempt=attempt;
        var documents=projects.list().stream().filter(d->d.project().path("energy").path("enabled").asBoolean()).toList();
        var active=new HashSet<String>();for(var d:documents)active.add(d.project().path("id").asText());baselines.keySet().retainAll(active);statuses.keySet().retainAll(active);
        if(documents.isEmpty())return;var key=sessions.key();
        if(key==null){baselines.clear();return;}
        try{
            var states=ha.states(connection.access(key));Instant now=clock.instant();
            for(var d:documents){String id=d.project().path("id").asText();var node=d.project().path("energy");var config=EnergyConfig.parse(node,json);var frame=EnergyMath.frame(config,states);var before=baselines.get(id);
                boolean continuous=before!=null&&before.config.equals(node.toString())&&java.time.Duration.between(before.time,now).toSeconds()<=300;
                if(continuous)storage.record(id,EnergyMath.integrate(config,before.frame,frame,before.time,now));
                baselines.put(id,new Baseline(node.toString(),now,frame));statuses.put(id,new Status(now.toString(),continuous?"Raccolta attiva · intervalli osservati":"Prima lettura acquisita; il prossimo campione avvia lo storico",frame.issues()));
            }
        }catch(Exception e){for(var d:documents){String id=d.project().path("id").asText();var previous=statuses.get(id);statuses.put(id,new Status(previous==null?"":previous.lastSample,"Raccolta sospesa: verifica connessione e accesso a Home Assistant",List.of()));}baselines.clear();}
    }
}
