package it.smarthome3d.api;

import it.smarthome3d.auth.*;
import it.smarthome3d.ha.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class SessionController {
    private final OwnerService owner;private final SecurityContextRepository contexts;private final HaClient ha;private final OwnerProfile profile;private final HaProfileConnection connection;
    public record Credentials(String username,String password) {}
    private final OwnerSessions sessions;
    public SessionController(OwnerService owner,SecurityContextRepository contexts,HaClient ha,OwnerProfile profile,HaProfileConnection connection,OwnerSessions sessions){this.owner=owner;this.contexts=contexts;this.ha=ha;this.profile=profile;this.connection=connection;this.sessions=sessions;}
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","ok","apiVersion",1);}
    @GetMapping("/api/v1/session") public Map<String,Object> session(HttpServletRequest request,Authentication auth){
        var csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());var session=request.getSession();boolean authenticated=auth!=null&&!(auth instanceof AnonymousAuthenticationToken)&&auth.isAuthenticated();
        var key=session.getAttribute(CredentialKey.SESSION);var status=authenticated&&key instanceof CredentialKey k?connection.status(k):new HaProfileConnection.Status(false,"");
        return Map.of("authenticated",authenticated,"setupRequired",owner.setupRequired(),"username",authenticated?auth.getName():"","csrfToken",csrf.getToken(),"homeAssistant",Map.of("configured",!ha.url().isBlank(),"url",ha.url(),"connected",status.connected(),"message",status.message()));
    }
    @PostMapping("/api/v1/auth/setup") public void setup(@RequestBody Credentials credentials,HttpServletRequest request,HttpServletResponse response){owner.setup(credentials.username(),credentials.password());establish(credentials.username(),credentials.password(),request,response);}
    @PostMapping("/api/v1/auth/login") public void login(@RequestBody Credentials credentials,HttpServletRequest request,HttpServletResponse response){establish(owner.login(credentials.username(),credentials.password(),request.getRemoteAddr()),credentials.password(),request,response);}
    @PostMapping("/api/v1/auth/logout") public void logout(HttpServletRequest request,HttpServletResponse response){var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();var cookie=new Cookie("SH3D_SESSION","");cookie.setHttpOnly(true);cookie.setPath("/api");cookie.setMaxAge(0);cookie.setSecure(request.isSecure());cookie.setAttribute("SameSite","Lax");response.addCookie(cookie);}
    private void establish(String username,String password,HttpServletRequest request,HttpServletResponse response){
        var key=profile.unlock(password);var existing=request.getSession(false);if(existing!=null)existing.invalidate();var session=request.getSession(true);session.setAttribute(CredentialKey.SESSION,key);sessions.add(session);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(username,null,List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
    }
}
