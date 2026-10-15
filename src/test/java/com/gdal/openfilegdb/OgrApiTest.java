package com.gdal.openfilegdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import com.gdal.openfilegdb.exception.GdbSqlException;
import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.layer.GdbFeatureDefn;
import com.gdal.openfilegdb.layer.GdbLayer;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.srs.SpatialReference;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OGR 风格 API 测试:目标是"照着 GDAL 的 OGRLayer / OGRFeature / OGRFeatureDefn
 * 写的代码,换成本库能一一对应"。
 *
 * <p>三组断言:
 * <ul>
 *   <li>{@link OpenFileGdbLayer} 的 {@link GdbLayer} 契约(GetLayerDefn /
 *       GetNextFeature / SetSpatialFilterRect / TestCapability …);</li>
 *   <li>{@code executeSql} 返回的<b>结果图层</b>能不能跟普通图层一样读
 *       (GDAL {@code ExecuteSQL} → {@code OGRGenSQLResultsLayer});</li>
 *   <li>{@link GdbFeature} / {@link GdbField} 的 OGR 风格访问器。</li>
 * </ul>
 */
class OgrApiTest {

    @TempDir
    static Path tmp;

    static Path gdb;

    /** 四条点要素,坐标特意分开以便空间过滤能数出确定条数。 */
    @BeforeAll
    static void buildFixture() throws IOException {
        gdb = tmp.resolve("ogr-api.gdb");
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            List<GdbField> fields = Arrays.asList(
                    new GdbField("NAME", FieldType.STRING).setLength(64),
                    new GdbField("VALUE", FieldType.INT32),
                    new GdbField("SEEN", FieldType.DATETIME));
            OpenFileGdbLayer points = ds.createLayer("Points",
                    SpatialReference.fromEpsg(4326), GeometryType.POINT, fields);
            points.createFeature(point(116.4, 39.9, "北京", 10,
                    LocalDateTime.of(2020, 1, 1, 0, 0)));
            points.createFeature(point(121.473, 31.230, "上海", 20, null));
            points.createFeature(point(113.264, 23.129, "广州", 30, null));
            points.createFeature(point(100.0, 0.0, null, null, null));
            ds.flush();
        }
    }

    private static GdbFeature point(double x, double y, String name, Integer value,
                                    LocalDateTime seen) {
        GdbFeature f = new GdbFeature();
        f.setGeometry(GdbGeometry.point(x, y));
        f.setAttribute("NAME", name);
        f.setAttribute("VALUE", value);
        f.setAttribute("SEEN", seen);
        return f;
    }

    // ==================================================================
    // 图层元信息(OGR: GetName / GetLayerDefn / GetFIDColumn / GetGeometryColumn)
    // ==================================================================

    @Test
    @DisplayName("图层元信息:GetLayerDefn / GetFIDColumn / GetGeometryColumn")
    void layerMetadata() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer layer = ds.getLayer("Points");
            assertEquals("Points", layer.getName());
            assertEquals("OBJECTID", layer.getFIDColumn());
            assertEquals("Shape", layer.getGeometryColumn());
            assertEquals(GeometryType.POINT, layer.getGeomType());
            assertFalse(layer.getSpatialRef().getWkt().isEmpty());

            GdbFeatureDefn defn = layer.getLayerDefn();
            assertEquals(5, defn.getFieldCount());
            assertEquals(1, defn.getGeomFieldCount());
            assertNotNull(defn.getGeomFieldDefn(0));
            assertEquals("Shape", defn.getGeomFieldDefn(0).getName());
            // 下标与磁盘字段顺序一致:OBJECTID, Shape, NAME, VALUE, SEEN
            assertEquals("OBJECTID", defn.getFieldDefn(0).getName());
            assertEquals("SEEN", defn.getFieldDefn(4).getName());
            // OGR 的 GetFieldIndex 大小写不敏感
            assertEquals(2, defn.getFieldIndex("name"));
            assertEquals(2, defn.getFieldIndex("NAME"));
            assertEquals(-1, defn.getFieldIndex("nope"));
            assertEquals(1, defn.getGeometryFieldIndex());
            assertEquals(0, defn.getObjectIdFieldIndex());
            assertSame(defn, layer.getLayerDefn(), "要素定义只算一次");
        }
    }

    @Test
    @DisplayName("遍历:GetNextFeature / ResetReading / 单游标")
    void cursorSemantics() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer layer = ds.getLayer("Points");
            assertEquals(4, layer.getFeatureCount());

            List<Long> first = new ArrayList<>();
            GdbFeature f;
            while ((f = layer.getNextFeature()) != null) {
                first.add(f.getOid());
            }
            assertEquals(Arrays.asList(1L, 2L, 3L, 4L), first);
            assertNull(layer.getNextFeature(), "末尾返回 null");

            // for-each 与 getNextFeature 共用同一游标:先消费 1 条,接着只该剩 3 条
            layer.resetReading();
            assertEquals(1L, layer.getNextFeature().getOid());
            assertEquals(3, countRemaining(layer));
            // resetReading 之后才是完整 4 条
            layer.resetReading();
            assertEquals(4, countRemaining(layer));
        }
    }

    private static int countRemaining(GdbLayer layer) {
        int n = 0;
        for (GdbFeature ignored : layer) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("随机读:GetFeature(fid) 与 GetFeatureCount(force)")
    void randomRead() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer layer = ds.getLayer("Points");
            assertEquals("北京", layer.getFeature(1).getField("NAME"));
            assertEquals("上海", layer.getFeature(2).getField("NAME"));
            assertNull(layer.getFeature(0), "OID 从 1 开始");
            assertNull(layer.getFeature(999));
            // 没过滤时是 O(1),force=false 也能给出准确值
            assertEquals(4, layer.getFeatureCount(false));
        }
    }

    // ==================================================================
    // 空间过滤(OGR: SetSpatialFilterRect / GetSpatialFilter / GetFeatureCount)
    // ==================================================================

    /** 覆盖面:含北京(116.4,39.9)与上海(121.473,31.23),不含广州与原点。 */
    private static final double[] BOX = {110.0, 25.0, 125.0, 45.0};

    @Test
    @DisplayName("空间过滤:SetSpatialFilterRect 后遍历只吐命中要素(流式)")
    void streamingSpatialFilter() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer layer = ds.getLayer("Points");
            assertNull(layer.getSpatialFilter(), "默认无过滤");

            layer.setSpatialFilterRect(BOX[0], BOX[1], BOX[2], BOX[3]);
            assertArrayEqualsBox(BOX, layer.getSpatialFilter());

            List<String> names = new ArrayList<>();
            GdbFeature f;
            while ((f = layer.getNextFeature()) != null) {
                names.add(f.getFieldAsString("NAME"));
            }
            assertEquals(Arrays.asList("北京", "上海"), names, "流式结果按行号升序");

            // 与急切版 search() 同结果(后者会把结果全驻留)
            assertEquals(2, layer.search(BOX[0], BOX[1], BOX[2], BOX[3]).size());

            layer.resetReading();
            assertEquals(2, layer.getFeatureCount(true));
            assertEquals(-1, layer.getFeatureCount(false), "带过滤且 force=false → OGR 返回 -1");

            layer.clearSpatialFilter();
            assertNull(layer.getSpatialFilter());
            assertEquals(4, countRemaining(layer), "清空后回到全表");
        }
    }

    @Test
    @DisplayName("空间过滤:null / 空矩形等价于 clearSpatialFilter")
    void spatialFilterNullMeansClear() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer layer = ds.getLayer("Points");
            layer.setSpatialFilter(BOX);
            assertNotNull(layer.getSpatialFilter());
            layer.setSpatialFilter(null);
            assertNull(layer.getSpatialFilter());
            assertEquals(4, countRemaining(layer));
            assertThrows(IllegalArgumentException.class,
                    () -> layer.setSpatialFilter(new double[]{1, 2, 3}));
        }
    }

    @Test
    @DisplayName("范围:GetExtent(force) 带过滤时算命中要素的包围盒")
    void extent() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer layer = ds.getLayer("Points");
            double[] all = layer.getExtent();
            assertNotNull(all);
            assertEquals(100.0, all[0], 1e-9);
            assertEquals(0.0, all[1], 1e-9);
            assertEquals(121.473, all[2], 1e-9);
            assertEquals(39.9, all[3], 1e-9);
            assertNotNull(layer.getExtent(false), "无过滤时表头就有范围,force=false 也拿得到");

            layer.setSpatialFilter(BOX);
            assertNull(layer.getExtent(false), "带过滤且 force=false → null");
            double[] hit = layer.getExtent(true);
            assertNotNull(hit);
            assertEquals(116.4, hit[0], 1e-9);
            assertEquals(31.230, hit[1], 1e-9);
            assertEquals(121.473, hit[2], 1e-9);
            assertEquals(39.9, hit[3], 1e-9);
        }
    }

    private static void assertArrayEqualsBox(double[] expected, double[] actual) {
        assertNotNull(actual);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-12, "下标 " + i);
        }
    }

    @Test
    @DisplayName("能力:TestCapability 的 OLC* 常量")
    void capability() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer layer = ds.getLayer("Points");
            assertTrue(layer.testCapability("OLCRandomRead"));
            assertTrue(layer.testCapability("OLCFastFeatureCount"));
            assertTrue(layer.testCapability("OLCFastGetExtent"));
            assertTrue(layer.testCapability("OLCStringsAsUTF8"));
            // 只读打开 → 写能力全关
            assertFalse(layer.testCapability("OLCSequentialWrite"));
            assertFalse(layer.testCapability("OLCRandomWrite"));
            assertFalse(layer.testCapability("OLCDeleteFeature"));
            // 本库不写 .spx
            assertFalse(layer.testCapability("OLCFastSpatialFilter"));
            assertFalse(layer.testCapability("OLCCreateField"));
            assertFalse(layer.testCapability("OLCTransactions"));
            assertFalse(layer.testCapability("不存在的常量"));

            // 写路径:create() 出来的库可写
            assertFalse(ds.testCapability("ODsCCreateLayer"), "只读打开不能建图层");
        }
    }

    @Test
    @DisplayName("写入守卫:只读图层调 CreateFeature 抛 GdbWriteException")
    void writeGuardOnReadOnlyLayer() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer layer = ds.getLayer("Points");
            assertThrows(GdbWriteException.class,
                    () -> layer.createFeature(point(1, 1, "x", 1, null)));
            assertThrows(GdbWriteException.class, () -> layer.updateFeature(new GdbFeature()));
            assertThrows(GdbWriteException.class, () -> layer.deleteFeature(1));
        }
    }

    // ==================================================================
    // 数据集(OGR: GetName / GetLayerByName / ReleaseResultSet)
    // ==================================================================

    @Test
    @DisplayName("数据集:GetName / GetLayerByName / ODsC* 能力")
    void datasetApi() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            assertEquals(gdb.toString(), ds.getName());
            assertEquals(ds.getName(), ds.getDescription());
            assertEquals(1, ds.getLayerCount());
            assertSame(ds.getLayer("Points"), ds.getLayerByName("Points"));
            assertSame(ds.getLayer(0), ds.getLayerByName("points"), "名字大小写不敏感");
            assertTrue(ds.testCapability("ODsCRandomLayerRead"));
            assertFalse(ds.testCapability("ODsCDeleteLayer"));
            // 普通图层交给数据集管生命周期,ReleaseResultSet 是安全的空操作
            OpenFileGdbLayer layer = ds.getLayer("Points");
            ds.releaseResultSet(layer);
            assertEquals(4, layer.getFeatureCount(), "ReleaseResultSet 对普通图层无害");
            ds.releaseResultSet(null);
        }
    }

    // ==================================================================
    // executeSql 的结果图层(GDAL: ExecuteSQL → OGRGenSQLResultsLayer)
    // ==================================================================

    @Test
    @DisplayName("executeSql 返回图层:名字是 SELECT,字段定义与源表一致")
    void sqlResultIsALayer() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbLayer rs = ds.executeSql("SELECT * FROM Points");
            try {
                assertEquals("SELECT", rs.getName(), "与 GDAL 的 GenSQL 层同名");
                assertEquals("OBJECTID", rs.getFIDColumn());
                assertEquals("Shape", rs.getGeometryColumn());
                assertEquals(GeometryType.POINT, rs.getGeomType());
                assertFalse(rs.getSpatialRef().getWkt().isEmpty());
                GdbFeatureDefn defn = rs.getLayerDefn();
                assertEquals(Arrays.asList("OBJECTID", "Shape", "NAME", "VALUE", "SEEN"),
                        defnColumnNames(defn));
                assertEquals(-1, rs.getFeatureCount(), "SQL 结果层要素数默认未知");
            } finally {
                ds.releaseResultSet(rs);
            }
        }
    }

    @Test
    @DisplayName("结果图层:ResetReading + GetNextFeature 像普通图层一样读")
    void sqlResultReadsLikeALayer() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbLayer rs = ds.executeSql(
                    "SELECT OBJECTID, NAME, Shape FROM Points ORDER BY OBJECTID");
            try {
                GdbFeature f = rs.getNextFeature();
                assertNotNull(f);
                assertEquals(1L, f.getFID(), "OBJECTID 列落到 GetFID");
                assertEquals("北京", f.getField("NAME"));
                assertNotNull(f.getGeometryRef());
                assertEquals(GeometryType.POINT, f.getGeometryRef().getType());

                assertEquals(2L, rs.getNextFeature().getFID());
                assertEquals(3L, rs.getNextFeature().getFID());
                assertEquals(4L, rs.getNextFeature().getFID());
                assertNull(rs.getNextFeature());

                rs.resetReading();
                List<Long> ids = new ArrayList<>();
                for (GdbFeature g : rs) {
                    ids.add(g.getFID());
                }
                assertEquals(Arrays.asList(1L, 2L, 3L, 4L), ids, "复位后能重读一遍");
                assertEquals(4, rs.getFeatureCount(true), "force=true 数一遍");
            } finally {
                ds.releaseResultSet(rs);
            }
        }
    }

    @Test
    @DisplayName("结果图层:没有 OBJECTID 列时 FID 退化成行号")
    void sqlResultSequentialFid() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbLayer rs = ds.executeSql("SELECT NAME FROM Points ORDER BY OBJECTID");
            try {
                assertEquals("", rs.getFIDColumn());
                assertEquals("", rs.getGeometryColumn());
                assertEquals(GeometryType.UNKNOWN, rs.getGeomType());
                GdbFeature f = rs.getNextFeature();
                assertNotNull(f);
                assertEquals(1L, f.getFID(), "无 OBJECTID 列 → 从 1 开始编号");
                assertEquals("北京", f.getField("NAME"));
                assertEquals(2L, rs.getNextFeature().getFID());
            } finally {
                ds.releaseResultSet(rs);
            }
        }
    }

    @Test
    @DisplayName("结果图层:SetSpatialFilterRect 会带 bbox 重跑一遍")
    void sqlResultSpatialFilter() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbLayer rs = ds.executeSql("SELECT OBJECTID, NAME FROM Points ORDER BY OBJECTID");
            try {
                assertEquals(4, rs.getFeatureCount(true));
                rs.setSpatialFilterRect(BOX[0], BOX[1], BOX[2], BOX[3]);
                assertEquals(2, rs.getFeatureCount(true));
                rs.resetReading();
                List<String> names = new ArrayList<>();
                GdbFeature f;
                while ((f = rs.getNextFeature()) != null) {
                    names.add(f.getFieldAsString("NAME"));
                }
                assertEquals(Arrays.asList("北京", "上海"), names);
                rs.clearSpatialFilter();
                assertEquals(4, rs.getFeatureCount(true), "清空过滤后回到全表");
            } finally {
                ds.releaseResultSet(rs);
            }
        }
    }

    @Test
    @DisplayName("结果图层:只读、不支持随机读、释放后不能再读")
    void sqlResultConstraints() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbLayer rs = ds.executeSql("SELECT OBJECTID FROM Points");
            assertNull(rs.getFeature(1), "结果层没有随机读");
            assertFalse(rs.testCapability("OLCRandomRead"));
            assertFalse(rs.testCapability("OLCFastFeatureCount"));
            assertTrue(rs.testCapability("OLCStringsAsUTF8"));
            assertThrows(GdbWriteException.class, () -> rs.createFeature(new GdbFeature()));
            assertThrows(GdbWriteException.class, () -> rs.updateFeature(new GdbFeature()));
            assertThrows(GdbWriteException.class, () -> rs.deleteFeature(1));

            ds.releaseResultSet(rs);
            ds.releaseResultSet(rs); // 幂等
            assertThrows(GdbSqlException.class, rs::getNextFeature);
        }
    }

    // ==================================================================
    // 要素 / 字段的 OGR 风格访问器
    // ==================================================================

    @Test
    @DisplayName("要素:GetFID / GetField / GetFieldAsXxx / GetGeometryRef")
    void featureApi() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbFeature f = ds.getLayer("Points").getFeature(1);
            assertEquals(1L, f.getFID());
            assertEquals("北京", f.getField("NAME"));
            assertEquals("北京", f.getField("name"), "字段名大小写不敏感");
            assertEquals(10, f.getFieldAsInteger("VALUE"));
            assertEquals(10L, f.getFieldAsInteger64("VALUE"));
            assertEquals(10.0, f.getFieldAsDouble("VALUE"), 1e-9);
            assertEquals("10", f.getFieldAsString("VALUE"));
            assertTrue(f.isFieldSet("NAME"));
            assertNotNull(f.getGeometryRef());
            assertSame(f.getGeometryRef(), f.getGeometry());

            // 第 2 条(上海)故意没写 SEEN:空值语义
            GdbFeature g = ds.getLayer("Points").getFeature(2);
            assertFalse(g.isFieldSet("SEEN"));
            assertEquals("", g.getFieldAsString("SEEN"), "空值转字符串是空串(OGR 约定)");
            assertEquals(0, g.getFieldAsInteger("SEEN"), "空值转数字是 0(OGR 约定)");
            assertEquals(0.0, g.getFieldAsDouble("SEEN"), 1e-9);

            f.setFID(42L);
            assertEquals(42L, f.getFID());
            assertEquals(42L, f.getOid());
        }
    }

    @Test
    @DisplayName("字段:GetWidth / GetPrecision / GetTypeName(ogrinfo 里那个类型名)")
    void fieldApi() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbFeatureDefn defn = ds.getLayer("Points").getLayerDefn();
            GdbField name = defn.getFieldDefn(defn.getFieldIndex("NAME"));
            assertEquals(64, name.getWidth());
            assertEquals(64, name.getLength());
            assertEquals(0, name.getPrecision(), "FileGDB 没有精度概念");
            assertEquals("String", name.getTypeName());

            assertEquals("Integer64", defn.getFieldDefn(0).getTypeName(), "OBJECTID");
            assertEquals("Geometry", defn.getFieldDefn(1).getTypeName());
            assertEquals("Integer", defn.getFieldDefn(3).getTypeName(), "VALUE 是 INT32");
            assertEquals("DateTime", defn.getFieldDefn(4).getTypeName());
        }
    }

    // ==================================================================
    // 空间过滤的精确性(OGR 用的是 Intersects,不是"包围盒相交")
    // ==================================================================

    /**
     * L 形凹多边形:顶点 (0,0)(10,0)(10,2)(2,2)(2,10)(0,10),
     * 包围盒 [0,0,10,10],但右上角 (2..10, 2..10) 是空的。
     */
    private static GdbGeometry lShape() {
        return GdbGeometry.polygon(
                new double[]{0, 10, 10, 2, 2, 0, 0},
                new double[]{0, 0, 2, 2, 10, 10, 0},
                new int[]{0, 7}, new int[]{0});
    }

    /** 带一个方孔的正方形:外环 (0,0)(10,0)(10,10)(0,10),内环 (4,4)(6,4)(6,6)(4,6)。 */
    private static GdbGeometry squareWithHole() {
        return GdbGeometry.polygon(
                new double[]{0, 10, 10, 0, 0, 4, 6, 6, 4, 4},
                new double[]{0, 0, 10, 10, 0, 4, 4, 6, 6, 4},
                new int[]{0, 5, 10}, new int[]{0, 0});
    }

    @Test
    @DisplayName("相交判定是几何级:凹多边形的空角不算命中")
    void intersectsRectIsExact() {
        GdbGeometry l = lShape();
        // 包围盒搭上了,实体没进去 → false(只看包围盒会误判为 true)
        assertFalse(l.intersectsRect(3, 3, 9, 9));
        // 矩形整个落在 L 的竖条里:没有顶点在内、没有边穿过,靠"中心在多边形内"救回来
        assertTrue(l.intersectsRect(0.5, 0.5, 1.5, 1.5));
        // 真压上了
        assertTrue(l.intersectsRect(8, 0.5, 9, 1.5));

        GdbGeometry donut = squareWithHole();
        // 矩形整个落在孔里 → 不算命中(奇偶规则:外环 1 次 + 内环 1 次 = 偶数)
        assertFalse(donut.intersectsRect(4.5, 4.5, 5.5, 5.5));
        // 压在内环边界上 → 命中
        assertTrue(donut.intersectsRect(3, 3, 5, 5));
        // 压在外环实体上
        assertTrue(donut.intersectsRect(8, 8, 12, 12));
    }

    @Test
    @DisplayName("相交判定:点 / 线同样按几何算,不是按包围盒")
    void intersectsRectForPointsAndLines() {
        GdbGeometry p = GdbGeometry.point(5, 5);
        assertTrue(p.intersectsRect(0, 0, 10, 10));
        assertFalse(p.intersectsRect(6, 6, 10, 10));

        // 斜线 (0,10)->(10,0):包围盒 [0,0,10,10] 盖住了 [0,0,2,2],但线本身没进去
        GdbGeometry diag = GdbGeometry.lineString(new double[]{0, 10}, new double[]{10, 0});
        assertFalse(diag.intersectsRect(0, 0, 2, 2));
        assertTrue(diag.intersectsRect(4, 4, 6, 6), "对角线上 x=y,中心附近必然穿过");
    }

    @Test
    @DisplayName("containsPoint:奇偶规则处理洞与多部件")
    void containsPointHandlesHoles() {
        GdbGeometry donut = squareWithHole();
        assertFalse(donut.containsPoint(5, 5), "孔里不算在多边形内");
        assertTrue(donut.containsPoint(1, 1), "外环实体内");
        assertFalse(donut.containsPoint(-1, 1), "多边形外");
        // 非面类型恒为 false
        assertFalse(GdbGeometry.point(1, 1).containsPoint(1, 1));
    }

    private static List<String> defnColumnNames(GdbFeatureDefn defn) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < defn.getFieldCount(); i++) {
            out.add(defn.getFieldDefn(i).getName());
        }
        return out;
    }
}
