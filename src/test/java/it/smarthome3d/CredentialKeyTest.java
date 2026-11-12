package it.smarthome3d;

import it.smarthome3d.auth.CredentialKey;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CredentialKeyTest {
    @Test void encryptsWithFreshNoncesAndAuthenticatesPasswordOwnerAndMetadata(){
        byte[] salt=new byte[32];new SecureRandom().nextBytes(salt);
        try(var key=CredentialKey.derive("test-only-owner-password",salt);var same=CredentialKey.derive("test-only-owner-password",salt);var wrong=CredentialKey.derive("wrong-owner-password",salt)){
            String value="test-only-refresh-not-a-real-credential",context="owner-1\0http://ha.test\0client";
            String first=key.encrypt(value,context),second=key.encrypt(value,context);
            assertThat(first).startsWith("v1:").doesNotContain(value).isNotEqualTo(second);
            assertThat(same.decrypt(first,context)).isEqualTo(value);
            assertThatThrownBy(()->wrong.decrypt(first,context)).hasMessageContaining("non è leggibile");
            assertThatThrownBy(()->key.decrypt(first,"owner-2\0http://ha.test\0client")).hasMessageContaining("non è leggibile");
            assertThatThrownBy(()->key.decrypt(first,context+"changed-instance")).hasMessageContaining("non è leggibile");
            char changed=first.charAt(22)=='A'?'B':'A';String damaged=first.substring(0,22)+changed+first.substring(23);
            assertThatThrownBy(()->key.decrypt(damaged,context)).hasMessageContaining("non è leggibile");
            key.close();assertThat(key.active()).isFalse();assertThatThrownBy(()->key.decrypt(first,context)).hasMessageContaining("Accedi nuovamente");
        }
    }
}
