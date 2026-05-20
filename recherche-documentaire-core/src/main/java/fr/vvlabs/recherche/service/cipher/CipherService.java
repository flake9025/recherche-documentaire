package fr.vvlabs.recherche.service.cipher;

import fr.vvlabs.recherche.util.CipherUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;

@Service
@Slf4j
public class CipherService {

    private final byte[] secretKeyBytes;

    @Value("${app.cipher.enabled}")
    private boolean cipherEnabled;

    public CipherService(@Value("${app.cipher.key}") String secretKeyBase64) {
        this.secretKeyBytes = Base64.getDecoder().decode(secretKeyBase64);
    }

    public String encrypt(String data) throws Exception {
        if(cipherEnabled) {
            log.debug("Cipher enabled. Encrypting string data: {}", data);
            return CipherUtil.encrypt(data, secretKeyBytes);
        }
        return data;
    }

    public byte[] encrypt(byte[] data) throws Exception {
        if(cipherEnabled) {
            log.debug("Cipher enabled. Encrypting byte data: {}", data);
            return CipherUtil.encrypt(data, secretKeyBytes);
        }
        return data;
    }

    public String decrypt(String data) throws Exception {
        if(cipherEnabled) {
            log.debug("Cipher enabled. Decrypting string data: {}", data);
            return CipherUtil.decrypt(data, secretKeyBytes);
        }
        return data;
    }

    public byte[] decrypt(byte[] data) throws Exception {
        if(cipherEnabled) {
            log.debug("Cipher enabled. Decrypting byte data: {}", data);
            return CipherUtil.decrypt(data, secretKeyBytes);
        }
        return data;
    }
}

