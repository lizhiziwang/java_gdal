package com.gdal.openfilegdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.gdal.openfilegdb.util.GdbCollections.list;

import com.gdal.openfilegdb.exception.GdbFormatException;
import com.gdal.openfilegdb.exception.GdbVersionException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeomField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.table.GdbTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code .gdbtable} / {@code .gdbtablx} 读写往返测试。
 *
 * <p>覆盖需求里点名的全部字段类型:整数(16/32/64)、浮点(32/64)、文本、日期、
 * BLOB、GUID,以及几何字段;并覆盖空值位图、更新、删除与头部回填。
 */
class GdbTableRoundTripTest {

    /** 表级几何类型 FGTGT_POINT(与 GDAL filegdbtable.h 一致)。 */
    private static final int FGTGT_POINT = 1;
    private static final int FGTGT_POLYGON = 4;

    /** 构造一个覆盖全部字段类型的字段清单。 */
    private static List<GdbField> sampleFields() {
        GdbGeomField shape = new GdbGeomField("Shape");
        shape.setXOrigin(-180).setYOrigin(-90).setXyScale(1e9).setXyTolerance(2e-6);
        shape.setZOrigin(-100000).setZScale(10000).setZTolerance(0.001);
        shape.setHasZOriginScaleTolerance(true);
        shape.setSpatialIndexGridResolutions(new double[]{0.012, 0.4, 12.0});

        List<GdbField> fields = new ArrayList<>();
        fields.add(new GdbField("OBJECTID", FieldType.OBJECTID)
                .setRequired(true).setNullable(false));
        fields.add(shape);
        fields.add(new GdbField("NAME", FieldType.STRING).setLength(64));
        fields.add(new GdbField("SMALL", FieldType.INT16));
        fields.add(new GdbField("CNT", FieldType.INT32));
        fields.add(new GdbField("BIG", FieldType.INT64));
        fields.add(new GdbField("RATIO", FieldType.FLOAT64));
        fields.add(new GdbField("RATIO32", FieldType.FLOAT32));
        fields.add(new GdbField("WHEN", FieldType.DATETIME));
        fields.add(new GdbField("BLOB", FieldType.BINARY));
        fields.add(new GdbField("GID", FieldType.GUID));
        fields.add(new GdbField("REQ", FieldType.INT32).setNullable(false));
        return fields;
    }

    private static GdbFeature sampleFeature(String name, double x, double y) {
        GdbFeature f = new GdbFeature();
        f.setGeometry(GdbGeometry.point(x, y));
        f.setAttribute("NAME", name);
        f.setAttribute("SMALL", (short) -7);
        f.setAttribute("CNT", 123456);
        f.setAttribute("BIG", 9_000_000_000L);
        f.setAttribute("RATIO", 3.141592653589793);
        f.setAttribute("RATIO32", 2.5f);
        f.setAttribute("WHEN", LocalDateTime.of(2024, 3, 8, 10, 30, 15));
        f.setAttribute("BLOB", new byte[]{1, 2, 3, (byte) 0xFF});
        f.setAttribute("GID", "{70737809-852C-4A03-9E22-2CECEA5B9BFA}");
        f.setAttribute("REQ", 42);
        return f;
    }

    @Test
    @DisplayName("全字段类型写入后重新打开,值逐项一致")
    void allFieldTypesRoundTrip(@TempDir Path tmp) throws Exception {
        Path tablePath = tmp.resolve("a00000009.gdbtable");
        assertNotNull(tablePath);

        try (GdbTable t = GdbTable.create(tablePath, sampleFields(), FGTGT_POINT,
                false, false, false, null, 5)) {
            assertEquals(GeometryType.POINT, t.getGeometryType());
            assertEquals(64, t.getFieldByName("NAME").getLength());
            // 每写一条前都要先把表头字段区落盘,这里靠 close() 统一 sync
            assertEquals(1, t.appendFeature(sampleFeature("北京", 116.397, 39.908)));
            assertEquals(2, t.appendFeature(sampleFeature("上海", 121.473, 31.230)));
        }
        // 索引文件必须同时生成
        assertTrue(Files.exists(tmp.resolve("a00000009.gdbtablx")), "缺少 .gdbtablx");

        // ---- 重新以只读方式打开 ----
        GdbTable r = GdbTable.open(tablePath);
        try {
            assertEquals(2, r.getValidRecordCount());
            assertEquals(2, r.getRecordCount());
            assertEquals(GeometryType.POINT, r.getGeometryType());
            assertEquals("OBJECTID", r.getOidFieldName());
            assertEquals("Shape", r.getGeometryFieldName());

            // 字段长度与类型
            assertEquals(64, r.getFieldByName("name").getLength()); // 大小写不敏感
            assertEquals(FieldType.INT64, r.getFieldByName("BIG").getType());

            GdbFeature f = r.readFeature(0);
            assertNotNull(f);
            assertEquals(1, f.getOid());
            assertEquals("北京", f.getAttribute("NAME"));
            assertEquals((short) -7, f.getAttribute("SMALL"));
            assertEquals(123456, f.getAttribute("CNT"));
            assertEquals(9_000_000_000L, f.getAttribute("BIG"));
            assertEquals(3.141592653589793, (Double) f.getAttribute("RATIO"), 0.0);
            assertEquals(2.5f, (Float) f.getAttribute("RATIO32"), 0.0f);
            assertEquals(LocalDateTime.of(2024, 3, 8, 10, 30, 15), f.getAttribute("WHEN"));
            assertArrayEquals(new byte[]{1, 2, 3, (byte) 0xFF}, (byte[]) f.getAttribute("BLOB"));
            assertEquals("{70737809-852C-4A03-9E22-2CECEA5B9BFA}", f.getAttribute("GID"));
            assertEquals(42, f.getAttribute("REQ"));

            // 几何:量化后坐标误差在 1e-9 以内
            GdbGeometry g = f.getGeometry();
            assertNotNull(g);
            assertEquals(GeometryType.POINT, g.getType());
            assertEquals(116.397, g.getX(0), 1e-9);
            assertEquals(39.908, g.getY(0), 1e-9);

            // 行顺序与全表包围盒
            GdbFeature f2 = r.readFeature(1);
            assertEquals("上海", f2.getAttribute("NAME"));
            double[] ext = r.getExtent();
            assertNotNull(ext);
            assertEquals(116.397, ext[0], 1e-6);
            assertEquals(31.230, ext[1], 1e-6);
            assertEquals(121.473, ext[2], 1e-6);
            assertEquals(39.908, ext[3], 1e-6);

            // 迭代
            List<String> names = new ArrayList<>();
            for (GdbFeature x : r.features()) {
                names.add((String) x.getAttribute("NAME"));
            }
            assertEquals(list("北京", "上海"), names);
        } finally {
            r.close();
        }
    }

    @Test
    @DisplayName("空值与空几何:nullable 字段写 null 后读回仍为 null")
    void nullValues(@TempDir Path tmp) throws Exception {
        Path tablePath = tmp.resolve("a0000000a.gdbtable");
        try (GdbTable t = GdbTable.create(tablePath, sampleFields(), FGTGT_POINT,
                false, false, false, null, 5)) {
            GdbFeature f = new GdbFeature();
            f.setGeometry(null);
            f.setAttribute("NAME", null);
            f.setAttribute("CNT", null);
            f.setAttribute("REQ", 7);   // 非空字段必须给值
            t.appendFeature(f);
        }
        GdbTable r = GdbTable.open(tablePath);
        try {
            GdbFeature f = r.readFeature(0);
            assertNotNull(f);
            assertNull(f.getAttribute("NAME"));
            assertNull(f.getAttribute("CNT"));
            assertEquals(7, f.getAttribute("REQ"));
            assertFalse(f.hasGeometry());
        } finally {
            r.close();
        }
    }

    @Test
    @DisplayName("更新与删除:小改原地重写、删除后槽位为空")
    void updateAndDelete(@TempDir Path tmp) throws Exception {
        Path tablePath = tmp.resolve("a0000000b.gdbtable");
        GdbTable t = GdbTable.create(tablePath, sampleFields(), FGTGT_POINT,
                false, false, false, null, 5);
        t.appendFeature(sampleFeature("A", 1, 1));
        t.appendFeature(sampleFeature("B", 2, 2));
        t.appendFeature(sampleFeature("C", 3, 3));

        // 小改:名字变短,原地重写
        GdbFeature b = t.readFeature(1);
        b.setAttribute("NAME", "B2");
        t.updateFeature(b);

        // 删除第 3 条
        assertTrue(t.deleteFeature(3));
        assertFalse(t.deleteFeature(3), "重复删除应返回 false");

        t.close();

        GdbTable r = GdbTable.open(tablePath);
        try {
            assertEquals(2, r.getValidRecordCount(), "有效记录数应减 1");
            assertEquals("B2", r.readFeature(1).getAttribute("NAME"));
            assertNull(r.readFeature(2), "已删除的槽位应读不到");
            assertNotNull(r.readFeature(0));
        } finally {
            r.close();
        }
    }

    @Test
    @DisplayName("多边形写入往返:环闭合、包围盒正确")
    void polygonRoundTrip(@TempDir Path tmp) throws Exception {
        Path tablePath = tmp.resolve("a0000000c.gdbtable");
        GdbGeomField shape = new GdbGeomField("Shape");
        shape.setXOrigin(-180).setYOrigin(-90).setXyScale(1e9).setXyTolerance(2e-6);
        shape.setHasZOriginScaleTolerance(false);
        List<GdbField> fields = list(
                new GdbField("OBJECTID", FieldType.OBJECTID).setRequired(true).setNullable(false),
                shape,
                new GdbField("NAME", FieldType.STRING).setLength(32));

        double[] xs = {0, 10, 10, 0, 0};
        double[] ys = {0, 0, 10, 10, 0};
        try (GdbTable t = GdbTable.create(tablePath, fields, FGTGT_POLYGON,
                false, false, false, null, 5)) {
            GdbFeature f = new GdbFeature();
            f.setGeometry(GdbGeometry.polygon(xs, ys, new int[]{0, 5}, new int[]{0}));
            f.setAttribute("NAME", "square");
            t.appendFeature(f);
        }
        GdbTable r = GdbTable.open(tablePath);
        try {
            assertEquals(GeometryType.POLYGON, r.getGeometryType());
            GdbFeature f = r.readFeature(0);
            GdbGeometry g = f.getGeometry();
            assertNotNull(g);
            // 盘上环是闭合的(xs 的 5 个点),内存表示不存闭合点,故解码后是 4 个点
            assertEquals(xs.length - 1, g.numPoints(), "解码应削掉环末端的闭合点");
            assertEquals(1, g.numParts());
            double[] env = g.envelope();
            assertEquals(0.0, env[0], 1e-9);
            assertEquals(0.0, env[1], 1e-9);
            assertEquals(10.0, env[2], 1e-9);
            assertEquals(10.0, env[3], 1e-9);
            // WKT 出口要把环重新闭合(OGC 要求首尾点重复)
            assertTrue(g.toWkt().contains("0 0, 10 0, 10 10, 0 10, 0 0")
                            || g.toWkt().contains("0 0, 0 10, 10 10, 10 0, 0 0"),
                    "环未闭合: " + g.toWkt());
            // 表级包围盒必须已按写入的要素更新
            double[] ext = r.getExtent();
            assertNotNull(ext, "表级包围盒应可读到");
            assertEquals(0.0, ext[0], 1e-9);
            assertEquals(0.0, ext[1], 1e-9);
            assertEquals(10.0, ext[2], 1e-9);
            assertEquals(10.0, ext[3], 1e-9);
        } finally {
            r.close();
        }
    }

    @Test
    @DisplayName("非 .gdbtable 文件与缺失索引的错误语义")
    void errorSemantics(@TempDir Path tmp) throws Exception {
        Path bad = tmp.resolve("a0000000d.gdbtable");
        Files.write(bad, new byte[]{1, 2, 3});
        assertThrows(GdbFormatException.class, () -> GdbTable.open(bad));

        // 版本非法
        Path v9 = tmp.resolve("a0000000e.gdbtable");
        byte[] header = new byte[40];
        header[0] = 9;
        Files.write(v9, header);
        assertThrows(GdbVersionException.class, () -> GdbTable.open(v9));
    }

    @Test
    @DisplayName("create 校验:多个 OBJECTID / 声明几何却没有几何字段")
    void createValidation(@TempDir Path tmp) {
        List<GdbField> twoOid = list(
                new GdbField("O1", FieldType.OBJECTID).setRequired(true).setNullable(false),
                new GdbField("O2", FieldType.OBJECTID).setRequired(true).setNullable(false));
        assertThrows(RuntimeException.class,
                () -> GdbTable.create(tmp.resolve("x.gdbtable"), twoOid, 0, false, false, false));

        List<GdbField> noGeom = list(
                new GdbField("OBJECTID", FieldType.OBJECTID).setRequired(true).setNullable(false));
        assertThrows(RuntimeException.class,
                () -> GdbTable.create(tmp.resolve("y.gdbtable"), noGeom, FGTGT_POINT,
                        false, false, false));
    }

    @Test
    @DisplayName("iterator 跳过空槽(删除后仍能连续遍历)")
    void iteratorSkipsHoles(@TempDir Path tmp) throws Exception {
        Path tablePath = tmp.resolve("a0000000f.gdbtable");
        GdbTable t = GdbTable.create(tablePath, sampleFields(), FGTGT_POINT,
                false, false, false, null, 5);
        for (int i = 1; i <= 5; i++) {
            t.appendFeature(sampleFeature("N" + i, i, i));
        }
        t.deleteFeature(2);
        t.deleteFeature(4);
        t.close();

        GdbTable r = GdbTable.open(tablePath);
        try {
            Iterator<GdbFeature> it = r.iterator();
            List<String> got = new ArrayList<>();
            while (it.hasNext()) {
                got.add((String) it.next().getAttribute("NAME"));
            }
            assertEquals(list("N1", "N3", "N5"), got);
            assertEquals(3, r.getValidRecordCount());
            assertEquals(5, r.getRecordCount(), "槽位总数含空洞");
        } finally {
            r.close();
        }
    }
}
