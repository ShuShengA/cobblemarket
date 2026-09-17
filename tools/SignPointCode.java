import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * CobbleMarket RP「外部签名码」签发器（单文件、零依赖、Java 11+）。
 *
 * 用法：
 *   java SignPointCode.java &lt;secret&gt; &lt;面额&gt; &lt;数量&gt; [有效天数] [serial十六进制]
 *
 * 例：
 *   java SignPointCode.java my-secret-key 100 5            # 生成 5 张 100 点的码，不过期
 *   java SignPointCode.java my-secret-key 100 1 30         # 1 张、30 天有效
 *   java SignPointCode.java my-secret-key 100 1 30 0a1b2c3d4e5f   # 指定 serial（自检用）
 *
 * ⚠ secret 必须与服务器 config/cobblemarket.json 里的 pointsExternalSecret 一致，
 *   **不要把它写进任何公开的地方**（码 = 钱）。
 *
 * 协议（20 字节 = 32 个 Crockford Base32 字符，见 docs/points_plan.md「外部签名码协议」）：
 *   [0]      version = 0x01
 *   [1..6]   serial 48bit（SecureRandom，防重放）
 *   [7..10]  amount 大端 int（RP 数，必须 > 0）
 *   [11..12] expiresDays 大端 ushort（自 2026-01-01 UTC 起的天数；0 = 不过期）
 *   [13]     flags = 0
 *   [14]     reserved = 0
 *   [15..19] HMAC-SHA256(secret, payload[0..15)) 的前 5 字节
 */
public class SignPointCode {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford Base32
    private static final long EPOCH_SECONDS = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
    private static final long MS_PER_DAY = 86_400_000L;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: java SignPointCode.java <secret> <面额> <数量> [有效天数] [serial十六进制]");
            System.exit(1);
        }
        String secret = args[0];
        int amount = Integer.parseInt(args[1]);
        int count = Integer.parseInt(args[2]);
        int days = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        String fixedSerial = args.length > 4 ? args[4] : null;

        if (amount <= 0) {
            System.err.println("面额必须大于 0");
            System.exit(1);
        }
        if (count <= 0) {
            System.err.println("数量必须大于 0");
            System.exit(1);
        }
        // expiresDays：自 2026-01-01 UTC 起的天数；0 = 不过期
        long expiresDays = 0;
        if (days > 0) {
            long targetMs = System.currentTimeMillis() + days * MS_PER_DAY;
            expiresDays = targetMs / MS_PER_DAY - EPOCH_SECONDS / 86400;
            if (expiresDays > 0xFFFF) {
                System.err.println("有效期过长（超过协议上限）");
                System.exit(1);
            }
        }

        SecureRandom random = new SecureRandom();
        for (int i = 0; i < count; i++) {
            byte[] serial = new byte[6];
            if (fixedSerial != null) {
                System.arraycopy(HexFormat.of().parseHex(fixedSerial), 0, serial, 0, 6);
            } else {
                random.nextBytes(serial);
            }
            System.out.println(format(encode(sign(secret, serial, amount, expiresDays))));
        }
    }

    private static byte[] sign(String secret, byte[] serial, int amount, long expiresDays) throws Exception {
        ByteBuffer payload = ByteBuffer.allocate(20);
        payload.put((byte) 0x01);                  // version
        payload.put(serial);                       // serial 6 字节
        payload.putInt(amount);                    // amount 大端 4 字节
        payload.putShort((short) expiresDays);     // expiresDays 大端 2 字节
        payload.put((byte) 0);                     // flags
        payload.put((byte) 0);                     // reserved

        byte[] payloadBytes = payload.array();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(payloadBytes, 0, 15);                  // 签名输入是**原始 15 字节**
        byte[] full = mac.doFinal();
        System.arraycopy(full, 0, payloadBytes, 15, 5);   // 取前 5 字节
        return payloadBytes;
    }

    /** 20 字节 → 32 个 Base32 字符（无 padding） */
    private static String encode(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    /** 按 4 位一组加连字符（8 组），方便抄写 */
    private static String format(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i += 4) {
            if (i > 0) sb.append('-');
            sb.append(code, i, Math.min(i + 4, code.length()));
        }
        return sb.toString();
    }
}
