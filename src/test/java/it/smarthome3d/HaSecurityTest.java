package it.smarthome3d;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.smarthome3d.ha.HaProxy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HaSecurityTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void allowsOnlyReadOperationsAndExplicitlySupportedActions()throws Exception{
        assertThat(HaProxy.allowed(json.readTree("{\"id\":1,\"type\":\"get_states\"}"))).isTrue();
        assertThat(HaProxy.allowed(json.readTree("{\"id\":2,\"type\":\"auth/long_lived_access_token\"}"))).isFalse();
        assertThat(HaProxy.allowed(json.readTree("{\"id\":3,\"type\":\"auth/sign_path\"}"))).isFalse();
        assertThat(HaProxy.allowed(json.readTree("{\"id\":4,\"type\":\"subscribe_events\",\"event_type\":\"state_changed\"}"))).isTrue();
        assertThat(HaProxy.allowed(json.readTree("{\"id\":4,\"type\":\"subscribe_events\"}"))).isFalse();
        assertThat(HaProxy.allowed(json.readTree("{\"id\":5,\"type\":\"call_service\",\"domain\":\"vacuum\",\"service\":\"start\",\"target\":{\"entity_id\":\"vacuum.test\"}}"))).isTrue();
        assertThat(HaProxy.allowed(json.readTree("{\"id\":5,\"type\":\"call_service\",\"domain\":\"homeassistant\",\"service\":\"restart\",\"target\":{\"entity_id\":\"vacuum.test\"}}"))).isFalse();
        assertThat(HaProxy.allowed(json.readTree("{\"id\":5,\"type\":\"call_service\",\"domain\":\"vacuum\",\"service\":\"start\",\"target\":{\"entity_id\":\"vacuum.test\"},\"service_data\":{\"entity_id\":\"all\"}}"))).isFalse();
        assertThat(HaProxy.allowed(json.readTree("{\"id\":5,\"type\":\"call_service\",\"domain\":\"vacuum\",\"service\":\"clean_area\",\"target\":{\"entity_id\":\"vacuum.test\"},\"service_data\":{\"cleaning_area_id\":[]}}"))).isFalse();
    }
}
