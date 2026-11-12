package it.smarthome3d.energy;

import com.fasterxml.jackson.databind.*;
import it.smarthome3d.api.ApiException;
import java.time.LocalDate;
import java.util.*;
import static it.smarthome3d.storage.ProjectValidator.require;

/** Independent, versioned configuration contract; no provider or renderer dependencies. */
public record EnergyConfig(int version,boolean enabled,List<Contract> contracts,List<Device> devices,Mains mains,Gas gas) {
    public record Contract(String from,String name,String electricityMode,List<Double> electricity,Double gas) {}
    public record Device(String id,String name,String entityId,String mode,String sensorId,double watts,double standbyWatts,List<String> activeStates,boolean includeInTotal) {}
    public record Mains(String mode,String sensorId) {}
    public record Gas(String mode,String sensorId,double volumeCorrection,double heatLoss,double efficiency,double calorificValue,String outdoorId,double outdoorTemperature,boolean heatingEnabled,double baseSmcPerDay,List<Zone> zones) {}
    public record Zone(String id,String name,int radiators,String climateId,String indoorId,double indoorTemperature,double targetTemperature) {}
    public static EnergyConfig parse(JsonNode node,ObjectMapper json){
        try{
            require(node.isObject()&&node.path("enabled").isBoolean(),"Configurazione consumi non valida");
            var strict=json.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
            var e=strict.treeToValue(node,EnergyConfig.class);e.validate();return e;
        }catch(ApiException e){throw e;}catch(Exception e){throw ApiException.invalid("Configurazione consumi non valida");}
    }
    public void validate(){
        require(version==1,"Versione consumi non supportata");list(contracts,100);list(devices,500);require(mains!=null&&gas!=null,"Configurazione consumi incompleta");
        var dates=new HashSet<String>();
        for(var c:contracts){label(c.name);require(dates.add(c.from)&&LocalDate.parse(c.from).toString().equals(c.from),"Decorrenza contratto non valida o duplicata");choice(c.electricityMode,"single","bands","hourly");list(c.electricity,24);require(c.electricity.size()==(c.electricityMode.equals("single")?1:c.electricityMode.equals("bands")?3:24),"Tariffe luce incomplete");for(var rate:c.electricity)range(rate,0,100);if(c.gas!=null)range(c.gas,0,100);}
        choice(mains.mode,"none","power","counter");entity(mains.sensorId,!mains.mode.equals("none"));
        var ids=new HashSet<String>();var sources=new HashSet<String>();
        for(var d:devices){label(d.id);require(d.id.matches("[a-zA-Z0-9_-]+")&&ids.add(d.id),"Profilo consumo duplicato o non valido");label(d.name);entity(d.entityId,true);choice(d.mode,"nominal","power","counter");entity(d.sensorId,!d.mode.equals("nominal"));range(d.watts,0,1_000_000);range(d.standbyWatts,0,1_000_000);list(d.activeStates,30);for(var state:d.activeStates){label(state);require(state.length()<=80,"Stato troppo lungo");}if(d.includeInTotal)require(sources.add(d.mode.equals("nominal")?d.entityId:d.sensorId),"Sorgente inclusa due volte nel totale");}
        choice(gas.mode,"off","counter","thermal");entity(gas.sensorId,gas.mode.equals("counter"));entity(gas.outdoorId,false);range(gas.volumeCorrection,.1,10);range(gas.heatLoss,0,100000);range(gas.efficiency,.01,1);range(gas.calorificValue,1,30);range(gas.outdoorTemperature,-80,60);range(gas.baseSmcPerDay,0,1000);list(gas.zones,100);
        ids.clear();for(var z:gas.zones){label(z.id);label(z.name);require(ids.add(z.id)&&z.radiators>=1&&z.radiators<=100,"Zona termica non valida");entity(z.climateId,false);entity(z.indoorId,false);range(z.indoorTemperature,-20,60);range(z.targetTemperature,5,35);}
        if(gas.mode.equals("thermal"))require(gas.heatLoss>0&&!gas.zones.isEmpty(),"La stima gas richiede dispersione H e almeno una zona");
    }
    private static void list(List<?> value,int max){require(value!=null&&value.size()<=max&&value.stream().noneMatch(Objects::isNull),"Lista consumi non valida");}
    private static void label(String value){require(value!=null&&!value.isBlank()&&value.length()<=200,"Nome consumi non valido");}
    private static void choice(String value,String... allowed){require(value!=null&&Arrays.asList(allowed).contains(value),"Modalità consumi non valida");}
    private static void entity(String value,boolean required){require(value!=null&&((value.isEmpty()&&!required)||value.matches("[a-z_]+\\.[a-z0-9_]+")),"Entità consumi non valida");}
    private static void range(Double value,double min,double max){require(value!=null&&Double.isFinite(value)&&value>=min&&value<=max,"Valore consumi fuori intervallo");}
}
