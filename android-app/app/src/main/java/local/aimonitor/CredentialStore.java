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
  private static final String ALIAS = "code-spend-usage-v3";
  private static final Object LOCK = new Object();
  private static final java.util.EnumMap<Provider, Long> REVISIONS =
      new java.util.EnumMap<>(Provider.class);
  private final SharedPreferences preferences;

  public CredentialStore(Context context) {
    preferences = context.getSharedPreferences("secure-subscription-usage", Context.MODE_PRIVATE);
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }

  public void save(Provider provider, JSONObject data) throws Exception {
    synchronized (LOCK) {
      saveLocked(provider, data);
      changed(provider);
    }
  }

  public long revision(Provider provider) {
    synchronized (LOCK) {
      return REVISIONS.getOrDefault(provider, 0L);
    }
  }

  private void changed(Provider provider) {
    REVISIONS.put(provider, REVISIONS.getOrDefault(provider, 0L) + 1);
  }

  /** Imports cannot overwrite a deletion or credential replacement completed while parsing. */
  public void importCredential(Provider provider, long expectedRevision, JSONObject data)
      throws Exception {
    synchronized (LOCK) {
      if (revision(provider) != expectedRevision || Thread.currentThread().isInterrupted())
        throw new java.io.IOException("가져오기가 취소되었습니다.");
      if (provider == Provider.CODEX) {
        JSONObject previous = loadLocked(provider);
        if (previous != null) {
          if (previous.has("localReport")) data.put("localReport", previous.get("localReport"));
          else if (!previous.has("accessToken") && previous.has("report"))
            data.put("localReport", previous.get("report"));
        }
      }
      saveLocked(provider, data);
      changed(provider);
    }
  }

  public void importCodexRecord(long expectedRevision, JSONObject report) throws Exception {
    synchronized (LOCK) {
      if (revision(Provider.CODEX) != expectedRevision || Thread.currentThread().isInterrupted())
        throw new java.io.IOException("가져오기가 취소되었습니다.");
      JSONObject current = loadLocked(Provider.CODEX);
      if (current != null && !current.optString("accessToken", "").isEmpty())
        current.put("localReport", report);
      else current = new JSONObject().put("report", report);
      saveLocked(Provider.CODEX, current);
      changed(Provider.CODEX);
    }
  }

  private void saveLocked(Provider provider, JSONObject data) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] ciphertext = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
    byte[] iv = cipher.getIV();
    byte[] packed = Arrays.copyOf(iv, iv.length + ciphertext.length);
    System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
    if (!preferences
        .edit()
        .putString(provider.name(), Base64.encodeToString(packed, Base64.NO_WRAP))
        .commit()) {
      throw new java.io.IOException("인증 정보를 저장하지 못했습니다.");
    }
  }

  public JSONObject load(Provider provider) throws Exception {
    synchronized (LOCK) {
      return loadLocked(provider);
    }
  }

  private JSONObject loadLocked(Provider provider) throws Exception {
    String stored = preferences.getString(provider.name(), "");
    if (stored.isEmpty()) return null;
    byte[] packed = Base64.decode(stored, Base64.NO_WRAP);
    if (packed.length <= 28) throw new java.security.GeneralSecurityException("저장 형식 오류");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, packed, 0, 12));
    JSONObject data =
        new JSONObject(
            new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8));
    return data;
  }

  public boolean configured(Provider provider) {
    synchronized (LOCK) {
      return preferences.contains(provider.name());
    }
  }

  /** A late network response cannot restore deleted/replaced credentials. */
  public boolean saveClaudeReport(String expectedToken, JSONObject report) throws Exception {
    return saveUsageReport(Provider.CLAUDE, expectedToken, null, report);
  }

  public boolean saveUsageReport(
      Provider provider, String expectedToken, String expectedAccountId, JSONObject report)
      throws Exception {
    synchronized (LOCK) {
      JSONObject current = loadLocked(provider);
      if (current == null || !expectedToken.equals(current.optString("accessToken", "")))
        return false;
      if (provider == Provider.CODEX
          && !java.util.Objects.equals(
              expectedAccountId,
              current.isNull("accountId") ? null : current.optString("accountId", null)))
        return false;
      JSONObject previous = current.optJSONObject("report");
      if (previous != null
          && java.time.Instant.parse(previous.getString("asOf"))
              .isAfter(java.time.Instant.parse(report.getString("asOf")))) return false;
      current.put("report", report);
      saveLocked(provider, current);
      return true;
    }
  }

  public void remove(Provider provider) {
    synchronized (LOCK) {
      changed(provider);
      preferences.edit().remove(provider.name()).apply();
    }
  }
}
