package org.canceledagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials are AES-256-GCM encrypted with a non-exportable Android Keystore key. */
public final class SecurePrefs {
    private final SharedPreferences prefs;
    private static final String ALIAS="canceled-agent-local-secrets";
    public SecurePrefs(Context ctx) { prefs=ctx.getSharedPreferences("settings",Context.MODE_PRIVATE); }
    public String get(String name,String defaultValue){return prefs.getString(name,defaultValue);}
    public void put(String name,String value){prefs.edit().putString(name,value).apply();}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS))return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS,null)).getSecretKey();
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
           .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    public void secret(String name,String value){
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
            String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
            String encrypted=Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
            put("private-"+name,iv+":"+encrypted);
        } catch(Exception ex){throw new IllegalStateException("Unable to store encrypted credential",ex);}
    }
    public String readSecret(String name){
        String s=get("private-"+name,"");if(s.isEmpty())return "";
        try {
            String[] parts=s.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception ex){throw new IllegalStateException("Unable to decrypt credential",ex);}
    }
    public void deleteSecret(String name){prefs.edit().remove("private-"+name).apply();}
}
