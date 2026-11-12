package it.smarthome3d.auth;

import it.smarthome3d.api.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.http.HttpStatus;

/** Session-only key. Never serializable, persisted, returned as JSON, or logged. */
public final class CredentialKey implements AutoCloseable {
    public static final String SESSION="sh3d.profile.key";
    private static final SecureRandom RANDOM=new SecureRandom();
    private byte[] material;
    private CredentialKey(byte[] material){this.material=material;}
    public static CredentialKey derive(String password,byte[] salt){
        if(salt==null||salt.length!=32)throw new IllegalStateException("Profilo credenziali non valido");
        char[] characters=password.toCharArray();var spec=new PBEKeySpec(characters,salt,600_000,256);Arrays.fill(characters,'\0');
        try{return new CredentialKey(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
        catch(GeneralSecurityException e){throw new IllegalStateException("Cifratura del profilo non disponibile");}
        finally{spec.clearPassword();}
    }
    public synchronized boolean active(){return material!=null;}
    public synchronized String encrypt(String value,String context){
        requireActive();byte[] clear=value.getBytes(StandardCharsets.UTF_8),nonce=new byte[12];RANDOM.nextBytes(nonce);
        try{var cipher=cipher(Cipher.ENCRYPT_MODE,nonce,context);byte[] encrypted=cipher.doFinal(clear);return "v1:"+Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());}
        catch(GeneralSecurityException e){throw failure();}finally{Arrays.fill(clear,(byte)0);}
    }
    public synchronized String decrypt(String value,String context){
        requireActive();byte[] clear=null;
        try{
            if(value==null||!value.startsWith("v1:")||value.length()>20_000)throw new GeneralSecurityException();
            byte[] envelope=Base64.getDecoder().decode(value.substring(3));if(envelope.length<29)throw new GeneralSecurityException();
            var cipher=cipher(Cipher.DECRYPT_MODE,Arrays.copyOf(envelope,12),context);clear=cipher.doFinal(envelope,12,envelope.length-12);return new String(clear,StandardCharsets.UTF_8);
        }catch(GeneralSecurityException|IllegalArgumentException e){throw failure();}finally{if(clear!=null)Arrays.fill(clear,(byte)0);}
    }
    private Cipher cipher(int mode,byte[] nonce,String context)throws GeneralSecurityException{
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(material,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));return cipher;
    }
    private void requireActive(){if(material==null)throw new ApiException(HttpStatus.UNAUTHORIZED,"Accedi nuovamente al server");}
    private static ApiException failure(){return new ApiException(HttpStatus.BAD_GATEWAY,"Il collegamento Home Assistant salvato non è leggibile. Collega nuovamente Home Assistant.");}
    @Override public synchronized void close(){if(material!=null){Arrays.fill(material,(byte)0);material=null;}}
    @Override public String toString(){return "Protected owner credential key";}
}
