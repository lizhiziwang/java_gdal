package com.gdal.openfilegdb.util;

/**
 * LSB-first 位图工具。
 *
 * <p>FileGDB 的空值位图(null value bitmap)与 {@code .gdbtablx} 的 base block map
 * 都使用 <b>最低有效位优先</b> 的约定:第 {@code i} 位位于
 * {@code data[i >> 3]} 的第 {@code (i & 7)} 位。
 *
 * <p>对标 GDAL {@code filegdbtable_priv.h} 里的宏:
 * <pre>
 *   #define BIT_ARRAY_SIZE_IN_BYTES(bit_count) (((bit_count) + 7) / 8)
 *   #define TEST_BIT(array, bit) ((array)[(bit) >> 3] & (1 << ((bit) & 7)))
 *   #define SET_BIT(array, bit) ((array)[(bit) >> 3] |= (1 << ((bit) & 7)))
 * </pre>
 */
public final class BitArray {

    private BitArray() {
    }

    /**
     * 容纳 {@code bitCount} 个位所需的字节数。
     *
     * @param bitCount 位数
     * @return 字节数
     */
    public static int sizeInBytes(int bitCount) {
        return (bitCount + 7) / 8;
    }

    /**
     * 读第 {@code idx} 位(LSB-first)。
     *
     * @param data 位图
     * @param idx  位下标
     * @return 该位是否为 1
     */
    public static boolean test(byte[] data, int idx) {
        return (data[idx >> 3] & (1 << (idx & 7))) != 0;
    }

    /**
     * 置位第 {@code idx} 位(LSB-first)。
     *
     * @param data 位图(就地修改)
     * @param idx  位下标
     */
    public static void set(byte[] data, int idx) {
        data[idx >> 3] |= (byte) (1 << (idx & 7));
    }

    /**
     * 清位第 {@code idx} 位(LSB-first)。
     *
     * @param data 位图(就地修改)
     * @param idx  位下标
     */
    public static void clear(byte[] data, int idx) {
        data[idx >> 3] &= (byte) ~(1 << (idx & 7));
    }

    /**
     * 新建一个全 0 的位图。
     *
     * @param bitCount 位数
     * @return 位图数组
     */
    public static byte[] create(int bitCount) {
        return new byte[sizeInBytes(bitCount)];
    }
}
