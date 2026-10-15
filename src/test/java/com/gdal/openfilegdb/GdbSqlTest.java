package com.gdal.openfilegdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gdal.openfilegdb.dataset.OpenFileGdbDataset;
import com.gdal.openfilegdb.exception.GdbSqlException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.sql.GdbSqlResult;
import com.gdal.openfilegdb.srs.SpatialReference;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code executeSql} 端到端测试:查询语义(投影 / WHERE / 聚合 / 排序 / 去重)、
 * 大小写不敏感、几何列与日期时间列的往返、空间过滤、错误语义。
 *
 * <p>时间戳断言特意覆盖 {@code WHERE seen = TIMESTAMP '...'} —— 这是 GdbSqlTypes
 * 采用 UTC 约定的动机:字面量与列值必须对得上,且结果里的 LocalDateTime 要还原成
 * 写入时的墙上时钟。
 */
class GdbSqlTest {

    @TempDir
    static Path tmp;

    static Path gdb;

    @BeforeAll
    static void buildFixture() throws IOException {
        gdb = tmp.resolve("sql.gdb");
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.create(gdb)) {
            List<GdbField> fields = java.util.Arrays.asList(
                    new GdbField("NAME", FieldType.STRING).setLength(64),
                    new GdbField("VALUE", FieldType.INT32),
                    new GdbField("SCORE", FieldType.FLOAT64),
                    new GdbField("SEEN", FieldType.DATETIME));
            SpatialReference srs = SpatialReference.fromEpsg(4326);
            com.gdal.openfilegdb.layer.OpenFileGdbLayer layer =
                    ds.createLayer("Points", srs, GeometryType.POINT, fields);
            layer.createFeature(feature(116.4, 39.9, "北京", 10, 99.5,
                    LocalDateTime.of(2020, 1, 1, 0, 0, 0)));
            layer.createFeature(feature(121.473, 31.230, "上海", 20, 88.25,
                    LocalDateTime.of(2021, 6, 15, 12, 30, 45)));
            layer.createFeature(feature(113.264, 23.129, "广州", 30, 77.0, null));
            // 第 4 条:空 name / 空 value,专测 NULL 语义
            layer.createFeature(feature(100.0, 0.0, null, null, null,
                    LocalDateTime.of(2022, 12, 31, 23, 59, 59)));

            com.gdal.openfilegdb.layer.OpenFileGdbLayer zones =
                    ds.createLayer("Zones", srs, GeometryType.POLYGON,
                            java.util.Arrays.asList(
                                    new GdbField("ZONE", FieldType.STRING).setLength(16)));
            zones.createFeature(poly(0, 0, 10, 10, "A"));
            zones.createFeature(poly(20, 20, 30, 30, "B"));
            zones.createFeature(poly(100, 100, 110, 110, "C"));
            ds.flush();
        }
    }

    private static GdbFeature feature(double x, double y, String name, Integer value,
                                      Double score, LocalDateTime seen) {
        GdbFeature f = new GdbFeature();
        f.setGeometry(GdbGeometry.point(x, y));
        f.setAttribute("NAME", name);
        f.setAttribute("VALUE", value);
        f.setAttribute("SCORE", score);
        f.setAttribute("SEEN", seen);
        return f;
    }

    private static GdbFeature poly(double x0, double y0, double x1, double y1, String zone) {
        GdbFeature f = new GdbFeature();
        f.setGeometry(GdbGeometry.polygon(
                new double[]{x0, x1, x1, x0, x0},
                new double[]{y0, y0, y1, y1, y0},
                new int[]{0, 5}, new int[]{0}));
        f.setAttribute("ZONE", zone);
        return f;
    }

    // ==================================================================

    @Test
    @DisplayName("SELECT *:全列全行,列名与磁盘字段一致")
    void selectStar() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows("SELECT * FROM Points")) {
            assertEquals(
                    java.util.Arrays.asList("OBJECTID", "Shape", "NAME", "VALUE", "SCORE", "SEEN"),
                    rs.getColumnNames());
            List<Object[]> rows = rs.toList();
            assertEquals(4, rows.size());
            assertEquals(1L, rows.get(0)[0]);
            assertEquals("北京", rows.get(0)[2]);
            assertEquals(10, rows.get(0)[3]);
            assertEquals(99.5, rows.get(0)[4]);
            assertEquals(LocalDateTime.of(2020, 1, 1, 0, 0, 0), rows.get(0)[5]);
        }
    }

    @Test
    @DisplayName("投影 + WHERE 下推(表名/字段名大小写不敏感,别名生效)")
    void projectAndFilter() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows(
                     "select name AS n, \"VALUE\" from POINTS where \"VALUE\" >= 20")) {
            assertEquals(java.util.Arrays.asList("n", "VALUE"), rs.getColumnNames());
            List<Object[]> rows = rs.toList();
            assertEquals(2, rows.size());
            assertEquals("上海", rows.get(0)[0]);
            assertEquals(20, rows.get(0)[1]);
            assertEquals("广州", rows.get(1)[0]);
        }
    }

    @Test
    @DisplayName("未投影几何不解码:纯属性查询的几何列不可见,命中行几何为 null 槽")
    void geometryOnlyWhenProjected() {
        // 没投影 Shape,SELECT 列里就没有几何;这里主要验证纯属性查询能正常流式跑完
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows(
                     "SELECT NAME FROM Points WHERE NAME IS NOT NULL")) {
            List<Object[]> rows = rs.toList();
            assertEquals(3, rows.size());
            assertEquals("北京", rows.get(0)[0]);
        }
    }

    @Test
    @DisplayName("几何列:投影后是 GdbGeometry,与直接读图层逐字一致")
    void geometryProjection() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            // 期望值取自直接读图层(GDB 的量化会带来 1e-14 级别的抖动,
            // 断言 SQL 路径与普通读取路径一致,而不是断言某个理想坐标)
            String expected = ds.getLayer("Points").getFeature(1).getGeometry().toWkt();
            try (GdbSqlResult rs = ds.executeSqlRows(
                    "SELECT OBJECTID, Shape FROM Points WHERE OBJECTID = 1")) {
                List<Object[]> rows = rs.toList();
                assertEquals(1, rows.size());
                GdbGeometry geom = (GdbGeometry) rows.get(0)[1];
                assertEquals(expected, geom.toWkt());
                assertTrue(expected.startsWith("POINT (116.39"), expected);
            }
        }
    }

    @Test
    @DisplayName("COUNT/SUM/AVG/MIN/MAX 聚合")
    void aggregates() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows(
                     "SELECT COUNT(*) AS n, COUNT(\"VALUE\") AS nv, SUM(\"VALUE\") AS s, "
                             + "AVG(score) AS a, MIN(seen) AS lo, MAX(seen) AS hi "
                             + "FROM Points")) {
            List<Object[]> rows = rs.toList();
            assertEquals(1, rows.size());
            // 聚合结果的具体数值类型由 Calcite 定(COUNT→BIGINT,SUM(INT32)→INTEGER),
            // 断言按数值比
            assertEquals(4L, num(rows.get(0)[0]));       // COUNT(*) 计所有行
            assertEquals(3L, num(rows.get(0)[1]));       // COUNT(列) 跳过该列的 NULL
            assertEquals(60L, num(rows.get(0)[2]));      // SUM 跳过 NULL:10+20+30
            assertEquals((99.5 + 88.25 + 77.0) / 3, (Double) rows.get(0)[3], 1e-9);
            assertEquals(LocalDateTime.of(2020, 1, 1, 0, 0, 0), rows.get(0)[4]);
            assertEquals(LocalDateTime.of(2022, 12, 31, 23, 59, 59), rows.get(0)[5]);
        }
    }

    /** 取数值的 long 值(聚合结果的宽度由 Calcite 定,测试只关心数值)。 */
    private static long num(Object v) {
        return ((Number) v).longValue();
    }

    @Test
    @DisplayName("GROUP BY + ORDER BY + LIMIT")
    void groupOrderByLimit() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows(
                     "SELECT zone, COUNT(*) AS n FROM Zones GROUP BY zone "
                             + "ORDER BY zone DESC LIMIT 2")) {
            List<Object[]> rows = rs.toList();
            assertEquals(2, rows.size());
            assertEquals("C", rows.get(0)[0]);
            assertEquals(1L, rows.get(0)[1]);
            assertEquals("B", rows.get(1)[0]);
        }
    }

    @Test
    @DisplayName("DISTINCT")
    void distinct() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows("SELECT DISTINCT \"VALUE\" FROM Points "
                     + "WHERE \"VALUE\" IS NOT NULL ORDER BY \"VALUE\"")) {
            List<Object[]> rows = rs.toList();
            assertEquals(3, rows.size());
            assertEquals(10, rows.get(0)[0]);
            assertEquals(30, rows.get(2)[0]);
        }
    }

    @Test
    @DisplayName("WHERE 与 TIMESTAMP 字面量比较(UTC 约定:墙上时钟直接对得上)")
    void timestampLiterals() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult eq = ds.executeSqlRows(
                     "SELECT NAME FROM Points WHERE SEEN = TIMESTAMP '2021-06-15 12:30:45'");
             GdbSqlResult ge = ds.executeSqlRows(
                     "SELECT NAME FROM Points WHERE SEEN >= TIMESTAMP '2022-01-01 00:00:00' "
                             + "ORDER BY OBJECTID")) {
            assertEquals(java.util.Arrays.asList("上海"), eq.toList().stream()
                    .map(r -> r[0]).collect(java.util.stream.Collectors.toList()));
            // 2022 之后只剩第 4 条(name 为 null)
            List<Object[]> rows = ge.toList();
            assertEquals(1, rows.size());
            assertNull(rows.get(0)[0]);
        }
    }

    @Test
    @DisplayName("NULL 三值逻辑:= NULL 查不到东西,IS NULL 查得到")
    void nullSemantics() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult eqNull = ds.executeSqlRows("SELECT OBJECTID FROM Points WHERE NAME = NULL");
             GdbSqlResult isNull = ds.executeSqlRows(
                     "SELECT OBJECTID FROM Points WHERE NAME IS NULL");
             GdbResultWrapper gt = new GdbResultWrapper(ds.executeSqlRows(
                     "SELECT OBJECTID FROM Points WHERE \"VALUE\" > 5 OR \"VALUE\" IS NULL "
                             + "ORDER BY OBJECTID"))) {
            assertEquals(0, eqNull.toList().size());
            assertEquals(java.util.Arrays.asList(4L),
                    isNull.toList().stream().map(r -> r[0])
                            .collect(java.util.stream.Collectors.toList()));
            // 10/20/30 都 > 5,加 null 值那条
            assertEquals(java.util.Arrays.asList(1L, 2L, 3L, 4L),
                    gt.toList().stream().map(r -> r[0])
                            .collect(java.util.stream.Collectors.toList()));
        }
    }

    /** 局部小包装:让多个结果按声明顺序统一 close。 */
    private static final class GdbResultWrapper implements AutoCloseable {
        private final GdbSqlResult result;

        GdbResultWrapper(GdbSqlResult result) {
            this.result = result;
        }

        List<Object[]> toList() {
            return result.toList();
        }

        @Override
        public void close() {
            result.close();
        }
    }

    @Test
    @DisplayName("AND / NOT 组合")
    void booleanCombination() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows(
                     "SELECT NAME FROM Points WHERE \"VALUE\" > 10 AND NOT (\"VALUE\" >= 30)")) {
            List<Object[]> rows = rs.toList();
            assertEquals(1, rows.size());
            assertEquals("上海", rows.get(0)[0]);
        }
    }

    @Test
    @DisplayName("空间过滤(bbox):走几何列精确相交,语义同 layer.search")
    void spatialFilter() {
        // 5..15 的矩形只压到 A(0..10);C(100..110) 远离
        double[] bbox = {5, 5, 15, 15};
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows("SELECT ZONE FROM Zones ORDER BY ZONE", bbox)) {
            List<Object[]> rows = rs.toList();
            assertEquals(1, rows.size());
            assertEquals("A", rows.get(0)[0]);
        }
    }

    @Test
    @DisplayName("遍历与 reset:OGR 式单游标,reset 后重新执行")
    void cursorAndReset() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows("SELECT NAME FROM Points ORDER BY OBJECTID")) {
            // for-each 走 iterator()(返回 this)
            int seen = 0;
            for (Object[] ignored : rs) {
                seen++;
            }
            assertEquals(4, seen);
            assertFalse(rs.hasNext());
            rs.reset();
            assertEquals("北京", rs.nextRow()[0]);
            assertEquals("上海", rs.nextRow()[0]);
        }
    }

    @Test
    @DisplayName("关闭后再用要报错;关闭幂等")
    void closedSemantics() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbSqlResult rs = ds.executeSqlRows("SELECT 1");
            rs.close();
            rs.close();
            assertThrows(GdbSqlException.class, () -> rs.nextRow());
        }
    }

    @Test
    @DisplayName("非查询语句拒绝(只读 SQL 层)")
    void rejectsNonQuery() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            GdbSqlException e = assertThrows(GdbSqlException.class,
                    () -> ds.executeSql("DROP TABLE Points"));
            assertTrue(e.getMessage().contains("只支持查询语句"));
            assertThrows(GdbSqlException.class,
                    () -> ds.executeSql("DELLAYER:Points"));
            assertThrows(GdbSqlException.class,
                    () -> ds.executeSql("INSERT INTO Points VALUES (1)"));
        }
    }

    @Test
    @DisplayName("语法错误 / 表不存在 / 字段不存在都抛 GdbSqlException")
    void errorMessages() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            assertThrows(GdbSqlException.class, () -> ds.executeSql("SELEC * FROM Points"));
            assertThrows(GdbSqlException.class, () -> ds.executeSql("SELECT * FROM NoSuchTable"));
            assertThrows(GdbSqlException.class,
                    () -> ds.executeSql("SELECT no_such_col FROM Points"));
        }
    }

    @Test
    @DisplayName("空语句与错误 bbox 拒绝")
    void rejectsEmptyAndBadBbox() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            assertThrows(GdbSqlException.class, () -> ds.executeSql("   "));
            assertThrows(GdbSqlException.class, () -> ds.executeSql("SELECT * FROM Points",
                    new double[]{1, 2, 3}));
        }
    }

    @Test
    @DisplayName("带尾分号的语句可用;含引号字符串比较")
    void trailingSemicolonAndStrings() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows(
                     "SELECT \"VALUE\" FROM Points WHERE NAME = '广州';")) {
            List<Object[]> rows = rs.toList();
            assertEquals(1, rows.size());
            assertEquals(30, rows.get(0)[0]);
        }
    }

    @Test
    @DisplayName("SQL 保留字作字段名须加双引号(VALUE 是 Calcite 保留字)")
    void reservedWordNeedsQuoting() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb)) {
            // 加引号可用
            try (GdbSqlResult rs = ds.executeSqlRows(
                    "SELECT \"VALUE\" FROM Points WHERE \"value\" = 20")) {
                assertEquals(1, rs.toList().size());
            }
            // 不加引号是语法错误(Calcite 的保留字表,与 OGR SQL 的宽松解析不同)
            assertThrows(GdbSqlException.class,
                    () -> ds.executeSql("SELECT VALUE FROM Points"));
        }
    }

    @Test
    @DisplayName("中文字面量与中文图层名全程可用(Calcite 默认 ISO-8859-1 的坑)")
    void chineseLiterals() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows(
                     "SELECT NAME FROM Points WHERE NAME = '上海' OR NAME = '广州' "
                             + "ORDER BY NAME")) {
            List<Object[]> rows = rs.toList();
            assertEquals(2, rows.size());
            assertEquals("上海", rows.get(0)[0]);
            assertEquals("广州", rows.get(1)[0]);
        }
    }

    @Test
    @DisplayName("跨表查询(WHERE 里同时用两个图层的字段不必,只验证多图层注册可见)")
    void multiLayerVisible() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult p = ds.executeSqlRows("SELECT COUNT(*) AS n FROM Points");
             GdbSqlResult z = ds.executeSqlRows("SELECT COUNT(*) AS n FROM Zones")) {
            assertEquals(4L, p.toList().get(0)[0]);
            assertEquals(3L, z.toList().get(0)[0]);
        }
    }

    @Test
    @DisplayName("几何列 IS NULL / IS NOT NULL")
    void geometryNullCheck() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows(
                     "SELECT COUNT(*) AS n FROM Points WHERE Shape IS NOT NULL")) {
            assertEquals(4L, rs.toList().get(0)[0]);
        }
    }

    @Test
    @DisplayName("结果 toString / 列数元信息")
    void metadataBasics() {
        try (OpenFileGdbDataset ds = OpenFileGdbDataset.open(gdb);
             GdbSqlResult rs = ds.executeSqlRows("SELECT NAME, \"VALUE\" FROM Points")) {
            assertEquals(2, rs.getColumnCount());
            assertTrue(rs.toString().contains("NAME"));
            Object[] first = rs.nextRow();
            assertInstanceOf(String.class, first[0]);
        }
    }
}
