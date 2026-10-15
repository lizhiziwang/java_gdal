package com.gdal.openfilegdb.io;

/**
 * 小端序标量读取的静态工具集。
 *
 * <p>对应 GDAL {@code filegdbtable_priv.h} 里的 {@code GetUInt32}、
 * {@code GetInt32}、{@code GetFloat64} 等宏 —— 这些宏内部只做
 * {@code memcpy} 后按本机序解释,而 FileGDB 全格式固定为
 * <b>little-endian</b>,因此本工程统一在这里显式按小端组装。
 *
 * <p>所有方法都不做边界检查(由 {@link ByteReader} 负责),因此在直接调用前
 * 必须自行确保 {@code byte[]} 足够长。这样做是为了让"热路径"里不出现
 * 多余的比较分支,与 C++ 宏的行为完全一致。
 */
public final class LittleEndian {

    private LittleEndian() {
    }

    /** 读无符号 16 位整数。 */
    public static int getUInt16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** 读有符号 16 位整数。 */
    public static short getInt16(byte[] b, int off) {
        return (short) getUInt16(b, off);
    }

    /** 读无符号 32 位整数(以 long 承载,避免高位被当成负数)。 */
    public static long getUInt32(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    /** 读有符号 32 位整数。 */
    public static int getInt32(byte[] b, int off) {
        return (int) getUInt32(b, off);
    }

    /** 读有符号 64 位整数。 */
    public static long getInt64(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24)
                | ((b[off + 4] & 0xFFL) << 32)
                | ((b[off + 5] & 0xFFL) << 40)
                | ((b[off + 6] & 0xFFL) << 48)
                | ((b[off + 7] & 0xFFL) << 56);
    }

    /**
     * 读 64 位无符号整数。
     *
     * <p>Java 没有无符号 64 位整型,FileGDB 里需要它的地方(v4 头部的记录数、
     * 文件大小、字段描述区偏移)实际取值都远小于 2^63,因此直接用 {@code long}
     * 承载,并在结果为负(即最高位被置位)时由调用方判定为非法。
     */
    public static long getUInt64(byte[] b, int off) {
        return getInt64(b, off);
    }

    /** 读 32 位浮点。 */
    public static float getFloat32(byte[] b, int off) {
        return Float.intBitsToFloat((int) getUInt32(b, off));
    }

    /** 读 64 位浮点。 */
    public static double getFloat64(byte[] b, int off) {
        return Double.longBitsToDouble(getInt64(b, off));
    }

    /**
     * 读任意字节数(4/5/6)的小端无符号整数,零扩展。
     *
     * <p>对应 GDAL {@code FileGDBTable::GetOffsetInTableForRow} 里按
     * {@code m_nTablxOffsetSize} 逐字节拼偏移的逻辑
     * (见 {@code filegdbtable.cpp::GetOffsetInTableForRow})。
     *
     * @param b     缓冲区
     * @param off   起始偏移
     * @param size  字节数,取值 4/5/6
     * @return 无符号整数值
     */
    public static long getUnsignedOfSize(byte[] b, int off, int size) {
        long v = 0;
        for (int i = size - 1; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }
}
