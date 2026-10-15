package com.gdal.openfilegdb.util;

import com.gdal.openfilegdb.exception.GdbFormatException;

/**
 * GUID / GLOBALID(16 字节)的文本化与解析。
 *
 * <p>FileGDB 存的 16 字节 GUID 是 <b>前 4 / 2 / 2 字节各自反向、后 8 字节正序</b>
 * 的"小端混合序"。这来自 Win32 {@code GUID} 结构体
 * ({@code Data1=DWORD, Data2=WORD, Data3=WORD, Data4=BYTE[8]})在
 * 小端机器上的内存布局。
 *
 * <p>对标 GDAL {@code filegdbtable.cpp} 中格式化 GUID 时使用的索引序列
 * {@code {3,2,1,0, 5,4, 7,6, 8,9,10,11, 12,...,15}}。
 */
public final class GuidUtil {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private GuidUtil() {
    }

    /**
     * 16 字节 → {@code {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}}(大写,带花括号)。
     *
     * @param b   缓冲区
     * @param off 起始偏移
     * @return 形如 {@code {7A5A0D1C-...}} 的字符串
     */
    public static String format(byte[] b, int off) {
        // 索引顺序: 3,2,1,0, 5,4, 7,6, 8,9,10,11, 12,13,14,15
        int[] order = {3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15};
        StringBuilder sb = new StringBuilder(38);
        sb.append('{');
        for (int i = 0; i < 16; i++) {
            int v = b[off + order[i]] & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
            if (i == 3 || i == 5 || i == 7 || i == 9) {
                sb.append('-');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 文本 GUID → 16 字节(小端混合序)。
     *
     * @param text 形如 {@code {7A5A0D1C-...}} 或 {@code 7a5a0d1c...} 的字符串
     * @return 16 字节
     * @throws GdbFormatException 长度非法
     */
    public static byte[] parse(String text) {
        String s = text.trim();
        if (s.startsWith("{")) {
            s = s.substring(1);
        }
        if (s.endsWith("}")) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.replace("-", "");
        if (s.length() != 32) {
            throw new GdbFormatException("GUID 长度非法: " + text);
        }
        byte[] raw = new byte[16];
        for (int i = 0; i < 16; i++) {
            raw[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        // 反向套用上面的索引顺序
        int[] order = {3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15};
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[order[i]] = raw[i];
        }
        return out;
    }
}
