package com.gdal.openfilegdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gdal.openfilegdb.srs.SpatialReference;
import com.gdal.openfilegdb.srs.WktSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 空间参考:内建 WKT 表、WKT → WKID 反解、以及 ESRI 风格 WKT(无 AUTHORITY)的还原。
 *
 * <p>这一层之所以值得单独测,是因为 ArcGIS 与 GDAL 写进 {@code .gdb} 的
 * {@code SRTEXT} 都是<b>没有 AUTHORITY 节点</b>的 ESRI 风格 WKT1(实测 GDAL 为
 * EPSG:4326 写出的文本与本库 {@link WktSupport#builtinWkt(int)} 逐字符相同)。
 * 只按 AUTHORITY 解析会把 WKID 丢掉,读回来的图层就成了"无坐标系"。
 */
class SpatialReferenceTest {

    @Test
    @DisplayName("内建 WKT 的 EPSG 反查(含空白/大小写容忍)")
    void builtinEpsg() {
        int[] epsgs = {4326, 3857, 4490, 4269, 3395, 32650};
        for (int epsg : epsgs) {
            String wkt = WktSupport.builtinWkt(epsg);
            assertFalse(wkt.isEmpty(), "EPSG:" + epsg + " 应内建 WKT");
            assertEquals(epsg, WktSupport.builtinEpsg(wkt), "EPSG:" + epsg + " 反查失败");
        }
        // 这些内建 WKT 里都没有 AUTHORITY,所以纯 AUTHORITY 解析必然拿不到
        assertEquals(0, WktSupport.extractLastAuthorityCode(WktSupport.builtinWkt(4326)));
        // 空白与大小写不影响反查
        String mixedCase = "  geogcs[ \"gcs_wgs_1984\", DATUM[\"d_wgs_1984\","
                + "SPHEROID[\"wgs_1984\",6378137.0,298.257223563]],PRIMEM[\"greenwich\",0.0],"
                + "UNIT[\"degree\",0.0174532925199433]]  ";
        assertEquals(4326, WktSupport.builtinEpsg(mixedCase));
        // 未收录的 WKT 返回 0,不抛异常
        assertEquals(0, WktSupport.builtinEpsg("GEOGCS[\"something-else\"]"));
        assertEquals(0, WktSupport.builtinEpsg(null));
        assertEquals(0, WktSupport.builtinEpsg(""));
    }

    @Test
    @DisplayName("fromWkt:ESRI 风格 WKT 也能还原出 WKID")
    void fromWktEsri() {
        SpatialReference srs = SpatialReference.fromWkt(WktSupport.builtinWkt(4326));
        assertEquals(4326, srs.getWkid(), "ESRI 风格 WKT 应靠内建表还原 WKID");
        assertEquals("GCS_WGS_1984", srs.getName());
        assertFalse(srs.isEmpty());
    }

    @Test
    @DisplayName("fromWkt:AUTHORITY 优先于内建表")
    void fromWktAuthorityWins() {
        // 带 AUTHORITY 的 WKT 走第一级解析,即使文本与内建表不同也能拿到码
        SpatialReference srs = SpatialReference.fromWkt(
                "PROJCS[\"自定义\",GEOGCS[\"GCS_WGS_1984\"],AUTHORITY[\"EPSG\",\"4547\"]]");
        assertEquals(4547, srs.getWkid());
        assertEquals("4547", String.valueOf(srs.getWkid()));
    }

    @Test
    @DisplayName("of / fromEpsg / withWkid 的构造口径")
    void constructors() {
        assertTrue(SpatialReference.EMPTY.isEmpty());
        assertEquals(4490, SpatialReference.fromEpsg(4490).getWkid());
        assertEquals("GCS_China_Geodetic_Coordinate_System_2000",
                SpatialReference.fromEpsg(4490).getName());
        // 未内建的 EPSG 只带码、WKT 为空;调用方应改用 of(wkid, wkt)
        SpatialReference unknown = SpatialReference.fromEpsg(999999);
        assertEquals(999999, unknown.getWkid());
        assertTrue(unknown.getWkt().isEmpty());

        SpatialReference of = SpatialReference.of(4326, WktSupport.builtinWkt(4326));
        assertEquals(4326, of.getWkid());
        assertEquals(0, of.getLatestWkid(), "of() 不带 latestWkid");
        SpatialReference withLatest = of.withWkid(4326, 4326);
        assertEquals(4326, withLatest.getWkid());
        assertEquals(4326, withLatest.getLatestWkid());
        assertEquals(of.getWkt(), withLatest.getWkt());
        assertEquals(of.getName(), withLatest.getName());
        // equals 会把 latestWkid 也算进去,所以两者并不相等
        assertNotEquals(of, withLatest);
    }
}
