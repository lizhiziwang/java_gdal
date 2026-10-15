package com.gdal.openfilegdb.io;

import com.gdal.openfilegdb.exception.GdbFormatException;

/**
 * 带边界检查的顺序字节读取器(小端)。
 *
 * <p>用于解析字段描述区与记录体。与 GDAL 里"一个 {@code const GByte*} 游标 +
 * 一组长度的裸指针算术"等价,但把边界检查集中到 {@link #need(int)},
 * 避免 C++ 里那些散落的 {@code if (pabyIter + n > pabyEnd)} 判断
 * (见 {@code filegdbtable.cpp::FileGDBTable::Open} 与 {@code ::SelectRow})。
 *
 * <p><b>约定</b>:缓冲区尾部允许有零填充(对应 GDAL 的
 * {@code ZEROES_AFTER_END_OF_BUFFER}),因此 {@code data.length} 可以大于
 * {@code end};所有越界判定都以 {@code end} 为准。
 */
public final class ByteReader {

    private final byte[] data;
    private final int end;
    private int pos;

    /**
     * 构造一个覆盖整个数组的读取器。
     *
     * @param data 缓冲区
     */
    public ByteReader(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * @param data 缓冲区
     * @param pos  起始位置
     * @param end  有效数据结束位置(不含)
     */
    public ByteReader(byte[] data, int pos, int end) {
        this.data = data;
        this.pos = pos;
        this.end = end;
    }

    /** 剩余可读字节数。 */
    public int remaining() {
        return end - pos;
    }

    /** 当前游标位置。 */
    public int position() {
        return pos;
    }

    /** 有效数据结束位置。 */
    public int end() {
        return end;
    }

    /** 底层缓冲区。 */
    public byte[] data() {
        return data;
    }

    /**
     * 直接设置游标位置(不做越界检查,调用方负责)。
     *
     * @param newPos 新位置
     */
    public void position(int newPos) {
        this.pos = newPos;
    }

    /**
     * 断言还有 {@code n} 字节可读。
     *
     * @param n 需要的字节数
     * @throws GdbFormatException 剩余不足
     */
    public void need(int n) {
        if (remaining() < n) {
            throw new GdbFormatException("缓冲区不足: 需要 " + n + " 字节, 只剩 " + remaining());
        }
    }

    /** 读 1 字节无符号。 */
    public int u8() {
        need(1);
        return data[pos++] & 0xFF;
    }

    /** 读 2 字节无符号。 */
    public int u16() {
        need(2);
        int v = LittleEndian.getUInt16(data, pos);
        pos += 2;
        return v;
    }

    /** 读 2 字节有符号。 */
    public short i16() {
        need(2);
        short v = LittleEndian.getInt16(data, pos);
        pos += 2;
        return v;
    }

    /** 读 4 字节无符号(以 long 承载)。 */
    public long u32() {
        need(4);
        long v = LittleEndian.getUInt32(data, pos);
        pos += 4;
        return v;
    }

    /** 读 4 字节有符号。 */
    public int i32() {
        need(4);
        int v = LittleEndian.getInt32(data, pos);
        pos += 4;
        return v;
    }

    /** 读 8 字节无符号(以 long 承载)。 */
    public long u64() {
        need(8);
        long v = LittleEndian.getUInt64(data, pos);
        pos += 8;
        return v;
    }

    /** 读 8 字节有符号。 */
    public long i64() {
        need(8);
        long v = LittleEndian.getInt64(data, pos);
        pos += 8;
        return v;
    }

    /** 读 4 字节浮点。 */
    public float f32() {
        need(4);
        float v = LittleEndian.getFloat32(data, pos);
        pos += 4;
        return v;
    }

    /** 读 8 字节浮点。 */
    public double f64() {
        need(8);
        double v = LittleEndian.getFloat64(data, pos);
        pos += 8;
        return v;
    }

    /**
     * 跳过 {@code n} 字节。
     *
     * @param n 字节数
     */
    public void skip(int n) {
        need(n);
        pos += n;
    }

    /** 读一个 unsigned LEB128 变长整数。 */
    public long varUInt() {
        long[] r = VarInt.read(data, pos, end);
        pos = (int) r[1];
        return r[0];
    }

    /**
     * 读 {@code [uint8 码元数][UTF-16LE]} 形式的字符串(字段名 / 别名)。
     */
    public String utf16Prefixed() {
        int n = u8();
        return utf16Units(n);
    }

    /**
     * 读 {@code n} 个 UTF-16 码元。
     *
     * @param codeUnits 码元个数
     * @return 字符串
     */
    public String utf16Units(int codeUnits) {
        int nbytes = codeUnits * 2;
        need(nbytes);
        String s = Utf16.decode(data, pos, nbytes);
        pos += nbytes;
        return s;
    }

    /**
     * 读 {@code nbytes} 字节的裸 UTF-16LE(几何字段的 WKT 用)。
     *
     * @param nbytes 字节数(通常为偶数)
     * @return 字符串
     */
    public String utf16Bytes(int nbytes) {
        need(nbytes);
        String s = Utf16.decode(data, pos, nbytes);
        pos += nbytes;
        return s;
    }

    /**
     * 读 {@code n} 字节的副本。
     *
     * @param n 字节数
     * @return 新的字节数组
     */
    public byte[] bytes(int n) {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }
}
