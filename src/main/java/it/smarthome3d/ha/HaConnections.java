package it.smarthome3d.ha;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

@Component
public class HaConnections {
    private final Map<String,Set<WebSocketSession>> sessions=new ConcurrentHashMap<>();
    public void add(String id,WebSocketSession socket){sessions.computeIfAbsent(id,k->ConcurrentHashMap.newKeySet()).add(socket);}
    public void remove(String id,WebSocketSession socket){var set=sessions.get(id);if(set!=null){set.remove(socket);if(set.isEmpty())sessions.remove(id,set);}}
    public void close(String id){var set=sessions.remove(id);if(set!=null)for(var socket:set)try{socket.close(CloseStatus.POLICY_VIOLATION);}catch(Exception ignored){}}
    public void closeAll(){for(var id:List.copyOf(sessions.keySet()))close(id);}
}
