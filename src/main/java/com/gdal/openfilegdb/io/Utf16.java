package com.gdal.openfilegdb.io;

import com.gdal.openfilegdb.exception.GdbFormatException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

/**
 * UTF-16 编解码工具。
 *
 * <p>FileGDB 字段描述区里的字符串(字段名、别名)是
 * {@code [uint8 码元数][UTF-16LE 数据]};几何字段的 WKT 则是
 * {@code [uint16 字节数][UTF-16LE 数据]}。
 *
 * <p>对标 GDAL {@code filegdbtable.cpp} 中的 {@code ReadUTF16String} /
 * {@code StoreUTF16String}:它显式处理了 BOM 与代理对(部分 ArcGIS 产出的
 * 字段名带 BOM)。本类在解码前剥掉 BOM,并在遇到半截代理对时用替换字符
 * 兜底而不是抛异常 —— 与 GDAL 的行为一致(损坏的字符串不应让整张表读不出来)。
 */
public final class Utf16 {

    /** UTF-16LE 的字节序标记。 */
    private static final byte[] BOM_LE = {(byte) 0xFF, (byte) 0xFE};

    private Utf16() {
    }

    /**
     * 解码 UTF-16LE 字节串,自动跳过 BOM。
     *
     * @param raw 原始字节
     * @return 解码后的字符串;空输入返回空串
     */
    public static String decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        int off = 0;
        int len = raw.length;
        if (len >= 2 && (raw[0] == (byte) 0xFF && raw[1] == (byte) 0xFE
                || raw[0] == (byte) 0xFE && raw[1] == (byte) 0xFF)) {
            off = 2;
            len -= 2;
        }
        int use = len & ~1; // 只取偶数个字节,尾部孤立的 1 字节丢弃
        try {
            // 用 decoder 显式指定 REPLACE,避免孤立代理对抛异常
            CharsetDecoder dec = StandardCharsets.UTF_16LE.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            return dec.decode(ByteBuffer.wrap(raw, off, use)).toString();
        } catch (CharacterCodingException e) {
            // decoder 已设为 REPLACE,理论上不会走到这里
            return new String(raw, off, use, StandardCharsets.UTF_16LE);
        }
    }

    /**
     * 解码一段 UTF-16LE 字节串(长度不一定为偶数时会丢弃最后 1 字节)。
     *
     * @param raw  缓冲区
     * @param off  起始
     * @param len  字节数
     * @return 字符串
     */
    public static String decode(byte[] raw, int off, int len) {
        if (len <= 0) {
            return "";
        }
        int use = len & ~1;
        byte[] sub = new byte[use];
        System.arraycopy(raw, off, sub, 0, use);
        return decode(sub);
    }

    /** 编码为 UTF-16LE(不含 BOM)。 */
    public static byte[] encode(String text) {
        return text.getBytes(StandardCharsets.UTF_16LE);
    }

    /**
     * 字符串占多少个 UTF-16 码元(即字节数 / 2)。
     *
     * <p>字段描述区的长度前缀存的是<b>码元数</b>而不是字符数 ——
     * 补充平面字符(emoji 等)会占 2 个码元。对标 GDAL 里以
     * {@code wcslen()} 计算长度的写法。
     */
    public static int units(String text) {
        return text.getBytes(StandardCharsets.UTF_16LE).length / 2;
    }

    /**
     * 写 {@code [uint8 码元数][UTF-16LE]}。
     *
     * @param out  输出
     * @param text 字符串
     * @throws GdbFormatException 码元数超过 255
     */
    public static void writePrefixed(ByteWriter out, String text) {
        int units = units(text);
        if (units > 255) {
            throw new GdbFormatException(
                    "UTF-16 字符串过长 (" + units + " 码元): " + abbreviate(text));
        }
        out.u8(units);
        out.raw(encode(text));
    }

    /**
     * 读 {@code [uint8 码元数][UTF-16LE]}(由 {@link ByteReader} 组合实现)。
     */
    static String readPrefixed(ByteReader r) {
        int n = r.u8();
        return r.utf16Units(n);
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
