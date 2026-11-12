package it.smarthome3d.api;

import it.smarthome3d.auth.OwnerProfile;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    private final OwnerProfile profile;
    public ProfileController(OwnerProfile profile){this.profile=profile;}
    public record Selection(String projectId) {}
    @GetMapping public Map<String,String> get(){return Map.of("activeProject",profile.activeProject());}
    @PutMapping("/active-project") public void select(@RequestBody Selection selection){profile.selectProject(selection.projectId());}
}
