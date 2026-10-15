package com.gdal.openfilegdb.io;

import com.gdal.openfilegdb.exception.GdbFormatException;

/**
 * unsigned LEB128 变长整数编解码。
 *
 * <p>FileGDB 记录体里所有变长字段(STRING / XML / GEOGRAPHY / BINARY / RASTER)
 * 的长度前缀都是 unsigned LEB128:低 7 位为数据,最高位表示"还有后续字节"。
 *
 * <p>对标:
 * <ul>
 *   <li>{@code filegdbtable.cpp::FileGDBTable::ReadVarUInt} —— 读取</li>
 *   <li>{@code filegdbtable_priv.h::WriteVarUInt} —— 写入</li>
 * </ul>
 */
public final class VarInt {

    /** unsigned LEB128 最多 10 字节(64 位)。 */
    public static final int MAX_LENGTH = 10;

    private VarInt() {
    }

    /**
     * 读取一个 unsigned LEB128。
     *
     * <p>结果以 {@code long} 承载。FileGDB 中长度字段的实际取值远小于 2^63,
     * 因此不会有符号问题。
     *
     * @param data 缓冲区
     * @param pos  起始位置
     * @param end  缓冲区有效数据结束位置
     * @return 长度为 2 的数组:{@code [0] = 值, [1] = 新位置}
     * @throws GdbFormatException 越界或编码超过 64 位
     */
    public static long[] read(byte[] data, int pos, int end) {
        if (pos >= end) {
            throw new GdbFormatException("readVarUInt: 读越界 (pos=" + pos + ", end=" + end + ")");
        }
        int b = data[pos] & 0xFF;
        if ((b & 0x80) == 0) {
            return new long[]{b, pos + 1L};
        }
        pos++;
        int shift = 7;
        long val = b & 0x7FL;
        while (true) {
            if (pos >= end) {
                throw new GdbFormatException("readVarUInt: 变长整数被截断");
            }
            b = data[pos] & 0xFF;
            pos++;
            val |= (b & 0x7FL) << shift;
            if ((b & 0x80) == 0) {
                return new long[]{val, pos};
            }
            shift += 7;
            if (shift >= 64) {
                throw new GdbFormatException("readVarUInt: 变长整数超过 64 位");
            }
        }
    }

    /**
     * 编码一个 unsigned LEB128 并写入输出缓冲区。
     *
     * @param out 输出
     * @param val 非负值
     * @throws IllegalArgumentException 传入负数
     */
    public static void write(ByteWriter out, long val) {
        if (val < 0) {
            throw new IllegalArgumentException("writeVarUInt 不接受负数: " + val);
        }
        while (true) {
            if (val >= 0x80L) {
                out.u8((int) (0x80L | (val & 0x7FL)));
                val >>>= 7;
            } else {
                out.u8((int) val);
                return;
            }
        }
    }

    /**
     * 写入 7 位分组到字节数组(用于预先计算长度的场景)。
     *
     * @param out 输出
     * @param val 非负值
     */
    public static void write(java.io.ByteArrayOutputStream out, long val) {
        if (val < 0) {
            throw new IllegalArgumentException("writeVarUInt 不接受负数: " + val);
        }
        while (true) {
            if (val >= 0x80L) {
                out.write((int) (0x80L | (val & 0x7FL)));
                val >>>= 7;
            } else {
                out.write((int) val);
                return;
            }
        }
    }

    /**
     * 计算编码 {@code val} 所需的字节数。
     *
     * <p>写记录体前需要先算出总长度以填充 Records 区里的 {@code uint32} 长度前缀,
     * 所以这个函数在写路径上很关键。对标 {@code WriteVarUInt} 的返回长度。
     */
    public static int size(long val) {
        if (val < 0) {
            throw new IllegalArgumentException("varUIntSize 不接受负数: " + val);
        }
        int n = 1;
        val >>>= 7;
        while (val != 0) {
            n++;
            val >>>= 7;
        }
        return n;
    }
}
