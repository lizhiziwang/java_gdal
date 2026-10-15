package com.gdal.openfilegdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.FileGdbGeometryCodec;
import com.gdal.openfilegdb.feature.GdbGeomField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 几何编解码测试:FileGDB 原生 blob ↔ {@link GdbGeometry} ↔ WKB。
 *
 * <p>覆盖点 / 线 / 面(含带洞多边形)/ 多点,以及 Z 维。
 */
class GdbGeometryTest {

    /** 构造一个与 GDAL/ArcGIS 默认量化参数一致的几何字段。 */
    private static GdbGeomField geomField() {
        GdbGeomField gf = new GdbGeomField("Shape");
        gf.setWkt("GEOGCS[\"GCS_WGS_1984\"]");
        gf.setXOrigin(-180.0).setYOrigin(-90.0).setXyScale(1e9).setXyTolerance(2e-6);
        gf.setZOrigin(-100000.0).setZScale(10000.0).setZTolerance(0.001);
        gf.setMOrigin(-100000.0).setMScale(10000.0).setMTolerance(0.001);
        // GdbGeomField 的 Z/M origin-scale-tolerance 开关
        gf.setHasZOriginScaleTolerance(true);
        gf.setHasMOriginScaleTolerance(false);
        return gf;
    }

    // ------------------------------------------------------------------
    // FileGDB 原生 blob 往返
    // ------------------------------------------------------------------

    @Test
    @DisplayName("原生 blob 往返:点")
    void nativePointRoundTrip() {
        GdbGeomField gf = geomField();
        GdbGeometry src = GdbGeometry.point(116.397, 39.908);
        byte[] blob = FileGdbGeometryCodec.encode(src, gf, false, false, true);
        assertTrue(blob.length > 0, "编码结果不应为空");

        GdbGeometry back = FileGdbGeometryCodec.decode(blob, gf, false, false);
        assertNotNull(back);
        assertEquals(GeometryType.POINT, back.getType());
        assertEquals(1, back.numPoints());
        assertEquals(116.397, back.getX(0), 1e-9);
        assertEquals(39.908, back.getY(0), 1e-9);
    }

    @Test
    @DisplayName("原生 blob 往返:两部件线")
    void nativePolylineRoundTrip() {
        GdbGeomField gf = geomField();
        double[] xs = {0, 1, 2, 10, 11, 12};
        double[] ys = {0, 1, 0, 100, 101, 100};
        GdbGeometry src = GdbGeometry.multiLineString(xs, ys, new int[]{0, 3, 6});
        byte[] blob = FileGdbGeometryCodec.encode(src, gf, false, false, true);

        GdbGeometry back = FileGdbGeometryCodec.decode(blob, gf, false, false);
        assertNotNull(back);
        assertEquals(6, back.numPoints());
        assertEquals(2, back.numParts());
        for (int i = 0; i < xs.length; i++) {
            assertEquals(xs[i], back.getX(i), 1e-9, "顶点 " + i + " X");
            assertEquals(ys[i], back.getY(i), 1e-9, "顶点 " + i + " Y");
        }
    }

    @Test
    @DisplayName("原生 blob 往返:带洞多边形(环闭合 + 内外环分组)")
    void nativePolygonWithHoleRoundTrip() {
        GdbGeomField gf = geomField();
        // 外环(逆时针)+ 内环(顺时针);编码器会把外环改写成顺时针、内环改写成逆时针
        double[] xs = {0, 10, 10, 0, 0, 2, 2, 8, 8, 2};
        double[] ys = {0, 0, 10, 10, 0, 2, 8, 8, 2, 2};
        int[] ringOff = {0, 5, 10};
        GdbGeometry src = GdbGeometry.polygon(xs, ys, ringOff, new int[]{0, 0});
        assertEquals(10, src.numPoints());

        byte[] blob = FileGdbGeometryCodec.encode(src, gf, false, false, true);
        GdbGeometry back = FileGdbGeometryCodec.decode(blob, gf, false, false);
        assertNotNull(back);
        assertEquals(2, back.numParts(), "两个环应各成 1 个 part");
        // 盘上环是闭合的、内存表示不存闭合点(见 pyopenfilegdb/_esri_geometry.py
        // 的 _strip_part_closures),所以解码后每个环正好少 1 个闭合点。
        assertEquals(src.numPoints() - 2, back.numPoints(),
                "解码应削掉每个环末端的闭合点");
        // 环归属:外环与内环同属第 0 个多边形(内环即它的洞),
        // 与 GDAL organizePolygons / 盘上的 ringGroup 数组口径一致。
        assertTrue(back.hasRingGroup(), "应带回环分组信息");
        assertEquals(0, back.ringGroupOf(0), "外环归第 0 个多边形");
        assertEquals(0, back.ringGroupOf(1), "内环是第 0 个多边形的洞,同组");
        // 包围盒应与原几何一致
        double[] env = back.envelope();
        assertNotNull(env);
        assertEquals(0.0, env[0], 1e-9);
        assertEquals(0.0, env[1], 1e-9);
        assertEquals(10.0, env[2], 1e-9);
        assertEquals(10.0, env[3], 1e-9);
        // WKT 里环必须重新闭合(OGC 要求首尾点重复)
        String wkt = back.toWkt();
        assertTrue(wkt.startsWith("POLYGON"), "单组多边形应输出 POLYGON,实得 " + wkt);
        assertTrue(wkt.contains("0 0, 0 10, 10 10, 10 0, 0 0"),
                "外环 WKT 应补回闭合点,实得 " + wkt);
        assertTrue(wkt.contains("2 2, 8 2, 8 8, 2 8, 2 2"),
                "内环 WKT 应补回闭合点,实得 " + wkt);
    }

    @Test
    @DisplayName("原生 blob 往返:三维点")
    void nativePointZRoundTrip() {
        GdbGeomField gf = geomField();
        GdbGeometry src = GdbGeometry.pointZ(1.5, 2.5, 33.25);
        byte[] blob = FileGdbGeometryCodec.encode(src, gf, true, false, true);
        GdbGeometry back = FileGdbGeometryCodec.decode(blob, gf, true, false);
        assertNotNull(back);
        assertEquals(1.5, back.getX(0), 1e-9);
        assertEquals(2.5, back.getY(0), 1e-9);
        assertEquals(33.25, back.getZ(0), 1e-6);
    }

    @Test
    @DisplayName("包围盒前缀探测与完整解码结果一致")
    void peekEnvelope() {
        GdbGeomField gf = geomField();
        GdbGeometry src = GdbGeometry.lineString(
                new double[]{-1, 5, 7}, new double[]{3, -2, 9});
        byte[] blob = FileGdbGeometryCodec.encode(src, gf, false, false, true);
        double[] peeked = FileGdbGeometryCodec.peekEnvelope(blob, gf, false, false);
        double[] full = FileGdbGeometryCodec.decode(blob, gf, false, false).envelope();
        assertNotNull(peeked);
        assertNotNull(full);
        // 探测出的包围盒已放宽一个量化步长,所以是"包含"关系而不是相等
        assertTrue(peeked[0] <= full[0] + 1e-9 && peeked[1] <= full[1] + 1e-9);
        assertTrue(peeked[2] >= full[2] - 1e-9 && peeked[3] >= full[3] - 1e-9);
    }

    // ------------------------------------------------------------------
    // WKB
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WKB 往返:点 / 线 / 多边形")
    void wkbRoundTrip() {
        GdbGeometry p = GdbGeometry.point(1, 2);
        byte[] pw = p.toWkb();
        assertEquals(1, pw[0], "WKB 第 1 字节应为小端标志 1");
        // WKB 头 = 1 字节字节序 + 4 字节小端类型码,所以类型码占 pw[1..4]。
        assertEquals(1, wkbType(pw), "POINT 的类型码应为 1");
        GdbGeometry pr = GdbGeometry.fromWkb(pw);
        assertEquals(GeometryType.POINT, pr.getType());
        assertEquals(1.0, pr.getX(0), 0.0);

        GdbGeometry line = GdbGeometry.lineString(new double[]{0, 1, 2}, new double[]{3, 4, 5});
        GdbGeometry lr = GdbGeometry.fromWkb(line.toWkb());
        assertEquals(GeometryType.LINESTRING, lr.getType());
        assertEquals(3, lr.numPoints());

        GdbGeometry poly = GdbGeometry.polygon(
                new double[]{0, 1, 1, 0, 0}, new double[]{0, 0, 1, 1, 0},
                new int[]{0, 5}, new int[]{0});
        GdbGeometry por = GdbGeometry.fromWkb(poly.toWkb());
        assertEquals(GeometryType.POLYGON, por.getType());
        assertEquals(5, por.numPoints());
    }

    @Test
    @DisplayName("WKB:ISO 的 Z 偏移 1000 能被正确解析")
    void wkbIsoZ() {
        GdbGeometry pz = GdbGeometry.pointZ(3, 4, 5);
        byte[] wkb = pz.toWkb();
        // 类型码应为 1 + 1000 = 1001,小端写为 E9 03 00 00(位于偏移 1..4)
        assertEquals(1001, wkbType(wkb), "ISO Z 点的类型码应为 1001(XYZ)");
        assertEquals((byte) 0xE9, wkb[1], "类型码低字节");
        assertEquals((byte) 0x03, wkb[2], "类型码次字节");
        GdbGeometry back = GdbGeometry.fromWkb(wkb);
        assertTrue(back.hasZ());
        assertEquals(5.0, back.getZ(0), 0.0);
    }

    /** 读 WKB 头的类型码(WKB 头 = 1 字节字节序 + 4 字节小端类型码)。 */
    private static int wkbType(byte[] wkb) {
        assertEquals(1, wkb[0], "WKB 只支持小端(NDR)");
        return (wkb[1] & 0xFF) | ((wkb[2] & 0xFF) << 8)
                | ((wkb[3] & 0xFF) << 16) | ((wkb[4] & 0xFF) << 24);
    }

    @Test
    @DisplayName("空几何:instanceof 与 envelope")
    void emptyGeometry() {
        GdbGeometry empty = new GdbGeometry(GeometryType.POINT, false, false,
                new double[0], new double[0], null, null, new int[]{0, 0}, null, null);
        assertTrue(empty.isEmpty());
        assertNull(empty.envelope());
        assertTrue(empty.toWkt().endsWith("EMPTY"));
    }

    @Test
    @DisplayName("WKT 输出(供日志/Demo 用)")
    void wktOutput() {
        assertEquals("POINT (1 2)", GdbGeometry.point(1, 2).toWkt());
        assertEquals("LINESTRING (0 0, 1 1)",
                GdbGeometry.lineString(new double[]{0, 1}, new double[]{0, 1}).toWkt());
    }

    @Test
    @DisplayName("WKT 坐标不得退化成科学计数法")
    void wktNoScientificNotation() {
        // 这几组值都会让 Double.toString 走科学计数法(|v| < 1e-3 或 |v| >= 1e7),
        // 必须被展平成普通十进制,否则日志里全是 4.5E7 / 1.23E-5。
        assertEquals("POINT (12345678.9 0.0000123)",
                GdbGeometry.point(12345678.9, 0.0000123).toWkt());
        assertEquals("POINT (-45000000.25 -0.00000015)",
                GdbGeometry.point(-45000000.25, -1.5e-7).toWkt());
        // 投影坐标常见量级:带假东距的高斯投影 X ≈ 5e5,UTM X ≈ 5e5,WebMercator ≈ 2e7。
        assertEquals("POINT (20037508.342789244 0.000000001)",
                GdbGeometry.point(20037508.342789244, 1e-9).toWkt());
        assertFalse(GdbGeometry.point(1e20, -1e-20).toWkt().contains("E"));
        assertFalse(GdbGeometry.point(12345678.9, 0.0000123).toWkt().contains("e"));
        // NaN / 无穷不参与格式化,保持字面量。
        assertTrue(GdbGeometry.point(Double.NaN, 0).toWkt().contains("NaN"));
    }

    @Test
    @DisplayName("WKT 坐标精度不丢:格式化后仍能原值还原")
    void wktCoordinateRoundTripPrecision() {
        double[] xs = {12345678.9, -45000000.25, 1e20, 0.0000123, -1.5e-7, 20037508.342789244};
        for (double x : xs) {
            String wkt = GdbGeometry.point(x, 0).toWkt();
            int close = wkt.indexOf(')');
            String text = wkt.substring("POINT (".length(), close).split(" ")[0];
            assertEquals(x, Double.parseDouble(text), 0.0,
                    "坐标 " + x + " 格式化后应为 " + x + ",实际文本 " + text);
        }
    }

    @Test
    @DisplayName("字段类型码与 GDAL filegdbtable.h 一致")
    void fieldTypeCodes() {
        assertEquals(0, FieldType.INT16.code());
        assertEquals(4, FieldType.STRING.code());
        assertEquals(6, FieldType.OBJECTID.code());
        assertEquals(7, FieldType.GEOMETRY.code());
        assertEquals(10, FieldType.GUID.code());
        assertEquals(13, FieldType.INT64.code());
        assertEquals(8, FieldType.DATETIME.fixedSize());
        assertEquals(0, FieldType.OBJECTID.fixedSize(), "OID 不占记录空间");
    }
}
