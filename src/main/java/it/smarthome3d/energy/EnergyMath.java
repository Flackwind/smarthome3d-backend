package it.smarthome3d.energy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Units: rate kWh/h or Smc/h; counters kWh or Smc. No network or persistence here. */
public final class EnergyMath {
    public static final ZoneId ZONE=ZoneId.of("Europe/Rome");
    public record Point(String name,String utility,String quality,boolean counter,double value) {}
    public record Frame(Map<String,Point> points,List<String> issues,Set<String> included) {}
    public record Bucket(long hour,String source,String name,String utility,String quality,double amount,double cost,double unpriced,double seconds) {}
    private EnergyMath() {}
    public static Frame frame(EnergyConfig c,JsonNode states){
        var entities=new HashMap<String,JsonNode>();for(var s:states)entities.put(s.path("entity_id").asText(),s);
        var points=new LinkedHashMap<String,Point>();var issues=new ArrayList<String>();var included=new HashSet<String>();
        for(var d:c.devices()){
            Double value;
            if(d.mode().equals("nominal")){
                var entity=entities.get(d.entityId());String state=valid(entity)?entity.path("state").asText():null;
                if(state!=null&&entity.path("attributes").path("hvac_action").isTextual())state=entity.path("attributes").path("hvac_action").asText();
                value=state==null?null:(d.activeStates().contains(state)?d.watts():d.standbyWatts())/1000;
            }else value=measure(entities.get(d.sensorId()),d.mode(),false,c.gas().volumeCorrection());
            if(value!=null)points.put(d.id(),new Point(d.name(),"electricity",d.mode().equals("nominal")?"nominal":"sensor",d.mode().equals("counter"),value));else issues.add("Dati mancanti o unità non compatibile: "+d.name());
            if(d.includeInTotal())included.add(d.id());
        }
        if(!c.mains().mode().equals("none")){
            included.clear();included.add("$electricity");Double value=measure(entities.get(c.mains().sensorId()),c.mains().mode(),false,c.gas().volumeCorrection());
            if(value!=null)points.put("$electricity",new Point("Totale casa","electricity","sensor",c.mains().mode().equals("counter"),value));else issues.add("Contatore generale non disponibile o unità non compatibile");
        }
        var g=c.gas();
        if(!g.mode().equals("off")){
            Double value=g.mode().equals("counter")?measure(entities.get(g.sensorId()),"counter",true,g.volumeCorrection()):thermalRate(g,entities);
            if(value!=null)points.put("$gas",new Point(g.mode().equals("thermal")?"Stima termica casa":"Contatore gas","gas",g.mode().equals("thermal")?"thermal":"sensor",g.mode().equals("counter"),value));else issues.add("Gas: temperatura, valvola o contatore non disponibile; intervallo escluso");
        }
        return new Frame(Map.copyOf(points),List.copyOf(issues),Set.copyOf(included));
    }
    private static boolean valid(JsonNode n){return n!=null&&!Set.of("unavailable","unknown","none","").contains(n.path("state").asText());}
    private static Double numeric(JsonNode n){try{double v=n.isNumber()?n.asDouble():Double.parseDouble(n.asText());return Double.isFinite(v)?v:null;}catch(Exception e){return null;}}
    private static Double measure(JsonNode state,String mode,boolean gas,double correction){
        if(!valid(state))return null;Double value=numeric(state.path("state"));if(value==null||value<0||value>1e12)return null;
        String unit=state.path("attributes").path("unit_of_measurement").asText();double factor;
        if(gas){factor=switch(unit){case "Smc","smc"->1;case "m³","m3"->correction;default->Double.NaN;};}
        else if(mode.equals("power")){factor=switch(unit){case "W"->.001;case "kW"->1;default->Double.NaN;};if(value*factor>1000)return null;}
        else factor=switch(unit){case "Wh"->.001;case "kWh"->1;case "MWh"->1000;default->Double.NaN;};
        return Double.isFinite(factor)?value*factor:null;
    }
    private static Double temperature(JsonNode entity,boolean outdoor){
        if(!valid(entity))return null;var a=entity.path("attributes");
        var raw=entity.path("entity_id").asText().startsWith("sensor.")?entity.path("state"):a.path(outdoor?"temperature":"current_temperature");Double value=numeric(raw);if(value==null)return null;
        String unit=a.path("temperature_unit").asText(a.path("unit_of_measurement").asText());
        value=switch(unit){case "°C","C"->value;case "°F","F"->(value-32)*5/9;case "K"->value-273.15;default->null;};
        return value!=null&&value>=-80&&value<=60?value:null;
    }
    static Double thermalRate(EnergyConfig.Gas g,Map<String,JsonNode> entities){
        double base=g.baseSmcPerDay()/24;if(!g.heatingEnabled())return base;
        Double outdoor=g.outdoorId().isEmpty()?Double.valueOf(g.outdoorTemperature()):temperature(entities.get(g.outdoorId()),true);if(outdoor==null)return null;
        double radiators=g.zones().stream().mapToInt(EnergyConfig.Zone::radiators).sum(),watts=0;
        for(var z:g.zones()){
            var climate=z.climateId().isEmpty()?null:entities.get(z.climateId());if(!z.climateId().isEmpty()&&!valid(climate))return null;
            Double indoor=!z.indoorId().isEmpty()?temperature(entities.get(z.indoorId()),false):climate!=null?temperature(climate,false):Double.valueOf(z.indoorTemperature());if(indoor==null)return null;
            Double target=climate==null?Double.valueOf(z.targetTemperature()):temperatureTarget(climate);if(target==null)return null;
            boolean heating=indoor<=target+.2;
            if(climate!=null){String action=climate.path("attributes").path("hvac_action").asText();if(!action.isEmpty()){if(Set.of("unknown","unavailable").contains(action))return null;heating=action.equals("heating");}if(climate.path("state").asText().equals("off"))heating=false;}
            if(heating)watts+=g.heatLoss()*z.radiators()/radiators*Math.max(0,indoor-outdoor);
        }
        return base+watts/1000/(g.efficiency()*g.calorificValue());
    }
    private static Double temperatureTarget(JsonNode climate){
        if(!climate.path("attributes").isObject())return null;
        var copy=climate.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)copy.path("attributes")).set("current_temperature",climate.path("attributes").path("temperature"));return temperature(copy,false);
    }
    public static List<Bucket> integrate(EnergyConfig config,Frame before,Frame after,Instant start,Instant end){
        double duration=Duration.between(start,end).toMillis()/1000.;if(duration<=0||duration>300)return List.of();
        var result=new ArrayList<Bucket>();var good=new HashSet<String>();
        for(var entry:after.points.entrySet()){
            String id=entry.getKey();Point b=entry.getValue(),a=before.points.get(id);
            if(a==null||a.counter!=b.counter||!a.quality.equals(b.quality))continue;
            double delta=b.counter?b.value-a.value:0;
            // Reset/rollover or implausible interval: establish a new baseline, never charge it.
            if(b.counter&&(delta<0||delta>(b.utility.equals("gas")?100:1000)*duration/3600))continue;
            good.add(id);
            for(Instant cursor=start;cursor.isBefore(end);){
                Instant next=cursor.truncatedTo(ChronoUnit.MINUTES).plus(1,ChronoUnit.MINUTES);if(next.isAfter(end))next=end;
                double seconds=Duration.between(cursor,next).toMillis()/1000.;
                double t0=Duration.between(start,cursor).toMillis()/1000./duration,t1=Duration.between(start,next).toMillis()/1000./duration;
                double amount=b.counter?delta*seconds/duration:(a.value+(b.value-a.value)*(t0+t1)/2)*seconds/3600;
                Double price=price(config,cursor,b.utility);double cost=price==null?0:amount*price;
                result.add(new Bucket(cursor.truncatedTo(ChronoUnit.HOURS).getEpochSecond(),id,b.name,b.utility,b.quality,amount,cost,price==null?amount:0,seconds));cursor=next;
            }
        }
        if(config.mains().mode().equals("none")&&!after.included.isEmpty()&&before.included.equals(after.included)&&good.containsAll(after.included)){
            String quality=after.included.stream().anyMatch(id->after.points.get(id).quality.equals("nominal"))?"nominal":"sensor";
            var grouped=new LinkedHashMap<Long,List<Bucket>>();for(var row:result)if(after.included.contains(row.source))grouped.computeIfAbsent(row.hour,k->new ArrayList<>()).add(row);
            for(var e:grouped.entrySet()){
                var rows=e.getValue();double seconds=rows.stream().mapToDouble(Bucket::seconds).sum()/after.included.size();
                result.add(new Bucket(e.getKey(),"$electricity","Dispositivi inclusi","electricity",quality,rows.stream().mapToDouble(Bucket::amount).sum(),rows.stream().mapToDouble(Bucket::cost).sum(),rows.stream().mapToDouble(Bucket::unpriced).sum(),seconds));
            }
        }
        return result;
    }
    public static Double price(EnergyConfig c,Instant time,String utility){
        var local=time.atZone(ZONE);var contract=c.contracts().stream().filter(v->!LocalDate.parse(v.from()).isAfter(local.toLocalDate())).max(Comparator.comparing(EnergyConfig.Contract::from)).orElse(null);if(contract==null)return null;
        if(utility.equals("gas"))return contract.gas();
        int index=switch(contract.electricityMode()){case "single"->0;case "hourly"->local.getHour();default->band(local);};return contract.electricity().get(index);
    }
    /** F1/F2/F3 Italian national calendar, including Easter Monday. */
    public static int band(ZonedDateTime time){
        LocalDate date=time.toLocalDate();int hour=time.getHour(),day=date.getDayOfWeek().getValue();
        String md=String.format(Locale.ROOT,"%02d-%02d",date.getMonthValue(),date.getDayOfMonth());
        boolean holiday=Set.of("01-01","01-06","04-25","05-01","06-02","08-15","11-01","12-08","12-25","12-26").contains(md)||date.equals(easter(date.getYear()).plusDays(1));
        if(day==7||holiday||hour<7||hour>=23)return 2;if(day<=5&&hour>=8&&hour<19)return 0;return 1;
    }
    private static LocalDate easter(int y){int a=y%19,b=y/100,c=y%100,d=b/4,e=b%4,f=(b+8)/25,g=(b-f+1)/3,h=(19*a+b-d-g+15)%30,i=c/4,k=c%4,l=(32+2*e+2*i-h-k)%7,m=(a+11*h+22*l)/451,n=h+l-7*m+114;return LocalDate.of(y,n/31,n%31+1);}
}
