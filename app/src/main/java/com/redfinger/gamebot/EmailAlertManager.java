package com.redfinger.gamebot;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/** SMTP alert delivery and persistent alert-state tracking. */
public final class EmailAlertManager {
    private static final String PREF = "email_alerts";
    private static final String KEY_ALIAS = "rfgb_email_secret_v1";
    private static final long STUCK_INTERVAL = 30L * 60L * 1000L;
    private final Context context;
    private final SharedPreferences prefs;
    private static final Object IDENTITY_SEND_LOCK = new Object();
    private static boolean identityRetryWorkerRunning = false;
    private static boolean stuckSending = false;

    public interface SendCallback {
        void onResult(boolean success, String message);
    }

    public EmailAlertManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void saveConfig(Context c, String host, String port, String from,
                                  String password, String to) throws Exception {
        int p = Integer.parseInt(port.trim());
        if (host.trim().isEmpty() || from.trim().isEmpty() || to.trim().isEmpty()
                || password.isEmpty() || p < 1 || p > 65535) {
            throw new IllegalArgumentException("请完整填写 SMTP 服务器、端口、发件邮箱、授权码和收件邮箱");
        }
        String encrypted = encrypt(c, password);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("host", host.trim()).putInt("port", p)
                .putString("from", from.trim()).putString("to", to.trim())
                .putString("password", encrypted).apply();
    }

    public static String[] loadConfig(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String secret = p.getString("password", "");
        String password = "";
        try { if (!secret.isEmpty()) password = decrypt(c, secret); } catch (Exception ignored) {}
        return new String[]{p.getString("host", "smtp.gmail.com"),
                String.valueOf(p.getInt("port", 587)), p.getString("from", ""),
                password, p.getString("to", "")};
    }

    public void observe(String state, boolean identityVerification) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (identityVerification) {
                boolean wasActive = prefs.getBoolean("identity_active", false);
                if (!wasActive) {
                    long nextSessionToken = prefs.getLong("identity_session_token", 0L) + 1L;
                    prefs.edit().putBoolean("identity_active", true)
                            .putLong("identity_entered_at", now)
                            .putLong("identity_last_alert", 0L)
                            .putLong("identity_retry_after", 0L)
                            .putLong("identity_session_token", nextSessionToken)
                            .putString("identity_state", state)
                            // Never reuse a previous verification session's link.
                            .remove("latest_verification_uri")
                            .remove("latest_verification_decoded").apply();
                } else {
                    prefs.edit().putString("identity_state", state).apply();
                }
                // Keep one worker alive for this verification session. It waits for
                // the decoded URL, then retries SMTP indefinitely until accepted.
                ensureIdentityAlertWorker();
                return;
            }
            if (prefs.getBoolean("identity_active", false)) {
                prefs.edit().putBoolean("identity_active", false)
                        .putLong("identity_entered_at", 0L)
                        .putLong("identity_last_alert", 0L)
                        .putLong("identity_retry_after", 0L).apply();
            }
            String previous = prefs.getString("stuck_state", "");
            long since = prefs.getLong("stuck_since", now);
            boolean alerted = prefs.getBoolean("stuck_alerted", false);
            if (!state.equals(previous)) {
                prefs.edit().putString("stuck_state", state).putLong("stuck_since", now)
                        .putBoolean("stuck_alerted", false).putLong("stuck_retry_after", 0L).apply();
            } else if (!alerted && now - since > STUCK_INTERVAL
                    && now >= prefs.getLong("stuck_retry_after", 0L)) {
                synchronized (IDENTITY_SEND_LOCK) {
                    if (stuckSending) return;
                    stuckSending = true;
                }
                sendAsync("状态机疑似卡死提醒", "程序状态连续超过 30 分钟未变化。\n当前状态：" + state
                        + "\n开始时间：" + new java.util.Date(since)
                        + "\n检测时间：" + new java.util.Date(now), (success, message) -> {
                    synchronized (IDENTITY_SEND_LOCK) { stuckSending = false; }
                    if (success) prefs.edit().putBoolean("stuck_alerted", true)
                            .putLong("stuck_retry_after", 0L).apply();
                    else prefs.edit().putLong("stuck_retry_after", System.currentTimeMillis() + 60_000L).apply();
                });
            }
        }
    }

    /** Store the original URI for local diagnostics only; never include it in email. */
    public void updateVerificationLink(String originalUri) {
        if (originalUri != null && !originalUri.trim().isEmpty()) {
            prefs.edit().putString("latest_verification_uri", originalUri.trim()).apply();
        }
    }

    /** Store the decoded HTTP(S) target and trigger the first alert once it is available. */
    public void updateDecodedVerificationLink(String decodedUrl) {
        if (decodedUrl == null || decodedUrl.trim().isEmpty()) return;
        prefs.edit().putString("latest_verification_decoded", decodedUrl.trim()).apply();
        if (prefs.getBoolean("identity_active", false)
                && prefs.getLong("identity_last_alert", 0L) == 0L) {
            ensureIdentityAlertWorker();
        }
    }

    /**
     * Deliver exactly one successful identity alert per verification session.
     * Failed SMTP attempts are retried forever (once per minute) while the same
     * identity session remains active. The worker waits for the decoded HTTPS URL
     * instead of dropping the alert when decoding finishes slightly later.
     */
    private void ensureIdentityAlertWorker() {
        synchronized (IDENTITY_SEND_LOCK) {
            if (identityRetryWorkerRunning
                    || !prefs.getBoolean("identity_active", false)
                    || prefs.getLong("identity_last_alert", 0L) != 0L) {
                return;
            }
            identityRetryWorkerRunning = true;
        }

        new Thread(() -> {
            boolean interrupted = false;
            try {
                while (prefs.getBoolean("identity_active", false)
                        && prefs.getLong("identity_last_alert", 0L) == 0L) {
                    String decoded = prefs.getString("latest_verification_decoded", "");
                    if (decoded == null || decoded.trim().isEmpty()) {
                        android.util.Log.i("RFG-Email", "身份验证邮件等待解码后的 HTTPS 链接");
                        Thread.sleep(5_000L);
                        continue;
                    }

                    long attemptSessionToken = prefs.getLong("identity_session_token", 0L);
                    String state = prefs.getString("identity_state", "身份验证");
                    long now = System.currentTimeMillis();
                    String body = "检测到程序进入身份验证状态。\n当前状态：" + state
                            + "\n时间：" + new java.util.Date(now)
                            + "\n\n解码后的验证链接：\n" + decoded;
                    try {
                        send("身份验证提醒", body);
                        // Mark only after SMTP Transport.send returns successfully.
                        if (prefs.getBoolean("identity_active", false)
                                && prefs.getLong("identity_session_token", 0L) == attemptSessionToken
                                && prefs.getLong("identity_last_alert", 0L) == 0L) {
                            prefs.edit().putLong("identity_last_alert", System.currentTimeMillis())
                                    .putLong("identity_retry_after", 0L).commit();
                            android.util.Log.i("RFG-Email", "身份验证提醒发送成功，本轮不再重复发送");
                            return;
                        }
                        // The verification session changed while SMTP was in flight.
                        // Do not mark the newer session as notified by an older email.
                        android.util.Log.w("RFG-Email", "邮件发送期间身份验证轮次已变化，将处理当前轮次提醒");
                        continue;
                    } catch (Exception e) {
                        android.util.Log.e("RFG-Email", "身份验证邮件发送失败；60秒后继续重试，直到成功："
                                + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
                        prefs.edit().putLong("identity_retry_after", System.currentTimeMillis() + 60_000L).apply();
                        // Wake early if this verification session ends and a new one begins;
                        // a new session should not wait out the previous session's backoff.
                        waitBeforeIdentityRetry(attemptSessionToken);
                    }
                }
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            } finally {
                synchronized (IDENTITY_SEND_LOCK) {
                    identityRetryWorkerRunning = false;
                }
                // Close the small race where a new verification session starts as
                // the previous worker is exiting. The new session still gets its alert.
                if (!interrupted && prefs.getBoolean("identity_active", false)
                        && prefs.getLong("identity_last_alert", 0L) == 0L) {
                    ensureIdentityAlertWorker();
                }
            }
        }, "RFG-Identity-Email-Retry").start();
    }

    private void waitBeforeIdentityRetry(long sessionToken) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!prefs.getBoolean("identity_active", false)
                    || prefs.getLong("identity_session_token", 0L) != sessionToken) {
                return;
            }
            long remaining = deadline - System.currentTimeMillis();
            Thread.sleep(Math.min(1_000L, Math.max(1L, remaining)));
        }
    }

    public void resetMonitoring() {
        prefs.edit().remove("stuck_state").remove("stuck_since").remove("stuck_alerted")
                .remove("stuck_retry_after")
                .putBoolean("identity_active", false).putLong("identity_last_alert", 0L)
                .putLong("identity_entered_at", 0L).putLong("identity_retry_after", 0L)
                .remove("identity_state").apply();
    }

    public void sendTestAsync(SendCallback callback) {
        String decoded = prefs.getString("latest_verification_decoded", "");
        StringBuilder body = new StringBuilder("邮件提醒配置成功。此邮件用于确认 SMTP 设置可用。\n");
        if (!decoded.isEmpty()) body.append("\n最近捕获的解码后验证链接：\n").append(decoded);
        else body.append("\n目前尚未捕获到解码后的验证链接；实际进入身份验证并完成解码后，提醒邮件会包含该 HTTP(S) 链接。");
        sendAsync("RedFingerGameBot 邮件测试", body.toString(), callback);
    }

    /** True when SMTP host/from/to and the encrypted password are all stored. */
    public boolean isConfigured() {
        return !prefs.getString("host", "").isEmpty()
                && !prefs.getString("from", "").isEmpty()
                && !prefs.getString("to", "").isEmpty()
                && !prefs.getString("password", "").isEmpty();
    }

    /** Generic alert used by the album script (fire-and-forget, same retry policy as other alerts). */
    public void sendAlertAsync(String subject, String body) {
        sendAsync(subject, body, null);
    }

    private void sendAsync(String subject, String body) {
        sendAsync(subject, body, null);
    }

    private void sendAsync(String subject, String body, SendCallback callback) {
        new Thread(() -> {
            Exception last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    send(subject, body);
                    android.util.Log.i("RFG-Email", "邮件发送成功：" + subject);
                    if (callback != null) callback.onResult(true, "邮件发送成功");
                    return;
                } catch (Exception e) {
                    last = e;
                    android.util.Log.e("RFG-Email", "邮件发送失败，第 " + attempt + " 次：" + subject, e);
                    if (attempt < 3) {
                        try { Thread.sleep(30_000L); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            if (callback != null) callback.onResult(false, "邮件发送已取消");
                            return;
                        }
                    }
                }
            }
            String detail = last == null ? "未知错误" : (last.getMessage() == null ? last.toString() : last.getMessage());
            android.util.Log.e("RFG-Email", "邮件重试仍失败，请检查 SMTP 设置：" + detail, last);
            if (callback != null) callback.onResult(false, "发送失败：" + detail);
        }, "RFG-Email-Sender").start();
    }

    private void send(String subject, String body) throws Exception {
        String host = prefs.getString("host", "");
        int port = prefs.getInt("port", 587);
        String from = prefs.getString("from", "");
        String to = prefs.getString("to", "");
        String encrypted = prefs.getString("password", "");
        if (host.isEmpty() || from.isEmpty() || to.isEmpty() || encrypted.isEmpty())
            throw new IllegalStateException("尚未配置邮件提醒");
        String password = decrypt(context, encrypted);
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        boolean implicitSsl = port == 465;
        props.put("mail.smtp.starttls.enable", implicitSsl ? "false" : "true");
        props.put("mail.smtp.ssl.enable", implicitSsl ? "true" : "false");
        props.put("mail.smtp.ssl.trust", host);
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        Session session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password);
            }
        });
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        Transport.send(message);
    }

    private static String encrypt(Context c, String value) throws Exception {
        SecretKey key = getKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(encrypted, 0, all, iv.length, encrypted.length);
        return Base64.encodeToString(all, Base64.NO_WRAP);
    }

    private static String decrypt(Context c, String value) throws Exception {
        byte[] all = Base64.decode(value, Base64.NO_WRAP);
        byte[] iv = new byte[12];
        System.arraycopy(all, 0, iv, 0, 12);
        byte[] encrypted = new byte[all.length - 12];
        System.arraycopy(all, 12, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
