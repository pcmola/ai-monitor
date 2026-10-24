package local.aimonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Credentials remain on the phone, encrypted by a non-exportable Keystore key. */
public final class CredentialStore {
    private static final String ALIAS = "code-spend-credentials-v1";
    private final SharedPreferences preferences;

    public record Credentials(String key, String scope) {}

    public CredentialStore(Context context) {
        preferences = context.getSharedPreferences("secure-cost-accounts", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    public synchronized void save(Provider provider, String apiKey, String scope) throws Exception {
        JSONObject data = new JSONObject().put("key", apiKey).put("scope", scope);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ciphertext = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] packed = Arrays.copyOf(iv, iv.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
        if (!preferences.edit().putString(provider.name(),
                Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            throw new java.io.IOException("인증 정보를 저장하지 못했습니다.");
        }
    }

    public synchronized Credentials load(Provider provider) throws Exception {
        String stored = preferences.getString(provider.name(), "");
        if (stored.isEmpty()) return null;
        byte[] packed = Base64.decode(stored, Base64.NO_WRAP);
        if (packed.length <= 28) throw new java.security.GeneralSecurityException("저장 형식 오류");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, packed, 0, 12));
        JSONObject data = new JSONObject(new String(cipher.doFinal(packed, 12, packed.length - 12),
                StandardCharsets.UTF_8));
        return new Credentials(data.getString("key"), data.getString("scope"));
    }

    public boolean configured(Provider provider) {
        return preferences.contains(provider.name());
    }

    public void remove(Provider provider) {
        preferences.edit().remove(provider.name()).apply();
    }
}
