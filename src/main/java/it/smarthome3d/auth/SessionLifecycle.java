package it.smarthome3d.auth;

import it.smarthome3d.ha.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration
public class SessionLifecycle {
    @Bean ServletListenerRegistrationBean<HttpSessionListener> sessionCleanup(HaConnections connections,OwnerSessions sessions){
        return new ServletListenerRegistrationBean<>(new HttpSessionListener(){@Override public void sessionDestroyed(HttpSessionEvent event){
            sessions.remove(event.getSession().getId());connections.close(event.getSession().getId());var key=event.getSession().getAttribute(CredentialKey.SESSION);if(key instanceof CredentialKey credential)credential.close();
        }});
    }
}
