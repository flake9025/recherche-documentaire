package fr.vvlabs.recherche.service.cipher;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CipherServiceTest {

    @Test
    void encryptAndDecrypt_roundTripWhenEnabled() throws Exception {
        CipherService service = new CipherService("zHRtFj9XxeREUhD+rqh5oI9PF4ZTN6r3Ua4ouUBq3yM=");
        ReflectionTestUtils.setField(service, "cipherEnabled", true);

        String encrypted = service.encrypt("hello");
        String decrypted = service.decrypt(encrypted);

        assertThat(encrypted).isNotEqualTo("hello");
        assertThat(decrypted).isEqualTo("hello");
    }

    @Test
    void encryptAndDecrypt_returnsOriginalWhenDisabled() throws Exception {
        CipherService service = new CipherService("zHRtFj9XxeREUhD+rqh5oI9PF4ZTN6r3Ua4ouUBq3yM=");
        ReflectionTestUtils.setField(service, "cipherEnabled", false);
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        assertThat(service.encrypt("hello")).isEqualTo("hello");
        assertThat(service.decrypt("hello")).isEqualTo("hello");
        assertThat(service.encrypt(bytes)).isSameAs(bytes);
        assertThat(service.decrypt(bytes)).isSameAs(bytes);
    }
}
