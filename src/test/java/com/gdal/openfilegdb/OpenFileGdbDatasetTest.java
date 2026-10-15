package com.gdal.openfilegdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import static com.gdal.openfilegdb.util.GdbCollections.list;

import com.gdal.openfilegdb.exception.GdbFileNotFoundException;
import com.gdal.openfilegdb.exception.GdbNotFoundException;
import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.srs.SpatialReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dataset / Layer 级测试:建库、建图层、写要素、重开读回,以及错误语义。
 *
 * <p>这是端到端验收的核心用例:写出的 {@code .gdb} 必须结构完整(系统表齐全、
 * 索引文件齐全),重开后图层名 / 字段 / 几何 / 属性 / 范围全部一致。
 */
class OpenFileGdbDatasetTest {

    /** 建库后必须存在的文件(不含用户图层)。 */
    private static final List<String> SYSTEM_FILES = list(
            "gdb", "timestamps",
            "a00000001.gdbtable", "a00000001.gdbtablx",
            "a00000002.gdbtable", "a00000002.gdbtablx",
            "a00000003.gdbtable", "a00000003.gdbtablx",
            "a00000004.gdbtable", "a00000004.gdbtablx",
            "a00000005.gdbtable", "a00000005.gdbtablx",
            "a00000006.gdbtable", "a00000006.gdbtablx",
            "a00000007.gdbtable", "a00000007.gdbtablx");

    private static List<GdbField> pointFields() {
        return list(
                new GdbField("NAME", FieldType.STRING).setLength(64),
                new GdbField("VALUE", FieldType.INT32),
                new GdbField("SEEN", FieldType.DATETIME));
    }

    // ==================================================================

    @Test
    @DisplayName("create() 建出完整库结构(gdb + timestamps + 七张系统表及其索引)")
    void createMakesCompleteStructure(@TempDir Path tmp) throws IOException {
        Path gdb = tmp.resolve("out.gdb");
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            assertTrue(ds.isWritable());
            assertTrue(ds.getLayerNames().isEmpty(), "新库不应有用户图层");
            ds.flush();
        }
        assertTrue(Files.isDirectory(gdb));
        for (String f : SYSTEM_FILES) {
            assertTrue(Files.isRegularFile(gdb.resolve(f)), "缺少 " + f);
        }
        // gdb 魔数文件内容
        byte[] magic = Files.readAllBytes(gdb.resolve("gdb"));
        assertEquals(8, magic.length);
        assertEquals(0x05, magic[0] & 0xFF);
        assertEquals((byte) 0xDE, magic[4]);
        assertEquals((byte) 0xAD, magic[5]);
        assertEquals((byte) 0xBE, magic[6]);
        assertEquals((byte) 0xEF, magic[7]);
        // timestamps 400 字节全 0xFF
        byte[] ts = Files.readAllBytes(gdb.resolve("timestamps"));
        assertEquals(400, ts.length);
        for (byte b : ts) {
            assertEquals((byte) 0xFF, b);
        }
        // 不应建 a00000008(GDB_ReplicaLog 可省)
        assertFalse(Files.exists(gdb.resolve("a00000008.gdbtable")));
    }

    @Test
    @DisplayName("建图层 -> 写要素 -> 重开读回:图层名/字段/几何/范围/遍历/过滤")
    void writeReadRoundTrip(@TempDir Path tmp) throws Exception {
        Path gdb = tmp.resolve("city.gdb");
        SpatialReference srs = SpatialReference.fromEpsg(4326);

        // ---- 写 ----
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            OpenFileGdbLayer layer =
                    ds.createLayer("points", srs, GeometryType.POINT, pointFields());
            assertEquals("points", layer.getName());
            assertEquals(GeometryType.POINT, layer.getGeometryType());

            GdbFeature a = new GdbFeature();
            a.setGeometry(GdbGeometry.point(116.397, 39.908));
            a.setAttribute("NAME", "北京");
            a.setAttribute("VALUE", 100);
            a.setAttribute("SEEN", LocalDateTime.of(2024, 1, 1, 8, 0, 0));
            assertEquals(1, layer.createFeature(a));

            GdbFeature b = new GdbFeature();
            b.setGeometry(GdbGeometry.point(121.473, 31.230));
            b.setAttribute("NAME", "上海");
            b.setAttribute("VALUE", 200);
            layer.createFeature(b);

            GdbFeature c = new GdbFeature();
            c.setGeometry(GdbGeometry.point(113.264, 23.129));
            c.setAttribute("NAME", "广州");
            c.setAttribute("VALUE", 300);
            layer.createFeature(c);

            assertEquals(3, layer.getFeatureCount());

            // 建第二个图层:面
            OpenFileGdbLayer poly = ds.createLayer("zones", srs, GeometryType.POLYGON,
                    list(new GdbField("ZNAME", FieldType.STRING).setLength(32)));
            GdbFeature p = new GdbFeature();
            p.setGeometry(GdbGeometry.polygon(
                    new double[]{0, 10, 10, 0, 0}, new double[]{0, 0, 10, 10, 0},
                    new int[]{0, 5}, new int[]{0}));
            p.setAttribute("ZNAME", "square");
            poly.createFeature(p);

            // 建非空间表
            OpenFileGdbLayer table = ds.createTable("attrs",
                    list(new GdbField("K", FieldType.STRING).setLength(16),
                            new GdbField("V", FieldType.INT32)));
            GdbFeature kv = new GdbFeature();
            kv.setAttribute("K", "k1");
            kv.setAttribute("V", 7);
            table.createFeature(kv);

            ds.flush();
        }

        // 用户图层的物理文件应从 a00000009 开始
        assertTrue(Files.isRegularFile(gdb.resolve("a00000009.gdbtable")));
        assertTrue(Files.isRegularFile(gdb.resolve("a00000009.gdbtablx")));

        // ---- 读 ----
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            assertFalse(ds.isWritable());
            assertEquals(list("points", "zones", "attrs"), ds.getLayerNames());

            OpenFileGdbLayer layer = ds.getLayer("points");
            assertEquals(GeometryType.POINT, layer.getGeometryType());
            assertEquals(3, layer.getFeatureCount());
            // 字段:OBJECTID + Shape + 三个属性
            assertEquals(5, layer.getFields().size());
            assertEquals(FieldType.OBJECTID, layer.getField("objectid").getType());
            assertEquals(FieldType.GEOMETRY, layer.getField("SHAPE").getType());
            assertEquals(FieldType.STRING, layer.getField("name").getType());

            // 空间参考(EPSG:4326 内建 WKT 里没有 AUTHORITY 段,所以 WKID 可能是 0,
            //  但 WKT 必须保留完整)
            SpatialReference got = layer.getSpatialReference();
            assertNotNull(got);
            assertTrue(got.getWkt().contains("WGS_1984"));

            // 遍历
            List<String> names = new ArrayList<>();
            GdbFeature f;
            while ((f = layer.nextFeature()) != null) {
                names.add((String) f.getAttribute("NAME"));
            }
            assertEquals(list("北京", "上海", "广州"), names);
            assertNull(layer.nextFeature(), "游标到末尾后应返回 null");

            // 复位 + for-each
            layer.reset();
            List<String> again = new ArrayList<>();
            for (GdbFeature x : layer) {
                again.add((String) x.getAttribute("NAME"));
            }
            assertEquals(names, again);

            // 随机读
            GdbFeature second = layer.getFeature(2);
            assertNotNull(second);
            assertEquals("上海", second.getAttribute("NAME"));
            assertEquals(200, second.getAttribute("VALUE"));
            assertNull(layer.getFeature(99));

            // 几何
            GdbGeometry g = layer.getFeature(1).getGeometry();
            assertNotNull(g);
            assertEquals(116.397, g.getX(0), 1e-9);
            assertEquals(39.908, g.getY(0), 1e-9);
            assertTrue(g.toWkb().length > 0);
            assertEquals(1, g.toWkb()[0], "WKB 应为小端");

            // 范围
            double[] ext = layer.getExtent();
            assertNotNull(ext);
            assertEquals(113.264, ext[0], 1e-6);
            assertEquals(23.129, ext[1], 1e-6);
            assertEquals(121.473, ext[2], 1e-6);
            assertEquals(39.908, ext[3], 1e-6);

            // 空间过滤(新库无 .spx,走全表扫 + 精确包围盒)
            List<GdbFeature> hit = layer.search(115.0, 30.0, 122.0, 40.0);
            assertEquals(2, hit.size(), "北京 + 上海");
            List<GdbFeature> miss = layer.search(0, 0, 1, 1);
            assertEquals(0, miss.size());

            // 属性过滤
            List<GdbFeature> big =
                    layer.where(x -> ((Number) x.getAttribute("VALUE")).intValue() >= 200);
            assertEquals(2, big.size());

            // 面图层与表
            assertEquals(GeometryType.POLYGON, ds.getLayer("zones").getGeometryType());
            assertEquals(1, ds.getLayer("attrs").getFeatureCount());
            assertEquals(GeometryType.UNKNOWN, ds.getLayer("attrs").getGeometryType());
            assertEquals("k1", ds.getLayer("attrs").nextFeature().getAttribute("K"));
        }
    }

    @Test
    @DisplayName("追加写入:openForWrite 后再写要素,数量累积")
    void appendToExisting(@TempDir Path tmp) throws Exception {
        Path gdb = tmp.resolve("append.gdb");
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            OpenFileGdbLayer l = ds.createLayer("p", SpatialReference.fromEpsg(4326),
                    GeometryType.POINT, pointFields());
            GdbFeature f = new GdbFeature();
            f.setGeometry(GdbGeometry.point(1, 1));
            f.setAttribute("NAME", "first");
            l.createFeature(f);
        }
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.openForWrite(gdb)) {
            OpenFileGdbLayer l = ds.getLayer("p");
            assertEquals(1, l.getFeatureCount());
            GdbFeature f = new GdbFeature();
            f.setGeometry(GdbGeometry.point(2, 2));
            f.setAttribute("NAME", "second");
            l.createFeature(f);
            assertEquals(2, l.getFeatureCount());
            ds.flush();
        }
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            OpenFileGdbLayer l = ds.getLayer("p");
            assertEquals(2, l.getFeatureCount());
            assertEquals("first", l.getFeature(1).getAttribute("NAME"));
            assertEquals("second", l.getFeature(2).getAttribute("NAME"));
            double[] ext = l.getExtent();
            assertEquals(1.0, ext[0], 1e-9);
            assertEquals(2.0, ext[2], 1e-9);
        }
    }

    @Test
    @DisplayName("多个图层各自独立成表(物理编号递增)")
    void multipleLayers(@TempDir Path tmp) throws Exception {
        Path gdb = tmp.resolve("multi.gdb");
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            for (int i = 0; i < 4; i++) {
                ds.createLayer("layer" + i, SpatialReference.fromEpsg(4326),
                        GeometryType.POINT, pointFields());
            }
            ds.flush();
        }
        try (Stream<Path> s = Files.list(gdb)) {
            List<String> tables = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".gdbtable"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            // 7 张系统表 + 4 张用户表
            assertEquals(11, tables.size());
            assertTrue(tables.contains("a00000009.gdbtable"));
            assertTrue(tables.contains("a0000000c.gdbtable"));
        }
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            assertEquals(list("layer0", "layer1", "layer2", "layer3"), ds.getLayerNames());
        }
    }

    @Test
    @DisplayName("按序号取图层:与 getLayerNames 同序,系统表不计入")
    void layerByIndex(@TempDir Path tmp) throws Exception {
        Path gdb = tmp.resolve("byindex.gdb");

        // 空库:0 个用户图层(7 张系统表都不算)
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            assertEquals(0, ds.getLayerCount());
            assertThrows(GdbNotFoundException.class, () -> ds.getLayer(0));

            // 两个要素类 + 一张非空间表,顺序即创建顺序
            ds.createLayer("p0", SpatialReference.fromEpsg(4326),
                    GeometryType.POINT, pointFields());
            ds.createLayer("p1", SpatialReference.fromEpsg(4326),
                    GeometryType.POINT, pointFields());
            ds.createTable("t2", list(new GdbField("K", FieldType.STRING).setLength(16)));
            ds.flush();

            assertEquals(3, ds.getLayerCount());
            assertEquals(ds.getLayerNames().size(), ds.getLayerCount());

            // 序号顺序与名字顺序一致,且与按名字取到的是同一个对象(走缓存)
            for (int i = 0; i < ds.getLayerCount(); i++) {
                assertEquals(ds.getLayerNames().get(i), ds.getLayer(i).getName());
                assertSame(ds.getLayer(ds.getLayerNames().get(i)), ds.getLayer(i));
            }
            assertEquals("p0", ds.getLayer(0).getName());
            assertEquals(GeometryType.POINT, ds.getLayer(0).getGeometryType());
            assertEquals("t2", ds.getLayer(2).getName());
            assertEquals(GeometryType.UNKNOWN, ds.getLayer(2).getGeometryType());
        }

        // 重开(只读)后序号仍然稳定
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            assertEquals(3, ds.getLayerCount());
            assertEquals(list("p0", "p1", "t2"), ds.getLayerNames());
            assertEquals("p1", ds.getLayer(1).getName());
            assertEquals(0, ds.getLayer(0).getFeatureCount());

            // 系统表不能按序号取到 —— 序号空间里根本没有它们
            for (int i = 0; i < ds.getLayerCount(); i++) {
                assertFalse(ds.getLayer(i).getName().startsWith("GDB_"),
                        "系统表不应出现在序号空间里: " + ds.getLayer(i).getName());
            }
            // 但按名字仍可取系统表
            assertNotNull(ds.getLayer("GDB_Items"));
        }
    }

    @Test
    @DisplayName("按序号取图层:越界抛 GdbNotFoundException(含负数)")
    void layerByIndexOutOfRange(@TempDir Path tmp) throws Exception {
        Path gdb = tmp.resolve("range.gdb");
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            ds.createLayer("only", SpatialReference.fromEpsg(4326),
                    GeometryType.POINT, pointFields());
            ds.flush();

            assertEquals(1, ds.getLayerCount());
            assertNotNull(ds.getLayer(0));
            // 下线:-1(负数);上线:== getLayerCount()
            assertThrows(GdbNotFoundException.class, () -> ds.getLayer(-1));
            assertThrows(GdbNotFoundException.class, () -> ds.getLayer(1));
            assertThrows(GdbNotFoundException.class, () -> ds.getLayer(Integer.MAX_VALUE));
        }
        // 只读打开后同样越界保护
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            assertThrows(GdbNotFoundException.class, () -> ds.getLayer(-1));
            assertThrows(GdbNotFoundException.class, () -> ds.getLayer(1));
        }
    }

    // ==================================================================
    // 错误语义
    // ==================================================================

    @Test
    @DisplayName("路径不存在 / 不是目录 / 缺系统表 -> GdbFileNotFoundException")
    void openErrors(@TempDir Path tmp) throws IOException {
        assertThrows(GdbFileNotFoundException.class,
                () -> OpenFileGdbDataset.open(tmp.resolve("nope.gdb")));

        Path file = tmp.resolve("afile.gdb");
        Files.write(file, new byte[]{1});
        assertThrows(GdbFileNotFoundException.class, () -> OpenFileGdbDataset.open(file));

        Path emptyDir = tmp.resolve("empty.gdb");
        Files.createDirectories(emptyDir);
        assertThrows(GdbFileNotFoundException.class, () -> OpenFileGdbDataset.open(emptyDir));
    }

    @Test
    @DisplayName("重复建库 / 重名图层 / 未知图层 的错误")
    void writeErrors(@TempDir Path tmp) throws Exception {
        Path gdb = tmp.resolve("dup.gdb");
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            ds.createLayer("a", SpatialReference.fromEpsg(4326), GeometryType.POINT, pointFields());
            assertThrows(GdbWriteException.class,
                    () -> ds.createLayer("a", SpatialReference.fromEpsg(4326),
                            GeometryType.POINT, pointFields()));
            assertThrows(GdbNotFoundException.class, () -> ds.getLayer("missing"));
            ds.flush();
        }
        assertThrows(GdbWriteException.class, () -> OpenFileGdbDataset.create(gdb));
    }

    @Test
    @DisplayName("只读数据集上写操作应被拒绝")
    void readOnlyRejectsWrite(@TempDir Path tmp) throws Exception {
        Path gdb = tmp.resolve("ro.gdb");
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            ds.createLayer("p", SpatialReference.fromEpsg(4326), GeometryType.POINT, pointFields());
            ds.flush();
        }
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            assertThrows(GdbWriteException.class,
                    () -> ds.createLayer("q", SpatialReference.fromEpsg(4326),
                            GeometryType.POINT, pointFields()));
        }
    }
}
