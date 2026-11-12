package it.smarthome3d.auth;

import jakarta.servlet.http.HttpSession;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** References existing servlet sessions; never keeps a key alive past expiry. */
@Component
public class OwnerSessions {
    private final ConcurrentHashMap<String,HttpSession> sessions=new ConcurrentHashMap<>();
    public void add(HttpSession session){sessions.put(session.getId(),session);}
    public void remove(String id){sessions.remove(id);}
    public CredentialKey key(){
        for(var entry:sessions.entrySet())try{
            var session=entry.getValue();long idle=System.currentTimeMillis()-session.getLastAccessedTime();int max=session.getMaxInactiveInterval();
            if(max>0&&idle>=max*1000L){sessions.remove(entry.getKey());continue;}
            var key=session.getAttribute(CredentialKey.SESSION);if(key instanceof CredentialKey k&&k.active())return k;
        }catch(IllegalStateException e){sessions.remove(entry.getKey());}
        return null;
    }
}
