package com.gdal.openfilegdb.io;

import com.gdal.openfilegdb.exception.GdbWriteException;
import java.util.Arrays;

/**
 * 可增长的顺序字节写入器(小端)。
 *
 * <p>对标 GDAL 写路径里对 {@code std::vector<GByte>} 的
 * {@code insert}/{@code push_back} 序列(见
 * {@code filegdbtable_write_fields.cpp::FileGDBTable::CreateField}
 * 与 {@code filegdbtable_write.cpp::FileGDBTable::CreateFeature}),
 * 用来把一条记录体先攒在内存里,再算出总长并补上 {@code uint32} 长度前缀。
 */
public final class ByteWriter {

    private byte[] buf;
    private int size;

    /** 默认初始容量 256 字节(足够容纳绝大多数记录体)。 */
    public ByteWriter() {
        this(256);
    }

    /**
     * @param initialCapacity 初始容量
     */
    public ByteWriter(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
    }

    /** 已写入的字节数。 */
    public int size() {
        return size;
    }

    private void ensure(int extra) {
        if (size + extra > buf.length) {
            int cap = buf.length;
            while (cap < size + extra) {
                cap = cap + (cap >> 1) + 16;
            }
            buf = Arrays.copyOf(buf, cap);
        }
    }

    /** 写 1 字节。 */
    public void u8(int v) {
        ensure(1);
        buf[size++] = (byte) v;
    }

    /** 写 2 字节无符号。 */
    public void u16(int v) {
        ensure(2);
        buf[size++] = (byte) v;
        buf[size++] = (byte) (v >>> 8);
    }

    /** 写 2 字节有符号。 */
    public void i16(short v) {
        u16(v & 0xFFFF);
    }

    /** 写 4 字节无符号。 */
    public void u32(long v) {
        ensure(4);
        buf[size++] = (byte) v;
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) (v >>> 16);
        buf[size++] = (byte) (v >>> 24);
    }

    /** 写 4 字节有符号。 */
    public void i32(int v) {
        u32(v & 0xFFFFFFFFL);
    }

    /** 写 8 字节无符号。 */
    public void u64(long v) {
        ensure(8);
        buf[size++] = (byte) v;
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) (v >>> 16);
        buf[size++] = (byte) (v >>> 24);
        buf[size++] = (byte) (v >>> 32);
        buf[size++] = (byte) (v >>> 40);
        buf[size++] = (byte) (v >>> 48);
        buf[size++] = (byte) (v >>> 56);
    }

    /** 写 8 字节有符号。 */
    public void i64(long v) {
        u64(v);
    }

    /** 写 4 字节浮点。 */
    public void f32(float v) {
        u32(Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
    }

    /** 写 8 字节浮点。 */
    public void f64(double v) {
        u64(Double.doubleToRawLongBits(v));
    }

    /** 写入原始字节。 */
    public void raw(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, buf, size, b.length);
        size += b.length;
    }

    /** 写入原始字节的指定片段。 */
    public void raw(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, size, len);
        size += len;
    }

    /** 写入 {@code n} 个 0 字节(字段描述区里占位空间用)。 */
    public void zeros(int n) {
        ensure(n);
        Arrays.fill(buf, size, size + n, (byte) 0);
        size += n;
    }

    /** 写一个 unsigned LEB128。 */
    public void varUInt(long v) {
        VarInt.write(this, v);
    }

    /**
     * 写 {@code [uint8 码元数][UTF-16LE]}(字段名 / 别名)。
     *
     * @param s 字符串
     * @throws GdbWriteException 码元数超过 255
     */
    public void utf16Prefixed(String s) {
        int units = Utf16.units(s);
        if (units > 255) {
            throw new GdbWriteException("UTF-16 字符串过长 (" + units + " 码元): " + s);
        }
        u8(units);
        raw(Utf16.encode(s));
    }

    /**
     * 写裸 UTF-16LE(调用方自己写长度前缀)。几何字段的 WKT 用。
     *
     * @param s 字符串
     */
    public void utf16Bytes(String s) {
        raw(Utf16.encode(s));
    }

    /**
     * 把 {@code buf[off, off+len)} 这段区间用零填充(用于后续回填)。
     *
     * @param off 起始
     * @param len 长度
     */
    public void patchZeros(int off, int len) {
        Arrays.fill(buf, off, off + len, (byte) 0);
    }

    /**
     * 在指定位置覆盖写入 4 字节无符号整数(尾部回填长度字段用)。
     *
     * @param off 位置
     * @param v   值
     */
    public void patchU32(int off, long v) {
        buf[off] = (byte) v;
        buf[off + 1] = (byte) (v >>> 8);
        buf[off + 2] = (byte) (v >>> 16);
        buf[off + 3] = (byte) (v >>> 24);
    }

    /** 导出为字节数组(拷贝)。 */
    public byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }

    /** 直接暴露内部数组(长度为容量,有效数据为前 {@link #size()} 字节)。 */
    public byte[] internalBuffer() {
        return buf;
    }
}
