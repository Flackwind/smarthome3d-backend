package it.smarthome3d.api;

import it.smarthome3d.energy.*;
import it.smarthome3d.storage.ProjectStorage;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{id}/energy")
public class EnergyController {
    private final EnergyStorage storage;private final EnergyCollector collector;private final ProjectStorage projects;
    public EnergyController(EnergyStorage storage,EnergyCollector collector,ProjectStorage projects){this.storage=storage;this.collector=collector;this.projects=projects;}
    @GetMapping public Map<String,Object> year(@PathVariable String id,@RequestParam int year){return Map.of("months",storage.year(id,year),"status",collector.status(id),"monthly",storage.monthly(id));}
    @PostMapping("/sample") public EnergyCollector.Status sample(@PathVariable String id){projects.get(id);collector.collect();return collector.status(id);}
    @PutMapping("/monthly") public void monthly(@PathVariable String id,@RequestBody EnergyStorage.Manual value){storage.manual(id,value);}
    @DeleteMapping("/monthly/{month}/{utility}") public void remove(@PathVariable String id,@PathVariable String month,@PathVariable String utility){storage.removeManual(id,month,utility);}
}
