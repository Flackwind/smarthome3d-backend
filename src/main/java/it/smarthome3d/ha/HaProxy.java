package it.smarthome3d.ha;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.smarthome3d.auth.CredentialKey;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Cookie-authenticated bridge. HA credentials never cross the browser boundary. */
@Component
public class HaProxy extends TextWebSocketHandler {
    private final HaClient ha;private final ObjectMapper json;private final HaConnections connections;private final HaProfileConnection profile;
    private final Map<String,Bridge> bridges=new ConcurrentHashMap<>();
    private static final Set<String> READS=Set.of("get_states","get_services","config/entity_registry/list","config/entity_registry/get","config/device_registry/list","config/area_registry/list","config/floor_registry/list","ping");
    private static final Set<String> ACTIONS=Set.of("light.turn_on","light.turn_off","switch.turn_on","switch.turn_off","input_boolean.turn_on","input_boolean.turn_off","vacuum.start","vacuum.pause","vacuum.return_to_base","vacuum.set_fan_speed","vacuum.clean_area","button.press","select.select_option","number.set_value","time.set_value","climate.set_temperature","climate.set_hvac_mode","roborock.get_vacuum_current_position");
    public HaProxy(HaClient ha,ObjectMapper json,HaConnections connections,HaProfileConnection profile){this.ha=ha;this.json=json;this.connections=connections;this.profile=profile;}
    @Override public void afterConnectionEstablished(WebSocketSession socket)throws Exception{
        socket.setTextMessageSizeLimit(65536);var session=(HttpSession)socket.getAttributes().get("httpSession");var bridge=new Bridge(socket,session);bridges.put(socket.getId(),bridge);connections.add(session.getId(),socket);bridge.send(json.createObjectNode().put("type","auth_required"));
    }
    @Override protected void handleTextMessage(WebSocketSession socket,TextMessage message)throws Exception{
        var bridge=bridges.get(socket.getId());if(bridge==null)return;JsonNode data;
        try{data=json.readTree(message.getPayload());}catch(Exception e){socket.close(CloseStatus.BAD_DATA);return;}
        if(data==null||!data.isObject()){socket.close(CloseStatus.BAD_DATA);return;}
        if(data.path("type").asText().equals("auth")){
            if(bridge.started||data.size()!=1){socket.close(CloseStatus.POLICY_VIOLATION);return;}bridge.started=true;bridge.connect();return;
        }
        if(!bridge.authenticated||!allowed(data)){bridge.send(json.createObjectNode().put("id",data.path("id").asInt()).put("type","result").put("success",false).set("error",json.createObjectNode().put("message","Richiesta non consentita dal bridge Home Assistant")));return;}
        bridge.upstream.sendText(data.toString(),true).exceptionally(error->{bridge.close();return null;});
    }
    public static boolean allowed(JsonNode data){
        if(!data.path("id").canConvertToInt()||data.path("id").asInt()<1)return false;
        String type=data.path("type").asText();
        if(READS.contains(type))return !data.has("access_token")&&(!type.equals("config/entity_registry/get")||data.path("entity_id").asText().matches("[a-z_]+\\.[a-z0-9_]+"));
        if(type.equals("subscribe_events"))return data.path("event_type").asText().equals("state_changed");
        if(!type.equals("call_service"))return false;
        String action=data.path("domain").asText()+"."+data.path("service").asText(),entity=data.path("target").path("entity_id").asText();
        if(!ACTIONS.contains(action)||!entity.matches("[a-z_]+\\.[a-z0-9_]+")||data.path("target").size()!=1||!entity.startsWith(data.path("domain").asText().equals("roborock")?"vacuum.":data.path("domain").asText()+"."))return false;
        if(data.has("service_data")&&!data.path("service_data").isObject())return false;
        if(data.has("return_response")&&(!action.equals("roborock.get_vacuum_current_position")||!data.path("return_response").asBoolean()))return false;
        var values=data.path("service_data");
        Set<String> fields=switch(action){
            case "light.turn_on" -> Set.of("brightness_pct","rgb_color","color_temp_kelvin");
            case "vacuum.set_fan_speed" -> Set.of("fan_speed");case "vacuum.clean_area" -> Set.of("cleaning_area_id");
            case "select.select_option" -> Set.of("option");case "number.set_value" -> Set.of("value");case "time.set_value" -> Set.of("time");
            case "climate.set_temperature" -> Set.of("temperature");case "climate.set_hvac_mode" -> Set.of("hvac_mode");
            default -> Set.of();
        };
        for(var name:values.properties().stream().map(Map.Entry::getKey).toList())if(!fields.contains(name))return false;
        if(action.equals("vacuum.clean_area")){var areas=values.path("cleaning_area_id");if(!areas.isArray()||areas.isEmpty()||areas.size()>100)return false;Set<String> unique=new HashSet<>();for(var area:areas)if(!area.isTextual()||area.asText().isBlank()||area.asText().length()>200||!unique.add(area.asText()))return false;}
        for(var name:List.of("brightness_pct","color_temp_kelvin","temperature"))if(values.has(name)&&(!values.path(name).isNumber()||!Double.isFinite(values.path(name).asDouble())))return false;
        if(values.has("brightness_pct")&&(values.path("brightness_pct").asDouble()<0||values.path("brightness_pct").asDouble()>100))return false;
        if(values.has("rgb_color")){var color=values.path("rgb_color");if(!color.isArray()||color.size()!=3)return false;for(var channel:color)if(!channel.isNumber()||channel.asDouble()<0||channel.asDouble()>255)return false;}
        for(var name:List.of("fan_speed","option","hvac_mode"))if(values.has(name)&&(!values.path(name).isTextual()||values.path(name).asText().length()>200))return false;
        if(action.equals("number.set_value")&&(!values.path("value").isNumber()||!Double.isFinite(values.path("value").asDouble())))return false;
        if(action.equals("time.set_value")&&!values.path("time").asText().matches("([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?"))return false;
        return true;
    }
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status){var bridge=bridges.remove(socket.getId());if(bridge!=null){connections.remove(bridge.sessionId,socket);if(bridge.upstream!=null)bridge.upstream.abort();}}
    private JsonNode redact(JsonNode node){
        if(node.isObject()){var copy=json.createObjectNode();node.properties().forEach(e->{if(!e.getKey().matches("(?i).*(token|password|secret|credential|authorization).*"))copy.set(e.getKey(),redact(e.getValue()));});return copy;}
        if(node.isArray()){var copy=json.createArrayNode();node.forEach(item->copy.add(redact(item)));return copy;}return node;
    }
    private final class Bridge implements WebSocket.Listener {
        final WebSocketSession frontend;final HttpSession session;final String sessionId;final StringBuilder buffer=new StringBuilder();volatile WebSocket upstream;volatile boolean started,authenticated;
        Bridge(WebSocketSession frontend,HttpSession session){this.frontend=frontend;this.session=session;this.sessionId=session.getId();}
        void connect(){CompletableFuture.runAsync(()->{
            try{if(!(session.getAttribute(CredentialKey.SESSION) instanceof CredentialKey key)||!key.active())throw new IllegalStateException();String token=profile.access(key);var url=ha.endpoint("/api/websocket").toString().replaceFirst("^http","ws");
                var listener=new WebSocket.Listener(){
                    @Override public void onOpen(WebSocket ws){upstream=ws;if(!frontend.isOpen()){ws.abort();return;}ws.request(1);}
                    @Override public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){
                        if(!frontend.isOpen()){close();return null;}buffer.append(text);if(buffer.length()>20_000_000){close();return null;}
                        if(last){try{var data=json.readTree(buffer.toString());buffer.setLength(0);String type=data.path("type").asText();
                            if(type.equals("auth_required"))ws.sendText(json.createObjectNode().put("type","auth").put("access_token",token).toString(),true);
                            else if(type.equals("auth_invalid")){profile.clearAccess();send(json.createObjectNode().put("type","auth_invalid"));close();}
                            else{if(type.equals("auth_ok"))authenticated=true;send(redact(data));}
                        }catch(Exception e){close();}}ws.request(1);return null;
                    }
                    @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){close();return null;}
                    @Override public void onError(WebSocket ws,Throwable error){close();}
                };
                if(frontend.isOpen())ha.http.newWebSocketBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).buildAsync(URI.create(url),listener).exceptionally(e->{close();return null;});
            }catch(Exception e){if(e instanceof HaAuthorizationException)send(json.createObjectNode().put("type","auth_invalid").put("message",e.getMessage()));close();}
        });}
        void send(JsonNode data){try{synchronized(frontend){if(frontend.isOpen())frontend.sendMessage(new TextMessage(data.toString()));}}catch(Exception e){close();}}
        void close(){try{if(frontend.isOpen())frontend.close(CloseStatus.SERVER_ERROR);}catch(Exception ignored){}if(upstream!=null)upstream.abort();}
    }
}
