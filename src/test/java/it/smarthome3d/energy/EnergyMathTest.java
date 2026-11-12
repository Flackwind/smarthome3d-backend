package it.smarthome3d.energy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EnergyMathTest {
    final ObjectMapper json=new ObjectMapper();
    static EnergyConfig config(String mode,String sensor){return new EnergyConfig(1,true,List.of(new EnergyConfig.Contract("2026-01-01","Fornitura","bands",List.of(.3,.2,.1),.8)),List.of(new EnergyConfig.Device("lamp","Lampada","light.casa",mode,sensor,100,2,List.of("on"),true)),new EnergyConfig.Mains("none",""),gas("off",false,1));}
    static EnergyConfig.Gas gas(String mode,boolean enabled,int radiators){return new EnergyConfig.Gas(mode,"sensor.gas",1.02,200,.9,10.7,"",5,enabled,0,List.of(new EnergyConfig.Zone("z","Casa",radiators,"","",20,20)));}
    EnergyMath.Frame frame(EnergyConfig c,String raw)throws Exception{return EnergyMath.frame(c,json.readTree(raw));}
    double sum(List<EnergyMath.Bucket> rows,String source){return rows.stream().filter(b->b.source().equals(source)).mapToDouble(EnergyMath.Bucket::amount).sum();}
    @Test void integratesPowerAndStandbySplitsRatesAndDoesNotDoubleCountMains()throws Exception{
        var c=config("power","sensor.potenza");var f=frame(c,"""
[{"entity_id":"sensor.potenza","state":"1000","attributes":{"unit_of_measurement":"W"}}]
""");
        Instant start=Instant.parse("2026-11-06T17:59:00Z");var rows=EnergyMath.integrate(c,f,f,start,start.plusSeconds(120));assertThat(sum(rows,"lamp")).isCloseTo(1./30,within(1e-9));assertThat(sum(rows,"$electricity")).isCloseTo(1./30,within(1e-9));assertThat(rows.stream().filter(b->b.source().equals("$electricity")).mapToDouble(EnergyMath.Bucket::cost).sum()).isCloseTo((.3+.2)/60,within(1e-9));
        var mains=new EnergyConfig(c.version(),true,c.contracts(),c.devices(),new EnergyConfig.Mains("power","sensor.potenza"),c.gas());var mf=frame(mains,"""
[{"entity_id":"sensor.potenza","state":"1","attributes":{"unit_of_measurement":"kW"}}]
""");assertThat(sum(EnergyMath.integrate(mains,mf,mf,start,start.plusSeconds(60)),"$electricity")).isCloseTo(1./60,within(1e-9));
        var nominal=config("nominal","");var off=frame(nominal,"""
[{"entity_id":"light.casa","state":"off","attributes":{}}]
""");assertThat(sum(EnergyMath.integrate(nominal,off,off,start,start.plusSeconds(60)),"lamp")).isCloseTo(.002/60,within(1e-9));
    }
    @Test void rejectsUnknownUnitsCounterResetsAndLongGapsWithoutInventingZero()throws Exception{
        var c=config("counter","sensor.energy");var before=frame(c,"""
[{"entity_id":"sensor.energy","state":"1000","attributes":{"unit_of_measurement":"Wh"}}]
""");var after=frame(c,"""
[{"entity_id":"sensor.energy","state":"1010","attributes":{"unit_of_measurement":"Wh"}}]
""");Instant t=Instant.parse("2026-11-06T12:00:00Z");assertThat(sum(EnergyMath.integrate(c,before,after,t,t.plusSeconds(60)),"lamp")).isCloseTo(.01,within(1e-9));assertThat(EnergyMath.integrate(c,after,before,t,t.plusSeconds(60))).isEmpty();assertThat(EnergyMath.integrate(c,before,after,t,t.plusSeconds(301))).isEmpty();
        var missing=frame(c,"""
[{"entity_id":"sensor.energy","state":"unavailable","attributes":{"unit_of_measurement":"kWh"}}]
""");assertThat(EnergyMath.integrate(c,before,missing,t,t.plusSeconds(60))).isEmpty();assertThat(missing.issues()).isNotEmpty();assertThat(frame(c,"""
[{"entity_id":"sensor.energy","state":"15","attributes":{"unit_of_measurement":"W"}}]
""").points()).isEmpty();
    }
    @Test void nationalHolidaysWeekendsAndDstUseTheItalianCalendar(){
        assertThat(EnergyMath.band(ZonedDateTime.of(2026,11,6,8,0,0,0,EnergyMath.ZONE))).isZero();assertThat(EnergyMath.band(ZonedDateTime.of(2026,11,7,12,0,0,0,EnergyMath.ZONE))).isEqualTo(1);assertThat(EnergyMath.band(ZonedDateTime.of(2026,4,6,12,0,0,0,EnergyMath.ZONE))).isEqualTo(2);
        for(var day:List.of(LocalDate.of(2026,3,29),LocalDate.of(2026,10,25))){var c=config("nominal","");var f=new EnergyMath.Frame(Map.of("lamp",new EnergyMath.Point("Lampada","electricity","nominal",false,1)),List.of(),Set.of("lamp"));Instant t=day.atStartOfDay(EnergyMath.ZONE).toInstant(),end=day.plusDays(1).atStartOfDay(EnergyMath.ZONE).toInstant();double amount=0;for(;t.isBefore(end);t=t.plusSeconds(60))amount+=sum(EnergyMath.integrate(c,f,f,t,t.plusSeconds(60)),"$electricity");assertThat(amount).isCloseTo(day.getMonthValue()==3?23:25,within(1e-8));}
    }
    @Test void separatesMonthBoundaryAndLeavesUnpricedAmountsExplicit(){
        var c=config("nominal","");var f=new EnergyMath.Frame(Map.of("lamp",new EnergyMath.Point("Lampada","electricity","nominal",false,1)),List.of(),Set.of("lamp"));
        Instant start=Instant.parse("2026-10-31T22:59:00Z");var rows=EnergyMath.integrate(c,f,f,start,start.plusSeconds(120));var months=rows.stream().filter(b->b.source().equals("$electricity")).map(b->YearMonth.from(Instant.ofEpochSecond(b.hour()).atZone(EnergyMath.ZONE))).distinct().toList();assertThat(months).containsExactly(YearMonth.of(2026,10),YearMonth.of(2026,11));
        var unpriced=new EnergyConfig(1,true,List.of(),c.devices(),c.mains(),c.gas());assertThat(EnergyMath.integrate(unpriced,f,f,start,start.plusSeconds(60)).stream().filter(b->b.source().equals("$electricity")).mapToDouble(EnergyMath.Bucket::unpriced).sum()).isCloseTo(1./60,within(1e-9));
    }
    @Test void thermalBalanceUsesEfficiencyPcsAndZoneWeightsNotRadiatorMultiplication()throws Exception{
        double expected=200.*15/1000/.9/10.7;assertThat(EnergyMath.thermalRate(gas("thermal",true,1),Map.of())).isCloseTo(expected,within(1e-9));assertThat(EnergyMath.thermalRate(gas("thermal",true,10),Map.of())).isCloseTo(expected,within(1e-9));assertThat(EnergyMath.thermalRate(gas("thermal",false,3),Map.of())).isZero();
        var c=config("nominal","");var g=new EnergyConfig.Gas("thermal","",1,200,.9,10.7,"weather.casa",5,true,0,List.of(new EnergyConfig.Zone("z","Casa",2,"climate.valvola","",20,20)));var thermal=new EnergyConfig(1,true,c.contracts(),List.of(),c.mains(),g);
        var active=frame(thermal,"""
[{"entity_id":"weather.casa","state":"sunny","attributes":{"temperature":41,"temperature_unit":"°F"}},{"entity_id":"climate.valvola","state":"heat","attributes":{"current_temperature":20,"temperature":21,"temperature_unit":"°C","hvac_action":"heating"}}]
""");assertThat(active.points().get("$gas").value()).isCloseTo(expected,within(1e-9));
        assertThat(frame(thermal,"[]").points()).isEmpty();var meter=new EnergyConfig(1,true,c.contracts(),List.of(),c.mains(),gas("counter",true,1));assertThat(frame(meter,"""
[{"entity_id":"sensor.gas","state":"10","attributes":{"unit_of_measurement":"m³"}}]
""").points().get("$gas").value()).isCloseTo(10.2,within(1e-9));
    }
}
