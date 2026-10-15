package com.gdal.openfilegdb.dataset;

import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeomField;
import com.gdal.openfilegdb.table.GdbTable;
import com.gdal.openfilegdb.util.GdbDateTimeUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.gdal.openfilegdb.util.GdbCollections.list;

/**
 * 空 FileGDB 的<b>系统表样板数据</b>。
 *
 * <p>逐行对照 Python 参考实现 {@code pyopenfilegdb/_gdb_template.py}。那里保存的是
 * 一个由 ArcGIS 10.x 建出的真实 {@code .gdb} 里 7 张系统表的<b>字段定义与全部行数据</b>
 * (不含用户要素类),本类把它原样转成 Java 常量;{@link #writeSystemTables(Path)}
 * 照着建库,使产物在结构上与 ArcGIS 的"新建文件地理数据库"一致。
 *
 * <p>样品来源与 GDAL 的写入顺序一致,见
 * {@code ogropenfilegdbdatasource_write.cpp::Create()} 末尾那句
 * {@code CreateGDBSystemCatalog() && CreateGDBDBTune() && CreateGDBSpatialRefs()
 * && CreateGDBItems() && CreateGDBItemTypes() && CreateGDBItemRelationships()
 * && CreateGDBItemRelationshipTypes()}:
 * <pre>
 *   a00000001 GDB_SystemCatalog
 *   a00000002 GDB_DBTune
 *   a00000003 GDB_SpatialRefs
 *   a00000004 GDB_Items
 *   a00000005 GDB_ItemTypes
 *   a00000006 GDB_ItemRelationships
 *   a00000007 GDB_ItemRelationshipTypes
 * </pre>
 *
 * <p><b>不建</b> {@code a00000008}(GDB_ReplicaLog):GDAL 同样不建,注释写着
 * "GDB_ReplicaLog can be omitted"。但注意 {@code a00000001} 的行数据里
 * <b>仍然有 8 条</b>(含 GDB_ReplicaLog 一条),所以第一个用户图层的物理编号是
 * {@code a00000009} —— 物理编号规则见 {@link GdbSystemCatalog}。
 *
 * <p>⚠️ 系统表的 {@code stringsAreUtf8} 为 {@code true}(真实 ArcGIS 系统表实测如此),
 * 与用户图层默认的 {@code false}(UTF-16LE)不同。
 *
 * <p>⚠️ <b>代码风格例外:</b>本类里存在若干超过 100 列的行(Google Java Style 的
 * 列宽上限)。它们<b>全部</b>是"逐行照抄"的样板字符串(ESRI WKT、Definition XML
 * 片段、GUID、配置说明文本),拆行会破坏与 {@code _gdb_template.py} 的逐行可对照性。
 * 按 Google Java Style 对"无法折行的长 token"的豁免保留原样。
 */
public final class GdbTemplates {

    private GdbTemplates() {
    }

    /**
     * 一个字段的样板定义。
     *
     * <p>对应 {@code _gdb_template.py} 里字段元组
     * {@code (名字, FGFT 类型, nullable, required, editable, maxWidth, geom_params)}。
     *
     * @param name     字段名
     * @param type     字段类型
     * @param nullable 是否可空
     * @param required 是否必填
     * @param editable 是否可编辑
     * @param maxWidth 字符串最大宽度(非字符串为 0)
     * @param geom     几何字段的量化参数;非几何字段为 {@code null}
     */
    public static final class FieldSpec {
        private final String name;
        private final FieldType type;
        private final boolean nullable;
        private final boolean required;
        private final boolean editable;
        private final int maxWidth;
        private final GeomSpec geom;

        public FieldSpec(String name, FieldType type, boolean nullable, boolean required,
                         boolean editable, int maxWidth, GeomSpec geom) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.required = required;
            this.editable = editable;
            this.maxWidth = maxWidth;
            this.geom = geom;
        }

        public String name() {
            return name;
        }

        public FieldType type() {
            return type;
        }

        public boolean nullable() {
            return nullable;
        }

        public boolean required() {
            return required;
        }

        public boolean editable() {
            return editable;
        }

        public int maxWidth() {
            return maxWidth;
        }

        public GeomSpec geom() {
            return geom;
        }
    }

    /**
     * 几何字段的样板量化参数。
     *
     * <p>对应 {@code _gdb_template.py} 里几何字段元组去掉名字/标志之后的
     * {@code (wkt, xOrigin, yOrigin, xyScale, xyTolerance, hasM, mOrigin, mScale,
     * mTolerance, hasZ, zOrigin, zScale, zTolerance, xmin, ymin, xmax, ymax, grids)}。
     */
    public static final class GeomSpec {
        private final String wkt;
        private final double xOrigin;
        private final double yOrigin;
        private final double xyScale;
        private final double xyTolerance;
        private final boolean hasM;
        private final double mOrigin;
        private final double mScale;
        private final double mTolerance;
        private final boolean hasZ;
        private final double zOrigin;
        private final double zScale;
        private final double zTolerance;
        private final double xmin;
        private final double ymin;
        private final double xmax;
        private final double ymax;
        private final double[] grids;

        public GeomSpec(String wkt, double xOrigin, double yOrigin, double xyScale,
                        double xyTolerance, boolean hasM, double mOrigin, double mScale,
                        double mTolerance, boolean hasZ, double zOrigin, double zScale,
                        double zTolerance, double xmin, double ymin, double xmax, double ymax,
                        double[] grids) {
            this.wkt = wkt;
            this.xOrigin = xOrigin;
            this.yOrigin = yOrigin;
            this.xyScale = xyScale;
            this.xyTolerance = xyTolerance;
            this.hasM = hasM;
            this.mOrigin = mOrigin;
            this.mScale = mScale;
            this.mTolerance = mTolerance;
            this.hasZ = hasZ;
            this.zOrigin = zOrigin;
            this.zScale = zScale;
            this.zTolerance = zTolerance;
            this.xmin = xmin;
            this.ymin = ymin;
            this.xmax = xmax;
            this.ymax = ymax;
            this.grids = grids;
        }

        public String wkt() {
            return wkt;
        }

        public double xOrigin() {
            return xOrigin;
        }

        public double yOrigin() {
            return yOrigin;
        }

        public double xyScale() {
            return xyScale;
        }

        public double xyTolerance() {
            return xyTolerance;
        }

        public boolean hasM() {
            return hasM;
        }

        public double mOrigin() {
            return mOrigin;
        }

        public double mScale() {
            return mScale;
        }

        public double mTolerance() {
            return mTolerance;
        }

        public boolean hasZ() {
            return hasZ;
        }

        public double zOrigin() {
            return zOrigin;
        }

        public double zScale() {
            return zScale;
        }

        public double zTolerance() {
            return zTolerance;
        }

        public double xmin() {
            return xmin;
        }

        public double ymin() {
            return ymin;
        }

        public double xmax() {
            return xmax;
        }

        public double ymax() {
            return ymax;
        }

        public double[] grids() {
            return grids;
        }
    }

    /**
     * 一张系统表的样板。
     *
     * @param physicalName    物理文件名(不含 {@code .gdbtable}),如 {@code a00000001}
     * @param tableGeomType   表级几何类型({@code FGTGT_*})
     * @param hasZ            表级 hasZ(与几何字段自己的 Z 量化参数是两回事)
     * @param hasM            表级 hasM
     * @param stringsAreUtf8  字符串是否 UTF-8
     * @param fields          字段清单
     * @param rows            逐行数据,每行与 {@code fields} 等长(OBJECTID/几何列会被跳过)
     */
    public static final class TableSpec {
        private final String physicalName;
        private final int tableGeomType;
        private final boolean hasZ;
        private final boolean hasM;
        private final boolean stringsAreUtf8;
        private final List<FieldSpec> fields;
        private final List<Object[]> rows;

        public TableSpec(String physicalName, int tableGeomType, boolean hasZ, boolean hasM,
                         boolean stringsAreUtf8, List<FieldSpec> fields, List<Object[]> rows) {
            this.physicalName = physicalName;
            this.tableGeomType = tableGeomType;
            this.hasZ = hasZ;
            this.hasM = hasM;
            this.stringsAreUtf8 = stringsAreUtf8;
            this.fields = fields;
            this.rows = rows;
        }

        public String physicalName() {
            return physicalName;
        }

        public int tableGeomType() {
            return tableGeomType;
        }

        public boolean hasZ() {
            return hasZ;
        }

        public boolean hasM() {
            return hasM;
        }

        public boolean stringsAreUtf8() {
            return stringsAreUtf8;
        }

        public List<FieldSpec> fields() {
            return fields;
        }

        public List<Object[]> rows() {
            return rows;
        }
    }

    // ==================================================================
    // 样板数据(逐行对照 _gdb_template.py::SYSTEM_TABLES)
    // ==================================================================

    /**
     * 7 张系统表的样板(字段 + 行数据)。
     *
     * @return 不可变的样板列表,顺序与 GDAL 写入顺序一致
     */
    public static List<TableSpec> systemTables() {
        return SYSTEM_TABLES;
    }

    private static final List<TableSpec> SYSTEM_TABLES = list(
            systemCatalog(),
            dbTune(),
            spatialRefs(),
            items(),
            itemTypes(),
            itemRelationships(),
            itemRelationshipTypes());

    /** {@code a00000001 GDB_SystemCatalog}。对应 Python 样板第一项。 */
    private static TableSpec systemCatalog() {
        List<FieldSpec> f = list(
                f("ID", 6, false, true, false, 0),
                f("Name", 4, false, false, true, 160),
                f("FileFormat", 1, false, false, true, 0));
        List<Object[]> rows = list(
                r(1, "GDB_SystemCatalog", 0),
                r(2, "GDB_DBTune", 0),
                r(3, "GDB_SpatialRefs", 0),
                r(4, "GDB_Items", 0),
                r(5, "GDB_ItemTypes", 0),
                r(6, "GDB_ItemRelationships", 0),
                r(7, "GDB_ItemRelationshipTypes", 0),
                r(8, "GDB_ReplicaLog", 2));
        return new TableSpec("a00000001", 0, false, false, true, f, rows);
    }

    /** {@code a00000002 GDB_DBTune}。注意本表<b>没有</b> OBJECTID 字段。 */
    private static TableSpec dbTune() {
        List<FieldSpec> f = list(
                f("Keyword", 4, false, false, true, 32),
                f("ParameterName", 4, false, false, true, 32),
                f("ConfigString", 4, true, false, true, 2048));
        List<Object[]> rows = list(
                r("DEFAULTS", "UI_TEXT", "The default datafile configuration."),
                r("DEFAULTS", "CHARACTER_FORMAT", "UTF8"),
                r("DEFAULTS", "GEOMETRY_FORMAT", "Compressed"),
                r("DEFAULTS", "GEOMETRY_STORAGE", "InLine"),
                r("DEFAULTS", "BLOB_STORAGE", "InLine"),
                r("DEFAULTS", "MAX_FILE_SIZE", "1TB"),
                r("DEFAULTS", "RASTER_STORAGE", "InLine"),
                r("TEXT_UTF16", "UI_TEXT", "The UTF16 text format configuration."),
                r("TEXT_UTF16", "CHARACTER_FORMAT", "UTF16"),
                r("MAX_FILE_SIZE_4GB", "UI_TEXT", "The 4GB maximum datafile size configuration."),
                r("MAX_FILE_SIZE_4GB", "MAX_FILE_SIZE", "4GB"),
                r("MAX_FILE_SIZE_256TB", "UI_TEXT", "The 256TB maximum datafile size configuration."),
                r("MAX_FILE_SIZE_256TB", "MAX_FILE_SIZE", "256TB"),
                r("GEOMETRY_UNCOMPRESSED", "UI_TEXT", "The Uncompressed Geometry configuration."),
                r("GEOMETRY_UNCOMPRESSED", "GEOMETRY_FORMAT", "Uncompressed"),
                r("GEOMETRY_OUTOFLINE", "UI_TEXT", "The Outofline Geometry configuration."),
                r("GEOMETRY_OUTOFLINE", "GEOMETRY_STORAGE", "OutOfLine"),
                r("BLOB_OUTOFLINE", "UI_TEXT", "The Outofline Blob configuration."),
                r("BLOB_OUTOFLINE", "BLOB_STORAGE", "OutOfLine"),
                r("GEOMETRY_AND_BLOB_OUTOFLINE", "UI_TEXT", "The Outofline Geometry and Blob configuration."),
                r("GEOMETRY_AND_BLOB_OUTOFLINE", "GEOMETRY_STORAGE", "OutOfLine"),
                r("GEOMETRY_AND_BLOB_OUTOFLINE", "BLOB_STORAGE", "OutOfLine"),
                r("TERRAIN_DEFAULTS", "UI_TERRAIN_TEXT", "The terrains default configuration."),
                r("TERRAIN_DEFAULTS", "GEOMETRY_STORAGE", "OutOfLine"),
                r("TERRAIN_DEFAULTS", "BLOB_STORAGE", "OutOfLine"),
                r("MOSAICDATASET_DEFAULTS", "UI_MOSAIC_TEXT", "The Outofline Raster and Blob configuration."),
                r("MOSAICDATASET_DEFAULTS", "RASTER_STORAGE", "OutOfLine"),
                r("MOSAICDATASET_DEFAULTS", "BLOB_STORAGE", "OutOfLine"),
                r("MOSAICDATASET_INLINE", "UI_MOSAIC_TEXT", "The mosaic dataset inline configuration."),
                r("MOSAICDATASET_INLINE", "CHARACTER_FORMAT", "UTF8"),
                r("MOSAICDATASET_INLINE", "GEOMETRY_FORMAT", "Compressed"),
                r("MOSAICDATASET_INLINE", "GEOMETRY_STORAGE", "InLine"),
                r("MOSAICDATASET_INLINE", "BLOB_STORAGE", "InLine"),
                r("MOSAICDATASET_INLINE", "MAX_FILE_SIZE", "1TB"),
                r("MOSAICDATASET_INLINE", "RASTER_STORAGE", "InLine"));
        return new TableSpec("a00000002", 0, false, false, true, f, rows);
    }

    /** {@code a00000003 GDB_SpatialRefs}。 */
    private static TableSpec spatialRefs() {
        List<FieldSpec> f = list(
                f("ID", 6, false, true, false, 0),
                f("SRTEXT", 4, false, false, true, 2048),
                f("FalseX", 3, true, false, true, 0),
                f("FalseY", 3, true, false, true, 0),
                f("XYUnits", 3, true, false, true, 0),
                f("FalseZ", 3, true, false, true, 0),
                f("ZUnits", 3, true, false, true, 0),
                f("FalseM", 3, true, false, true, 0),
                f("MUnits", 3, true, false, true, 0),
                f("XYTolerance", 3, true, false, true, 0),
                f("ZTolerance", 3, true, false, true, 0),
                f("MTolerance", 3, true, false, true, 0));
        List<Object[]> rows = list(
                r(1,
                        "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]",
                        -180.0, -90.0, 1000000.0, -100000.0, 10000.0, -100000.0, 10000.0,
                        2e-06, 0.001, 0.001),
                r(2,
                        "GEOGCS[\"GCS_China_Geodetic_Coordinate_System_2000\",DATUM[\"D_China_2000\",SPHEROID[\"CGCS2000\",6378137.0,298.257222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]",
                        -400.0, -400.0, 999999999.9999999, -100000.0, 10000.0, -100000.0, 10000.0,
                        8.983152841195213e-09, 0.001, 0.001));
        return new TableSpec("a00000003", 0, false, false, true, f, rows);
    }

    /**
     * {@code a00000004 GDB_Items}。
     *
     * <p>本表带一个几何字段 {@code Shape}(表级几何类型为 4 = Polygon,但表级
     * hasZ/hasM 都是 false,只有几何字段自身带 Z/M 量化参数 —— 与真实文件相符)。
     * 两行:根 Folder 与 Workspace。
     */
    private static TableSpec items() {
        GeomSpec shape = new GeomSpec(
                "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]",
                -180.0, -90.0, 1000000.0, 2e-06,
                true, -100000.0, 10000.0, 0.001,
                true, -100000.0, 10000.0, 0.001,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                new double[]{0.012, 0.4, 12.0});
        List<FieldSpec> f = list(
                f("ObjectID", 6, false, true, false, 0),
                f("UUID", 11, false, true, false, 0),
                f("Type", 10, false, false, true, 0),
                f("Name", 4, true, false, true, 160),
                f("PhysicalName", 4, true, false, true, 160),
                f("Path", 4, true, false, true, 260),
                f("DatasetSubtype1", 1, true, false, true, 0),
                f("DatasetSubtype2", 1, true, false, true, 0),
                f("DatasetInfo1", 4, true, false, true, 255),
                f("DatasetInfo2", 4, true, false, true, 255),
                f("URL", 4, true, false, true, 255),
                f("Definition", 12, true, false, true, 0),
                f("Documentation", 12, true, false, true, 0),
                f("ItemInfo", 12, true, false, true, 0),
                f("Properties", 1, true, false, true, 0),
                f("Defaults", 8, true, false, true, 0),
                geom("Shape", true, true, true, shape));
        List<Object[]> rows = list(
                r(1, "{EB539B11-FFE7-4E00-B302-A2A4845042AE}",
                        "{F3783E6F-65CA-4514-8315-CE3985DAD3B1}", "", "", "\\",
                        null, null, null, null, "", null, null, null, 1, null, null),
                r(2, "{2CD2AA92-6CB4-436F-8A99-5EF3DE8F0051}",
                        "{C673FE0F-7280-404F-8532-20755DD8FC06}", "Workspace", "WORKSPACE", "",
                        null, null, null, null, "",
                        "<DEWorkspace xsi:type='typens:DEWorkspace' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
                                + " xmlns:xs='http://www.w3.org/2001/XMLSchema'"
                                + " xmlns:typens='http://www.esri.com/schemas/ArcGIS/10.8'>"
                                + "<CatalogPath>\\</CatalogPath><Name></Name><ChildrenExpanded>false</ChildrenExpanded>"
                                + "<WorkspaceType>esriLocalDatabaseWorkspace</WorkspaceType>"
                                + "<WorkspaceFactoryProgID></WorkspaceFactoryProgID><ConnectionString></ConnectionString>"
                                + "<ConnectionInfo xsi:nil='true'/><Domains xsi:type='typens:ArrayOfDomain'></Domains>"
                                + "<MajorVersion>3</MajorVersion><MinorVersion>0</MinorVersion>"
                                + "<BugfixVersion>0</BugfixVersion><Realm></Realm></DEWorkspace>",
                        null, null, 0, null, null));
        return new TableSpec("a00000004", 4, false, false, true, f, rows);
    }

    /** {@code a00000005 GDB_ItemTypes}。 */
    private static TableSpec itemTypes() {
        List<FieldSpec> f = list(
                f("ObjectID", 6, false, true, false, 0),
                f("UUID", 10, false, false, true, 0),
                f("ParentTypeID", 10, false, false, true, 0),
                f("Name", 4, false, false, true, 160));
        List<Object[]> rows = list(
                r(1, "{70737809-852C-4A03-9E22-2CECEA5B9BFA}", "{D4912162-3413-476E-9DA4-2AEFBBC16939}", "Feature Class"),
                r(2, "{CD06BC3B-789D-4C51-AAFA-A467912B8965}", "{D4912162-3413-476E-9DA4-2AEFBBC16939}", "Table"),
                r(3, "{F8413DCB-2248-4935-BFE9-315F397E5110}", "{77292603-930F-475D-AE4F-B8970F42F394}", "Mosaic Dataset"),
                r(4, "{77292603-930F-475D-AE4F-B8970F42F394}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Extension Dataset"),
                r(5, "{8637F1ED-8C04-4866-A44A-1CB8288B3C63}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Domain"),
                r(6, "{7771FC7D-A38B-4FD3-8225-639D17E9A131}", "{77292603-930F-475D-AE4F-B8970F42F394}", "Network Dataset"),
                r(7, "{73718A66-AFB9-4B88-A551-CFFA0AE12620}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Geometric Network"),
                r(8, "{767152D3-ED66-4325-8774-420D46674E07}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Topology"),
                r(9, "{74737149-DCB5-4257-8904-B9724E32A530}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Feature Dataset"),
                r(10, "{F3783E6F-65CA-4514-8315-CE3985DAD3B1}", "{8405ADD5-8DF8-4227-8FAC-3FCADE073386}", "Folder"),
                r(11, "{D86502F9-9758-45C6-9D23-6DD1A0107B47}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Sync Dataset"),
                r(12, "{4ED4A58E-621F-4043-95ED-850FBA45FCBC}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Replica"),
                r(13, "{D98421EB-D582-4713-9484-43304D0810F6}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Replica Dataset"),
                r(14, "{DC64B6E4-DC0F-43BD-B4F5-F22385DCF055}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Historical Marker"),
                r(15, "{787BEA35-4A86-494F-BB48-500B96145B58}", "{77292603-930F-475D-AE4F-B8970F42F394}", "Catalog Dataset"),
                r(16, "{35B601F7-45CE-4AFF-ADB7-7702D3839B12}", "{D4912162-3413-476E-9DA4-2AEFBBC16939}", "Raster Catalog"),
                r(17, "{A3803369-5FC2-4963-BAE0-13EFFC09DD73}", "{77292603-930F-475D-AE4F-B8970F42F394}", "Parcel Fabric"),
                r(18, "{E6302665-416B-44FA-BE33-4E15916BA101}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Survey Dataset"),
                r(19, "{FFD09C28-FE70-4E25-907C-AF8E8A5EC5F3}", "{8405ADD5-8DF8-4227-8FAC-3FCADE073386}", "Resource"),
                r(20, "{D4912162-3413-476E-9DA4-2AEFBBC16939}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "AbstractTable"),
                r(21, "{DB1B697A-3BB6-426A-98A2-6EE7A4C6AED3}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Toolbox"),
                r(22, "{D5A40288-029E-4766-8C81-DE3F61129371}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Schematic Dataset"),
                r(23, "{FBDD7DD6-4A25-40B7-9A1A-ECC3D1172447}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Tin"),
                r(24, "{C673FE0F-7280-404F-8532-20755DD8FC06}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Workspace"),
                r(25, "{5B966567-FB87-4DDE-938B-B4B37423539D}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Sync Replica"),
                r(26, "{5ED667A3-9CA9-44A2-8029-D95BF23704B9}", "{D4912162-3413-476E-9DA4-2AEFBBC16939}", "Raster Dataset"),
                r(27, "{DC9EF677-1AA3-45A7-8ACD-303A5202D0DC}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Workspace Extension"),
                r(28, "{A300008D-0CEA-4F6A-9DFA-46AF829A3DF2}", "{77292603-930F-475D-AE4F-B8970F42F394}", "Representation Class"),
                r(29, "{C29DA988-8C3E-45F7-8B5C-18E51EE7BEB4}", "{8637F1ED-8C04-4866-A44A-1CB8288B3C63}", "Range Domain"),
                r(30, "{8C368B12-A12E-4C7E-9638-C9C64E69E98F}", "{8637F1ED-8C04-4866-A44A-1CB8288B3C63}", "Coded Value Domain"),
                r(31, "{76357537-3364-48AF-A4BE-783C7C28B5CB}", "{77292603-930F-475D-AE4F-B8970F42F394}", "Terrain"),
                r(32, "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "{FFD09C28-FE70-4E25-907C-AF8E8A5EC5F3}", "Dataset"),
                r(33, "{8405ADD5-8DF8-4227-8FAC-3FCADE073386}", "{00000000-0000-0000-0000-000000000000}", "Item"),
                r(34, "{B606A7E1-FA5B-439C-849C-6E9C2481537B}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "Relationship Class"));
        return new TableSpec("a00000005", 0, false, false, true, f, rows);
    }

    /** {@code a00000006 GDB_ItemRelationships}。 */
    private static TableSpec itemRelationships() {
        List<FieldSpec> f = list(
                f("ObjectID", 6, false, true, false, 0),
                f("UUID", 11, false, true, false, 0),
                f("OriginID", 10, false, false, true, 0),
                f("DestID", 10, false, false, true, 0),
                f("Type", 10, false, false, true, 0),
                f("Attributes", 12, true, false, true, 0),
                f("Properties", 1, true, false, true, 0));
        List<Object[]> rows = list(
                r(1, "{625A05B1-B045-4B6B-8D58-1DB7D715E1BA}", "{EB539B11-FFE7-4E00-B302-A2A4845042AE}",
                        "{DB050D8B-FACC-4229-8C84-833ACB1BE460}", "{DC78F1AB-34E4-43AC-BA47-1C4EABD0E7C7}", null, 1),
                r(2, "{E707A4CB-6D75-41B4-A9BA-A22213E6EC13}", "{EB539B11-FFE7-4E00-B302-A2A4845042AE}",
                        "{AFC05424-D435-4B5A-B42C-6F57841DE885}", "{DC78F1AB-34E4-43AC-BA47-1C4EABD0E7C7}", null, 1));
        return new TableSpec("a00000006", 0, false, false, true, f, rows);
    }

    /** {@code a00000007 GDB_ItemRelationshipTypes}。 */
    private static TableSpec itemRelationshipTypes() {
        List<FieldSpec> f = list(
                f("ObjectID", 6, false, true, false, 0),
                f("UUID", 10, false, false, true, 0),
                f("OrigItemTypeID", 10, false, false, true, 0),
                f("DestItemTypeID", 10, false, false, true, 0),
                f("Name", 4, false, false, true, 160),
                f("ForwardLabel", 4, true, false, true, 255),
                f("BackwardLabel", 4, true, false, true, 255),
                f("IsContainment", 0, true, false, true, 0));
        List<Object[]> rows = list(
                r(1, "{CC28387C-441F-4D7C-A802-41A160317FE0}", "{5B966567-FB87-4DDE-938B-B4B37423539D}",
                        "{D86502F9-9758-45C6-9D23-6DD1A0107B47}", "SyncDatasetInSyncReplica", "Sync Dataset",
                        "Participates In Sync Replica", 1),
                r(2, "{5F9085E0-788F-4354-AE3C-34C83A7EA784}", "{A3803369-5FC2-4963-BAE0-13EFFC09DD73}",
                        "{CD06BC3B-789D-4C51-AAFA-A467912B8965}", "TableInParcelFabric", "Manages Table",
                        "Participates In Parcel Fabric", 0),
                r(3, "{B32B8563-0B96-4D32-92C4-086423AE9962}", "{7771FC7D-A38B-4FD3-8225-639D17E9A131}",
                        "{70737809-852C-4A03-9E22-2CECEA5B9BFA}", "FeatureClassInNetworkDataset",
                        "Spatially Manages Feature Class", "Participates In Network Dataset", 0),
                r(4, "{D088B110-190B-4229-BDF7-89FDDD14D1EA}", "{767152D3-ED66-4325-8774-420D46674E07}",
                        "{70737809-852C-4A03-9E22-2CECEA5B9BFA}", "FeatureClassInTopology",
                        "Spatially Manages Feature Class", "Participates In Topology", 0),
                r(5, "{DC78F1AB-34E4-43AC-BA47-1C4EABD0E7C7}", "{F3783E6F-65CA-4514-8315-CE3985DAD3B1}",
                        "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "DatasetInFolder", "Contains Dataset",
                        "Contained in Dataset", 1),
                r(6, "{55D2F4DC-CB17-4E32-A8C7-47591E8C71DE}", "{76357537-3364-48AF-A4BE-783C7C28B5CB}",
                        "{70737809-852C-4A03-9E22-2CECEA5B9BFA}", "FeatureClassInTerrain",
                        "Spatially Manages Feature Class", "Participates In Terrain", 0),
                r(7, "{E79B44E3-F833-4B12-90A1-364EC4DDC43E}", "{70737809-852C-4A03-9E22-2CECEA5B9BFA}",
                        "{A300008D-0CEA-4F6A-9DFA-46AF829A3DF2}", "RepresentationOfFeatureClass",
                        "Feature Class Representation", "Represented Feature Class", 0),
                r(8, "{A1633A59-46BA-4448-8706-D8ABE2B2B02E}", "{74737149-DCB5-4257-8904-B9724E32A530}",
                        "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "DatasetInFeatureDataset",
                        "Contains Dataset", "Contained In FeatureDataset", 1),
                r(9, "{5DD0C1AF-CB3D-4FEA-8C51-CB3BA8D77CDB}", "{F3783E6F-65CA-4514-8315-CE3985DAD3B1}",
                        "{8405ADD5-8DF8-4227-8FAC-3FCADE073386}", "ItemInFolder", "Contains Item",
                        "Contained In Folder", 1),
                r(10, "{D022DE33-45BD-424C-88BF-5B1B6B957BD3}", "{D98421EB-D582-4713-9484-43304D0810F6}",
                        "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "DatasetOfReplicaDataset",
                        "Replicated Dataset", "Dataset of Replicated Dataset", 0),
                r(11, "{725BADAB-3452-491B-A795-55F32D67229C}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}",
                        "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "DatasetsRelatedThrough", "Origin Of",
                        "Destination Of", 0),
                r(12, "{79CC71C8-B7D9-4141-9014-B6373E236ABB}", "{D86502F9-9758-45C6-9D23-6DD1A0107B47}",
                        "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}", "DatasetOfSyncDataset", "Replicated Dataset",
                        "Dataset Of Sync Dataset", 0),
                r(13, "{583A5BAA-3551-41AE-8AA8-1185719F3889}", "{A3803369-5FC2-4963-BAE0-13EFFC09DD73}",
                        "{70737809-852C-4A03-9E22-2CECEA5B9BFA}", "FeatureClassInParcelFabric",
                        "Spatially Manages Feature Class", "Participates In Parcel Fabric", 0),
                r(14, "{17E08ADB-2B31-4DCD-8FDD-DF529E88F843}", "{28DA9E89-FF80-4D6D-8926-4EE2B161677D}",
                        "{8637F1ED-8C04-4866-A44A-1CB8288B3C63}", "DomainInDataset", "Contains Domain",
                        "Contained in Dataset", 0),
                r(15, "{908A4670-1111-48C6-8269-134FDD3FE617}", "{7771FC7D-A38B-4FD3-8225-639D17E9A131}",
                        "{CD06BC3B-789D-4C51-AAFA-A467912B8965}", "TableInNetworkDataset", "Manages Table",
                        "Participates In Network Dataset", 0),
                r(16, "{8DB31AF1-DF7C-4632-AA10-3CC44B0C6914}", "{4ED4A58E-621F-4043-95ED-850FBA45FCBC}",
                        "{D98421EB-D582-4713-9484-43304D0810F6}", "ReplicaDatasetInReplica",
                        "Replicated Dataset", "Participates In Replica", 1),
                r(17, "{DC739A70-9B71-41E8-868C-008CF46F16D7}", "{73718A66-AFB9-4B88-A551-CFFA0AE12620}",
                        "{70737809-852C-4A03-9E22-2CECEA5B9BFA}", "FeatureClassInGeometricNetwork",
                        "Spatially Manages Feature Class", "Participates In Geometric Network", 0),
                r(18, "{0D10B3A7-2F64-45E6-B7AC-2FC27BF2133C}", "{F3783E6F-65CA-4514-8315-CE3985DAD3B1}",
                        "{F3783E6F-65CA-4514-8315-CE3985DAD3B1}", "FolderInFolder", "Parent Folder Of",
                        "Child Folder Of", 1));
        return new TableSpec("a00000007", 0, false, false, true, f, rows);
    }

    // ==================================================================
    // 建库
    // ==================================================================

    /**
     * 在 {@code gdbDir} 下把 7 张系统表全部建出来(含行数据)。
     *
     * <p>对应 GDAL {@code CreateGDBSystemCatalog}/{@code CreateGDBDBTune}/.../
     * {@code CreateGDBItemRelationshipTypes} 这一串(见
     * {@code ogropenfilegdbdatasource_write.cpp:548,786,947,...}),步骤为:对每张
     * 样板表调 {@link GdbTable#create} 写字段描述区,再逐行 {@code appendFeature}。
     *
     * <p>文件写完后立即 {@code close()}(即落盘);调用方随后以可写方式重新打开
     * catalog 即可。
     *
     * @param gdbDir {@code .gdb} 目录(须已存在)
     */
    public static void writeSystemTables(Path gdbDir) {
        for (TableSpec spec : SYSTEM_TABLES) {
            List<GdbField> fields = new ArrayList<>();
            for (FieldSpec fs : spec.fields()) {
                fields.add(toField(fs));
            }
            Path tablePath = gdbDir.resolve(spec.physicalName() + ".gdbtable");
            GdbTable table = GdbTable.create(tablePath, fields, spec.tableGeomType(),
                    spec.hasZ(), spec.hasM(), spec.stringsAreUtf8(), null, 5);
            try {
                appendRows(table, spec);
            } finally {
                table.close();
            }
        }
    }

    /** 把样板行逐行追加进表(OBJECTID/几何列跳过;nil 值不写,保持空)。 */
    private static void appendRows(GdbTable table, TableSpec spec) {
        List<FieldSpec> fields = spec.fields();
        for (Object[] row : spec.rows()) {
            GdbFeature feature = new GdbFeature();
            for (int i = 0; i < fields.size(); i++) {
                FieldSpec fs = fields.get(i);
                if (fs.type() == FieldType.OBJECTID || fs.type() == FieldType.GEOMETRY) {
                    // OID 由行号推导;样板行的几何一律为空
                    continue;
                }
                Object value = row[i];
                if (value == null) {
                    continue;
                }
                feature.setAttribute(fs.name(), value);
            }
            table.appendFeature(feature);
        }
    }

    /**
     * 样板字段 -> {@link GdbField}。
     *
     * <p>几何字段的包围盒用 {@link GdbDateTimeUtil#ESRI_NAN} 替换样板里的普通 NaN:
     * 两者只差最低有效位,但 ArcGIS 用的是后者(见
     * {@code filegdbtable.cpp::getESRI_NAN})。
     *
     * @param spec 样板字段
     * @return 字段定义
     */
    public static GdbField toField(FieldSpec spec) {
        if (spec.geom() == null) {
            return new GdbField(spec.name(), spec.type())
                    .setLength(spec.maxWidth())
                    .setNullable(spec.nullable())
                    .setRequired(spec.required())
                    .setEditable(spec.editable());
        }
        GeomSpec g = spec.geom();
        GdbGeomField gf = new GdbGeomField(spec.name());
        gf.setAlias("");
        gf.setNullable(spec.nullable());
        gf.setRequired(spec.required());
        gf.setEditable(spec.editable());
        gf.setWkt(g.wkt());
        gf.setHasMOriginScaleTolerance(g.hasM());
        gf.setHasZOriginScaleTolerance(g.hasZ());
        gf.setXOrigin(g.xOrigin()).setYOrigin(g.yOrigin()).setXyScale(g.xyScale());
        if (g.hasM()) {
            gf.setMOrigin(g.mOrigin()).setMScale(g.mScale());
        }
        if (g.hasZ()) {
            gf.setZOrigin(g.zOrigin()).setZScale(g.zScale());
        }
        gf.setXyTolerance(g.xyTolerance());
        if (g.hasM()) {
            gf.setMTolerance(g.mTolerance());
        }
        if (g.hasZ()) {
            gf.setZTolerance(g.zTolerance());
        }
        gf.setExtent(nanify(g.xmin()), nanify(g.ymin()), nanify(g.xmax()), nanify(g.ymax()));
        gf.setSpatialIndexGridResolutions(g.grids());
        return gf;
    }

    /** 把 Java 的 {@code Double.NaN} 换成 ESRI 的 NaN 位模式。 */
    private static double nanify(double v) {
        return Double.isNaN(v) ? GdbDateTimeUtil.ESRI_NAN : v;
    }

    // ==================================================================
    // 便捷构造
    // ==================================================================

    /** 按 FGFT 类型码构造非几何字段样板。 */
    private static FieldSpec f(String name, int fgftCode, boolean nullable, boolean required,
                               boolean editable, int maxWidth) {
        return new FieldSpec(name, FieldType.fromCode(fgftCode), nullable, required, editable,
                maxWidth, null);
    }

    /** 构造几何字段样板。 */
    private static FieldSpec geom(String name, boolean nullable, boolean required, boolean editable,
                                  GeomSpec gm) {
        return new FieldSpec(name, FieldType.GEOMETRY, nullable, required, editable, 0, gm);
    }

    /** 一行数据。 */
    private static Object[] r(Object... values) {
        return values;
    }
}
