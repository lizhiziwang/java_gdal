package com.gdal.openfilegdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gdal.openfilegdb.exception.GdbFormatException;
import com.gdal.openfilegdb.io.ByteReader;
import com.gdal.openfilegdb.io.ByteWriter;
import com.gdal.openfilegdb.io.VarInt;
import com.gdal.openfilegdb.util.BitArray;
import com.gdal.openfilegdb.util.GdbCollections;
import com.gdal.openfilegdb.util.GdbDateTimeUtil;
import com.gdal.openfilegdb.util.GdbNumbers;
import com.gdal.openfilegdb.util.GuidUtil;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 底层工具层的单元测试:变长整数、UTF-16、GUID、日期、位图。
 *
 * <p>这些是所有上层解析的基础,它们的正确性直接决定了对二进制格式的对齐程度。
 */
class IoUtilTest {

    // ------------------------------------------------------------------
    // unsigned LEB128
    // ------------------------------------------------------------------

    @Test
    @DisplayName("varuint 编解码往返(含单字节快路径与多字节)")
    void varUIntRoundTrip() {
        long[] samples = {0, 1, 127, 128, 255, 16383, 16384, 1_000_000, Integer.MAX_VALUE,
                4_294_967_295L, Long.MAX_VALUE};
        for (long v : samples) {
            ByteWriter w = new ByteWriter();
            VarInt.write(w, v);
            byte[] bytes = w.toByteArray();
            assertEquals(VarInt.size(v), bytes.length, "varuint 长度不符: " + v);
            long[] r = VarInt.read(bytes, 0, bytes.length);
            assertEquals(v, r[0], "varuint 值不符: " + v);
            assertEquals(bytes.length, (int) r[1], "varuint 读取位置不符: " + v);
        }
        // 与 GDAL WriteVarUInt 的经典编码逐字节比对
        ByteWriter w = new ByteWriter();
        VarInt.write(w, 300);
        assertEquals(2, w.size());
        assertEquals((byte) 0xAC, w.internalBuffer()[0]); // 300 = 0b1_0010_1100
        assertEquals((byte) 0x02, w.internalBuffer()[1]);
    }

    @Test
    @DisplayName("varuint 越界抛 GdbFormatException")
    void varUIntTruncated() {
        byte[] truncated = {(byte) 0x80, (byte) 0x80}; // 两个续字节但没结束
        assertThrows(GdbFormatException.class, () -> VarInt.read(truncated, 0, truncated.length));
    }

    // ------------------------------------------------------------------
    // GUID
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GUID 文本 <-> 16 字节小端混合序往返")
    void guidRoundTrip() {
        String text = "{70737809-852C-4A03-9E22-2CECEA5B9BFA}";
        byte[] raw = GuidUtil.parse(text);
        assertEquals(16, raw.length);
        assertEquals(text, GuidUtil.format(raw, 0));
        // 前 4 字节应按小端反转: GUID 以 0x70737809 开头 → 落盘为 09 78 73 70
        assertEquals(0x09, raw[0] & 0xFF);
        assertEquals(0x78, raw[1] & 0xFF);
        assertEquals(0x73, raw[2] & 0xFF);
        assertEquals(0x70, raw[3] & 0xFF);
    }

    // ------------------------------------------------------------------
    // 日期时间
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FileGDB 天数 <-> LocalDateTime 往返")
    void dateTimeRoundTrip() {
        LocalDateTime dt = LocalDateTime.of(2024, 6, 15, 13, 45, 30);
        double days = GdbDateTimeUtil.dateTimeToDays(dt);
        LocalDateTime back = GdbDateTimeUtil.daysToDateTime(days);
        assertEquals(dt, back);
        // 基准点:1899-12-30 UTC 对应 0 天
        assertEquals(0.0,
                GdbDateTimeUtil.dateTimeToDays(LocalDateTime.of(1899, 12, 30, 0, 0)), 1e-9);
    }

    @Test
    @DisplayName("ESRI NaN 位型与 Java NaN 不同(最低有效位差 1)")
    void esriNaN() {
        assertEquals(0x7FF8000000000001L, GdbDateTimeUtil.ESRI_NAN_BITS);
        assertEquals(0x7FF8000000000000L, Double.doubleToRawLongBits(Double.NaN));
        assertTrue(Double.isNaN(GdbDateTimeUtil.ESRI_NAN));
        // NaN 表示"空日期"
        assertEquals(null, GdbDateTimeUtil.daysToDateTime(GdbDateTimeUtil.ESRI_NAN));
    }

    // ------------------------------------------------------------------
    // 位图
    // ------------------------------------------------------------------

    @Test
    @DisplayName("位图 LSB-first 置位 / 取值 / 清位")
    void bitArray() {
        byte[] bits = BitArray.create(10);
        assertEquals(2, bits.length);
        BitArray.set(bits, 0);
        BitArray.set(bits, 9);
        assertTrue(BitArray.test(bits, 0));
        assertTrue(BitArray.test(bits, 9));
        assertFalse(BitArray.test(bits, 1));
        assertEquals((byte) 0x01, bits[0]);
        assertEquals((byte) 0x02, bits[1]); // 第 9 位 = 第 1 字节的第 1 位
        BitArray.clear(bits, 0);
        assertFalse(BitArray.test(bits, 0));
    }

    // ------------------------------------------------------------------
    // 字节游标
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ByteReader 小端读取与 UTF-16 前缀字符串")
    void byteReader() {
        ByteWriter w = new ByteWriter();
        w.u8(0xAB);
        w.u16(0x1234);
        w.i32(-2);
        w.f64(1.5);
        w.utf16Prefixed("字段");
        byte[] buf = w.toByteArray();

        ByteReader r = new ByteReader(buf);
        assertEquals(0xAB, r.u8());
        assertEquals(0x1234, r.u16());
        assertEquals(-2, r.i32());
        assertEquals(1.5, r.f64(), 0.0);
        assertEquals("字段", r.utf16Prefixed());
        assertEquals(0, r.remaining());

        // 越界要抛异常而不是静默返回 0
        assertThrows(GdbFormatException.class, r::u8);
    }

    // ------------------------------------------------------------------
    // Java 8 集合工厂 shim
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GdbCollections:不可变、保序、可含 null")
    void gdbCollections() {
        List<String> l = GdbCollections.list("a", "b", "a");
        assertEquals(Arrays.asList("a", "b", "a"), l);
        assertThrows(UnsupportedOperationException.class, () -> l.add("c"));

        // 空参调用必须返回空表(而不是 null)
        assertTrue(GdbCollections.list().isEmpty());
        assertTrue(GdbCollections.set().isEmpty());
        assertTrue(GdbCollections.ofEntries().isEmpty());

        // set 去重且保持插入顺序
        Set<String> s = GdbCollections.set("b", "a", "b");
        assertEquals(Arrays.asList("b", "a"), new ArrayList<>(s));
        assertThrows(UnsupportedOperationException.class, () -> s.add("c"));

        // map 保序,重复键以后者为准
        Map<String, String> m = GdbCollections.ofEntries(
                GdbCollections.entry("k1", "v1"),
                GdbCollections.entry("k2", "v2"),
                GdbCollections.entry("k1", "v1b"));
        assertEquals(2, m.size());
        assertEquals("v1b", m.get("k1"));
        assertEquals(Arrays.asList("k1", "k2"), new ArrayList<>(m.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> m.put("k3", "v3"));

        // 与 List.of 不同:允许 null(样板行数据需要用它占位)
        assertEquals(1, GdbCollections.list((String) null).size());

        // copyOfX 生成独立副本
        List<String> src = new ArrayList<>(Arrays.asList("x", "y"));
        List<String> copyL = GdbCollections.copyOfList(src);
        Set<String> copyS = GdbCollections.copyOfSet(src);
        src.add("z");
        assertEquals(2, copyL.size());
        assertEquals(2, copyS.size());
    }

    @Test
    @DisplayName("日期:double 天数往返的亚秒误差按 GDAL 规则吸附到整秒")
    void dateSnapToSecond() {
        // 这条用例锁定 GDAL filegdbtable.cpp:1873 那条 fmod(dfSeconds,1)>1-1e-4 的吸附规则。
        // 不吸附时,下面这些整秒值会读成 xxx.999999523。
        LocalDateTime[] samples = {
                LocalDateTime.of(2024, 3, 8, 10, 30, 15),
                LocalDateTime.of(1949, 10, 1, 0, 0, 0),
                LocalDateTime.of(1999, 12, 31, 23, 59, 59),
                LocalDateTime.of(1970, 1, 1, 0, 0, 0),
        };
        for (LocalDateTime dt : samples) {
            double days = GdbDateTimeUtil.dateTimeToDays(dt);
            assertEquals(dt, GdbDateTimeUtil.daysToDateTime(days), "整秒往返应精确: " + dt);
        }

        // 带小数的值不能整秒化:小数部分离整秒较远时只保留 double 天数的固有舍入,
        // 实测误差在 1 微秒以内(格式本身就把时间存成"天"的 double,精度到不了纳秒)。
        LocalDateTime far = LocalDateTime.of(2024, 3, 8, 10, 30, 15, 99_900_000);
        LocalDateTime farBack = GdbDateTimeUtil.daysToDateTime(
                GdbDateTimeUtil.dateTimeToDays(far));
        long farDeltaNanos = Math.abs(ChronoUnit.NANOS.between(far, farBack));
        assertTrue(farDeltaNanos <= 1_000_000L,
                "亚毫秒精度应保留(误差 <= 1ms),实得 " + farDeltaNanos + "ns");
        assertEquals(15, farBack.getSecond(), "秒数不应被贴着整秒的规则改动");
        assertEquals(99, farBack.getNano() / 1_000_000, "毫秒部分应为 99");

        // 而"离下一个整秒不到 1e-4 秒"的值会被吸附上去(与 GDAL 同款行为):
        // 15.99999 秒 -> 16 秒
        LocalDateTime justBelow = LocalDateTime.of(2024, 3, 8, 10, 30, 15, 999_990_000);
        assertEquals(LocalDateTime.of(2024, 3, 8, 10, 30, 16),
                GdbDateTimeUtil.daysToDateTime(GdbDateTimeUtil.dateTimeToDays(justBelow)));
    }

    @Test
    @DisplayName("数值文本化:永不输出科学计数法,且精度不丢")
    void plainNumber() {
        // 整值不带小数点
        assertEquals("0", GdbNumbers.plain(0.0));
        assertEquals("-5", GdbNumbers.plain(-5.0));
        assertEquals("500000", GdbNumbers.plain(500000.0));
        // 会触发 Double.toString 科学计数法的量级必须被展平
        assertEquals("0.0000123", GdbNumbers.plain(0.0000123));
        assertEquals("-0.00000015", GdbNumbers.plain(-1.5e-7));
        assertEquals("12345678.9", GdbNumbers.plain(12345678.9));
        assertEquals("-45000000.25", GdbNumbers.plain(-45000000.25));
        assertEquals("100000000000000000000", GdbNumbers.plain(1e20));
        assertEquals("0.00000000000000000001", GdbNumbers.plain(1e-20));
        // NaN / 无穷保持字面量
        assertEquals("NaN", GdbNumbers.plain(Double.NaN));
        assertEquals("Infinity", GdbNumbers.plain(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", GdbNumbers.plain(Double.NEGATIVE_INFINITY));

        // 任何输出都不该含 E/e,且 parse 回来必须与原值逐位相同(shortest-round-trip 不被破坏)
        double[] samples = {0.0000123, -1.5e-7, 12345678.9, -45000000.25, 1e20, 1e-20,
                20037508.342789244, 113.26440000000002, Double.MIN_VALUE, Double.MAX_VALUE,
                1.0 / 3.0, -0.0};
        for (double v : samples) {
            String text = GdbNumbers.plain(v);
            assertFalse(text.indexOf('E') >= 0 || text.indexOf('e') >= 0,
                    "不应出现科学计数法: " + v + " -> " + text);
            assertEquals(v, Double.parseDouble(text), 0.0,
                    "文本化后应能原值还原: " + v + " -> " + text);
        }
    }
}
