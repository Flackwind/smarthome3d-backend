package it.smarthome3d.config;

import it.smarthome3d.ha.*;
import it.smarthome3d.auth.CredentialKey;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final HaProxy proxy;private final List<String> origins;private final HaProfileConnection connection;
    public WebSocketConfig(HaProxy proxy,HaProfileConnection connection,@Value("${sh3d.origins}")List<String> origins){this.proxy=proxy;this.origins=origins;this.connection=connection;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(proxy,"/api/v1/ha/ws").setAllowedOrigins(origins.toArray(String[]::new)).addInterceptors(new HandshakeInterceptor(){
            @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
                if(!(request instanceof ServletServerHttpRequest servlet)||!origins.contains(request.getHeaders().getOrigin()))return false;
                var session=servlet.getServletRequest().getSession(false);if(session==null)return false;
                var context=session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                if(!(context instanceof SecurityContext security)||security.getAuthentication()==null||!security.getAuthentication().isAuthenticated()||!(session.getAttribute(CredentialKey.SESSION) instanceof CredentialKey key)||!connection.status(key).connected())return false;
                attributes.put("httpSession",session);return true;
            }
            @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception error){}
        });
    }
}
