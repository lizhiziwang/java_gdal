package com.gdal.openfilegdb.dataset;

import com.gdal.openfilegdb.exception.GdbFileNotFoundException;
import com.gdal.openfilegdb.exception.GdbFormatException;
import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.table.GdbTable;
import com.gdal.openfilegdb.util.GdbNumbers;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import static com.gdal.openfilegdb.util.GdbCollections.copyOfSet;
import static com.gdal.openfilegdb.util.GdbCollections.entry;
import static com.gdal.openfilegdb.util.GdbCollections.ofEntries;

/**
 * {@code GDB_SystemCatalog}(物理名通常是 {@code a00000001})的读写包装,外加系统目录
 * 相关的静态工具。
 *
 * <p>逐段对照 Python 参考实现 {@code pyopenfilegdb/_system_catalog.py}。职责:
 * <ul>
 *   <li>{@link #findSystemTables(Path)} —— 扫描目录并按 catalog 的 {@code Name}
 *       反查"物理名 → 逻辑表名"(<b>不</b>硬编码编号),对应 GDAL
 *       {@code GDALOpenFileGDBDataSource::Open} 里遍历 catalog 记录、
 *       {@code m_osMapNameToIdx[Name] = i + 1} 那段
 *       (ogropenfilegdbdatasource.cpp:314)。</li>
 *   <li>{@link #physicalNameForIndex(int)} —— 逻辑序号 → 物理名,对应
 *       {@code CPLSPrintf("a%08x", idx)}。</li>
 *   <li>catalog 行读取与追加({@link #entries()} / {@link #appendEntry})。</li>
 *   <li>item 类型 / 关系类型 UUID 常量、{@link #generateUuid()}、
 *       {@link #classify} —— 出处 {@code ogr_openfilegdb.h:48-73}。</li>
 *   <li>{@link #makeFeatureClassXml} / {@link #makeTableXml} /
 *       {@link #makeWorkspaceXml} —— 生成的 {@code Definition} XML,对应
 *       {@code OGROpenFileGDBLayer::RefreshXMLDefinitionInMemory}
 *       (ogropenfilegdblayer_write.cpp:2616)。</li>
 * </ul>
 *
 * <p>⚠️ 定位系统表<b>永远</b>先读 catalog 的 {@code Name} 反查,编号只是"通常如此":
 * GDAL 注释里明说 GDB_Items "has been seen in some datasets to not be a00000004"。
 */
public final class GdbSystemCatalog implements AutoCloseable {

    /** {@code GDB_SystemCatalog} 固定写死在 a00000001(GDAL 从不按名字找它)。 */
    public static final String SYSTEM_CATALOG_PHYSICAL = "a00000001";

    /** {@code GDB_Items} 的逻辑表名。 */
    public static final String GDB_ITEMS_LOGICAL_NAME = "GDB_Items";

    /** 物理名正则:{@code a} + 8 位十六进制。 */
    private static final String PHYSICAL_NAME_PATTERN = "^[aA][0-9a-fA-F]{8}$";

    /**
     * 规范布局下"物理名 → 逻辑表名"的映射。
     *
     * <p>只是<b>默认猜测</b>,永远以 {@link #findSystemTables(Path)} 的扫描结果为准。
     * 顺序即 GDAL {@code Create()} 的写入顺序(ogropenfilegdbdatasource_write.cpp:579)。
     */
    public static final Map<String, String> SYSTEM_TABLE_NAMES = ofEntries(
            entry("a00000001", "GDB_SystemCatalog"),
            entry("a00000002", "GDB_DBTune"),
            entry("a00000003", "GDB_SpatialRefs"),
            entry("a00000004", "GDB_Items"),
            entry("a00000005", "GDB_ItemTypes"),
            entry("a00000006", "GDB_ItemRelationships"),
            entry("a00000007", "GDB_ItemRelationshipTypes"),
            entry("a00000008", "GDB_ReplicaLog"));

    /** 系统表逻辑名集合。 */
    public static final Set<String> SYSTEM_TABLE_LOGICAL_NAMES =
            copyOfSet(SYSTEM_TABLE_NAMES.values());

    // ------------------------------------------------------------------
    // item 类型 UUID(ogr_openfilegdb.h:48-63)
    //
    // ⚠️ 大小写:真实 ArcGIS 文件里 GUID 以大写存储,GuidUtil.format 也输出大写;
    //    所有比较一律走 uuidEquals 做大小写无关匹配。
    // ------------------------------------------------------------------

    /** item 类型 GUID(带花括号,大写)。 */
    public static final Map<String, String> ITEM_TYPE_UUIDS = ofEntries(
            entry("Folder", "{F3783E6F-65CA-4514-8315-CE3985DAD3B1}"),
            entry("Workspace", "{C673FE0F-7280-404F-8532-20755DD8FC06}"),
            entry("Feature Dataset", "{74737149-DCB5-4257-8904-B9724E32A530}"),
            entry("Feature Class", "{70737809-852C-4A03-9E22-2CECEA5B9BFA}"),
            entry("Table", "{CD06BC3B-789D-4C51-AAFA-A467912B8965}"),
            entry("Range Domain", "{C29DA988-8C3E-45F7-8B5C-18E51EE7BEB4}"),
            entry("Coded Value Domain", "{8C368B12-A12E-4C7E-9638-C9C64E69E98F}"),
            entry("Relationship Class", "{B606A7E1-FA5B-439C-849C-6E9C2481537B}"));

    /** item 关系类型 GUID(写 {@code GDB_ItemRelationships} 用,ogr_openfilegdb.h:66-73)。 */
    public static final Map<String, String> RELATIONSHIP_UUIDS = ofEntries(
            entry("DatasetInFeatureDataset", "{A1633A59-46BA-4448-8706-D8ABE2B2B02E}"),
            entry("DatasetInFolder", "{DC78F1AB-34E4-43AC-BA47-1C4EABD0E7C7}"),
            entry("DomainInDataset", "{17E08ADB-2B31-4DCD-8FDD-DF529E88F843}"),
            entry("DatasetsRelatedThrough", "{725BADAB-3452-491B-A795-55F32D67229C}"));

    /** GUID(大写,带花括号)→ 人类可读类型名,供 {@link #classify} 用。 */
    private static final Map<String, String> UUID_TO_KIND = buildUuidToKind();

    /** Definition XML 根标签名 → 类型名。 */
    private static final Map<String, String> ROOT_TAG_TO_KIND = ofEntries(
            entry("DEFeatureClassInfo", "Feature Class"),
            entry("DETableInfo", "Table"),
            entry("DEFeatureDataset", "Feature Dataset"),
            entry("DEWorkspace", "Workspace"),
            entry("DERasterDataset", "Raster Dataset"),
            entry("DERasterCatalog", "Raster Catalog"),
            entry("DERelationshipClass", "Relationship Class"),
            entry("GPCodedValueDomain2", "Coded Value Domain"),
            entry("GPCodedValueDomain", "Coded Value Domain"),
            entry("GPRangeDomain2", "Range Domain"),
            entry("GPRangeDomain", "Range Domain"));

    /** 线程安全的 UUID 随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    // ------------------------------------------------------------------
    // 实例状态
    // ------------------------------------------------------------------

    private final Path gdbDir;
    private final Path catalogPath;
    private final GdbTable table;
    private final boolean writable;

    private GdbSystemCatalog(Path gdbDir, Path catalogPath, GdbTable table, boolean writable) {
        this.gdbDir = gdbDir;
        this.catalogPath = catalogPath;
        this.table = table;
        this.writable = writable;
    }

    // ==================================================================
    // 打开
    // ==================================================================

    /**
     * 只读打开一个 {@code .gdb} 的 catalog。
     *
     * @param gdbDir {@code .gdb} 目录
     * @return catalog 包装
     */
    public static GdbSystemCatalog open(Path gdbDir) {
        return open(gdbDir, false);
    }

    /**
     * 打开一个 {@code .gdb} 的 catalog。
     *
     * <p>对应 {@code ogropenfilegdbdatasource.cpp::Open} 里打开 {@code a00000001.gdbtable}
     * 并校验 {@code Name}/{@code FileFormat} 两列那段。
     *
     * @param gdbDir   {@code .gdb} 目录
     * @param writable 是否以可写方式打开(追加 catalog 行用)
     * @return catalog 包装
     * @throws GdbFileNotFoundException 目录或 {@code a00000001.gdbtable} 不存在
     * @throws GdbFormatException       catalog 结构不是 {@code Name:STRING + FileFormat:INT*}
     */
    public static GdbSystemCatalog open(Path gdbDir, boolean writable) {
        Path dir = gdbDir.toAbsolutePath().normalize();
        Path path = dir.resolve(SYSTEM_CATALOG_PHYSICAL + ".gdbtable");
        if (!Files.isRegularFile(path)) {
            throw new GdbFileNotFoundException(
                    dir + ": 找不到系统目录表 " + SYSTEM_CATALOG_PHYSICAL + ".gdbtable");
        }
        GdbTable t = writable ? GdbTable.openForWrite(path) : GdbTable.open(path);

        GdbField name = t.getFieldByName("Name");
        GdbField format = t.getFieldByName("FileFormat");
        if (name == null || format == null
                || name.getType() != FieldType.STRING
                || (format.getType() != FieldType.INT16 && format.getType() != FieldType.INT32)) {
            t.close();
            throw new GdbFormatException(path + ": GDB_SystemCatalog 结构不正确"
                    + "(需要 Name:STRING 与 FileFormat:INT16/INT32)");
        }
        return new GdbSystemCatalog(dir, path, t, writable);
    }

    // ==================================================================
    // 基本信息
    // ==================================================================

    /** {@code .gdb} 目录。 */
    public Path getGdbDir() {
        return gdbDir;
    }

    /** catalog 的 {@code .gdbtable} 路径。 */
    public Path getPath() {
        return catalogPath;
    }

    /** 底层表(调用方一般不需要;数据集会把它放进统一的表缓存)。 */
    public GdbTable getTable() {
        return table;
    }

    // ==================================================================
    // 行读取
    // ==================================================================

    /**
     * 一行 catalog 记录。
     *
     * @param physicalNumber 物理编号(1 基)= 该行的 0 基行号 + 1;物理名即
     *                       {@code String.format("a%08x", physicalNumber)}
     * @param name           逻辑表名
     * @param fileFormat     FileFormat(核心系统表 0,GDB_ReplicaLog 2)
     */
    public static final class Entry {
        private final int physicalNumber;
        private final String name;
        private final int fileFormat;

        public Entry(int physicalNumber, String name, int fileFormat) {
            this.physicalNumber = physicalNumber;
            this.name = name;
            this.fileFormat = fileFormat;
        }

        public int physicalNumber() {
            return physicalNumber;
        }

        public String name() {
            return name;
        }

        public int fileFormat() {
            return fileFormat;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry other = (Entry) o;
            return physicalNumber == other.physicalNumber
                    && fileFormat == other.fileFormat
                    && (name == null ? other.name == null : name.equals(other.name));
        }

        @Override
        public int hashCode() {
            int h = physicalNumber;
            h = 31 * h + fileFormat;
            h = 31 * h + (name == null ? 0 : name.hashCode());
            return h;
        }

        @Override
        public String toString() {
            return "Entry[physicalNumber=" + physicalNumber + ", name=" + name
                    + ", fileFormat=" + fileFormat + "]";
        }
    }

    /**
     * 读出全部 catalog 行,按记录顺序(每次调用都重新读,不做缓存 —— 追加后立即可见)。
     *
     * @return 行列表;Name 缺失的行被跳过
     */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        int total = table.getRecordCount();
        for (int row = 0; row < total; row++) {
            GdbFeature f = table.readFeature(row);
            if (f == null) {
                continue;
            }
            Object name = f.getAttribute("Name");
            if (name == null) {
                continue;
            }
            Object fmt = f.getAttribute("FileFormat");
            int fileFormat = fmt instanceof Number ? ((Number) fmt).intValue() : 0;
            out.add(new Entry(row + 1, String.valueOf(name), fileFormat));
        }
        return out;
    }

    /**
     * 逻辑表名 → 物理名。
     *
     * <p>先精确匹配,再退化为大小写无关;catalog 里没有时,回落到规范布局
     * {@link #SYSTEM_TABLE_NAMES}。
     *
     * @param logical 逻辑表名
     * @return 物理名;找不到返回 {@code null}
     */
    public String physicalFor(String logical) {
        if (logical == null) {
            return null;
        }
        for (Entry e : entries()) {
            if (e.name().equals(logical)) {
                return physicalNameForIndex(e.physicalNumber());
            }
        }
        for (Entry e : entries()) {
            if (e.name().equalsIgnoreCase(logical)) {
                return physicalNameForIndex(e.physicalNumber());
            }
        }
        for (Map.Entry<String, String> e : SYSTEM_TABLE_NAMES.entrySet()) {
            if (e.getValue().equals(logical)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 物理名 → 逻辑表名。
     *
     * @param physical 物理名(如 {@code a00000009})
     * @return 逻辑表名;找不到返回 {@code null}
     */
    public String logicalFor(String physical) {
        if (physical == null) {
            return null;
        }
        for (Entry e : entries()) {
            if (physicalNameForIndex(e.physicalNumber()).equalsIgnoreCase(physical)) {
                return e.name();
            }
        }
        return SYSTEM_TABLE_NAMES.get(physical.toLowerCase(Locale.ROOT));
    }

    // ==================================================================
    // 行追加
    // ==================================================================

    /**
     * 下一个可用物理编号 = {@code 1 + catalog 的槽位数}。
     *
     * <p>对应 GDAL {@code ICreateLayer} 里的
     * {@code nTableNum = 1 + oTable.GetTotalRecordCount()}(拿的是<b>槽位数</b>,含空洞)。
     * 空库有 8 条 → 第一个用户图层是 {@code a00000009}。
     *
     * @return 物理编号(1 基)
     */
    public int nextPhysicalNumber() {
        return 1 + table.getRecordCount();
    }

    /**
     * 往 catalog 追加一行 {@code (Name, FileFormat)}。
     *
     * <p>对应 {@code RegisterLayerInSystemCatalog}
     * (ogropenfilegdbdatasource_write.cpp:144)。
     *
     * @param name       逻辑表名
     * @param fileFormat FileFormat(通常 0)
     */
    public void appendEntry(String name, int fileFormat) {
        if (!writable) {
            throw new GdbWriteException(catalogPath.getFileName() + ": catalog 不是以可写方式打开的");
        }
        GdbFeature f = new GdbFeature();
        f.setAttribute("Name", name);
        f.setAttribute("FileFormat", fileFormat);
        table.appendFeature(f);
    }

    /** 落盘(转发到 {@link GdbTable#sync()})。 */
    public void sync() {
        table.sync();
    }

    /** 落盘并关闭句柄。 */
    @Override
    public void close() {
        table.close();
    }

    // ==================================================================
    // 系统表定位(静态)
    // ==================================================================

    /**
     * 逻辑序号 → 物理名,{@code 9 -> "a00000009"}。
     *
     * <p>对应 GDAL {@code CPLSPrintf("a%08x", idx)}(ogropenfilegdbdatasource.cpp:1084)。
     *
     * @param index 1 基逻辑序号(= catalog 行号 + 1)
     * @return 物理名
     */
    public static String physicalNameForIndex(int index) {
        return String.format("a%08x", index);
    }

    /**
     * 扫描 {@code .gdb} 目录里的 {@code *.gdbtable},返回"物理名 → 逻辑表名"。
     *
     * <p>与 GDAL {@code Open} 一致:先读 catalog,按行号推出每个表的物理文件名;
     * catalog 里没有的条目再用规范布局 {@link #SYSTEM_TABLE_NAMES} 猜,最后用物理名本身
     * 兜底。
     *
     * @param gdbDir {@code .gdb} 目录
     * @return 映射;目录不存在或没有 {@code .gdbtable} 时返回空 map
     */
    public static Map<String, String> findSystemTables(Path gdbDir) {
        Path dir = gdbDir.toAbsolutePath().normalize();
        Map<Integer, String> namesByIndex = catalogIndexToName(dir);
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".gdbtable"))
                    .forEach(files::add);
        } catch (IOException e) {
            return out;
        }
        files.sort((a, b) -> a.getFileName().toString().toLowerCase(Locale.ROOT)
                .compareTo(b.getFileName().toString().toLowerCase(Locale.ROOT)));
        for (Path p : files) {
            String physical = stripExtension(p.getFileName().toString());
            String logical = null;
            Integer index = physicalNumber(physical);
            if (index != null) {
                logical = namesByIndex.get(index);
            }
            if (logical == null) {
                logical = SYSTEM_TABLE_NAMES.get(physical.toLowerCase(Locale.ROOT));
            }
            if (logical == null) {
                logical = physical;
            }
            out.put(physical, logical);
        }
        return out;
    }

    /** 读 {@code a00000001.gdbtable},返回 "1 基序号 → 逻辑表名";损坏时返回空 map。 */
    private static Map<Integer, String> catalogIndexToName(Path dir) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Path path = dir.resolve(SYSTEM_CATALOG_PHYSICAL + ".gdbtable");
        if (!Files.isRegularFile(path)) {
            return out;
        }
        GdbTable t = null;
        try {
            t = GdbTable.open(path);
            int total = t.getRecordCount();
            for (int row = 0; row < total; row++) {
                GdbFeature f = t.readFeature(row);
                if (f == null) {
                    continue;
                }
                Object name = f.getAttribute("Name");
                if (name != null) {
                    out.put(row + 1, String.valueOf(name));
                }
            }
        } catch (RuntimeException e) {
            // 单个 catalog 损坏不应让整库打不开:返回空映射,调用方用规范布局兜底
            return new LinkedHashMap<>();
        } finally {
            if (t != null) {
                t.close();
            }
        }
        return out;
    }

    /** {@code 'a00000009'} → 9;不是合法物理名返回 {@code null}。 */
    public static Integer physicalNumber(String physical) {
        if (physical == null || !physical.matches(PHYSICAL_NAME_PATTERN)) {
            return null;
        }
        return Integer.parseInt(physical.substring(1), 16);
    }

    /** 是否是系统表逻辑名。 */
    public static boolean isSystemTableName(String name) {
        return name != null && SYSTEM_TABLE_LOGICAL_NAMES.contains(name);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ==================================================================
    // UUID
    // ==================================================================

    /**
     * 返回内置的 item 类型 GUID(大写带花括号)。未知名字返回空串。
     *
     * @param kind 类型名,如 {@code "Feature Class"}
     * @return GUID 或空串
     */
    public static String itemTypeUuid(String kind) {
        if (kind == null) {
            return "";
        }
        return ITEM_TYPE_UUIDS.getOrDefault(kind, "");
    }

    /**
     * 返回内置的 item 关系类型 GUID(大写带花括号)。未知名字返回空串。
     *
     * @param kind 关系类型名,如 {@code "DatasetInFolder"}
     * @return GUID 或空串
     */
    public static String relationshipUuid(String kind) {
        if (kind == null) {
            return "";
        }
        return RELATIONSHIP_UUIDS.getOrDefault(kind, "");
    }

    /**
     * 生成一个 v4 UUID,写成<b>大写带花括号</b>的形式。
     *
     * <p>对应 {@code ogropenfilegdb_generate_uuid.cpp::OFGDBGenerateUUID}:第 13 个半字节
     * 固定 {@code 4}(version=4)、第 17 个半字节取 {@code 8..b}(variant=10xx)。
     * 真实 ArcGIS 文件里 GUID 以大写存储,故这里输出大写;
     * {@link com.gdal.openfilegdb.util.GuidUtil#parse} 大小写都能吃。
     *
     * @return 形如 {@code {XXXXXXXX-XXXX-4XXX-8XXX-XXXXXXXXXXXX}} 的字符串
     */
    public static String generateUuid() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        // UUID 文本分组:8-4-4-4-12 个十六进制字符
        int[] groups = {8, 4, 4, 4, 12};
        // 转成 32 个十六进制字符
        char[] hex = new char[32];
        for (int i = 0; i < 16; i++) {
            hex[i * 2] = HEX[(b[i] >> 4) & 0x0F];
            hex[i * 2 + 1] = HEX[b[i] & 0x0F];
        }
        // version = 4(第 13 个半字节,即字符下标 12)
        hex[12] = '4';
        // variant = 8..b(第 17 个半字节,即字符下标 16)
        hex[16] = "89AB".charAt(RANDOM.nextInt(4));
        StringBuilder sb = new StringBuilder(38);
        sb.append('{');
        int pos = 0;
        for (int gi = 0; gi < groups.length; gi++) {
            if (gi > 0) {
                sb.append('-');
            }
            int chars = groups[gi];
            for (int k = 0; k < chars; k++) {
                sb.append(hex[pos++]);
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** GUID 大小写无关比较;任一为空返回 false。 */
    public static boolean uuidEquals(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static Map<String, String> buildUuidToKind() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ITEM_TYPE_UUIDS.entrySet()) {
            m.put(e.getValue().toUpperCase(Locale.ROOT), e.getKey());
        }
        return m;
    }

    // ==================================================================
    // item 分类(读路径)
    // ==================================================================

    /**
     * 判定一个 {@code GDB_Items} 行的 item 类型。
     *
     * <p>优先用 {@code Type} GUID 匹配;GUID 不认识时退回 GDAL 真正采用的办法 ——
     * 嗅探 {@code Definition} 里的根元素名(ogropenfilegdbdatasource.cpp:794 用
     * {@code strstr} 找 {@code DEFeatureClassInfo} 的做法)。
     *
     * @param typeGuid   {@code GDB_Items.Type}(GUID 字符串,可为 null)
     * @param definition {@code GDB_Items.Definition}(XML 字符串,可为 null)
     * @return 类型名(如 {@code "Feature Class"});无法判定返回空串
     */
    public static String classify(String typeGuid, String definition) {
        if (typeGuid != null && !typeGuid.trim().isEmpty()) {
            String known = UUID_TO_KIND.get(typeGuid.trim().toUpperCase(Locale.ROOT));
            if (known != null) {
                return known;
            }
        }
        String root = definitionRootTag(definition);
        if (!root.isEmpty()) {
            String kind = ROOT_TAG_TO_KIND.get(root);
            if (kind != null) {
                return kind;
            }
        }
        // 兜底:直接子串嗅探(与 Python _classify 一致;可容忍"带前缀但未声明"的畸形 XML)
        String def = definition == null ? "" : definition;
        if (def.contains("DEFeatureClassInfo")) {
            return "Feature Class";
        }
        if (def.contains("DEFeatureDataset")) {
            return "Feature Dataset";
        }
        if (def.contains("DETableInfo")) {
            return "Table";
        }
        if (def.contains("DERasterDataset")) {
            return "Raster Dataset";
        }
        if (def.contains("DERasterCatalog")) {
            return "Raster Catalog";
        }
        if (def.contains("DEWorkspace")) {
            return "Workspace";
        }
        if (def.contains("GPCodedValueDomain")) {
            return "Coded Value Domain";
        }
        if (def.contains("GPRangeDomain")) {
            return "Range Domain";
        }
        if (def.contains("DERelationshipClass")) {
            return "Relationship Class";
        }
        return "";
    }

    /**
     * 取 Definition XML 的根元素本地名(剥掉命名空间前缀)。
     *
     * <p>对应 {@code CPLStripXMLNamespace(psTree, nullptr, TRUE)}
     * (ogropenfilegdblayer.cpp:143)。用<b>非命名空间感知</b>的 DOM 解析:这样
     * "带前缀但漏声明 xmlns" 的畸形 XML 也能解析成功(前缀不会被解析器当成未绑定)。
     * 解析失败返回空串,调用方按"该 item 定义不可解析"处理,不中断整个数据集。
     *
     * @param xml Definition XML 文本
     * @return 本地根标签名;不可解析返回空串
     */
    public static String definitionRootTag(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return "";
        }
        String text = xml.trim();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            trySetFeature(factory,
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(factory,
                    "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document doc = builder.parse(new InputSource(new StringReader(text)));
            Element root = doc.getDocumentElement();
            if (root == null) {
                return "";
            }
            String tag = root.getTagName();
            int colon = tag.indexOf(':');
            return colon >= 0 ? tag.substring(colon + 1) : tag;
        } catch (Exception e) {
            // 不可解析:返回空串,调用方按"该 item 定义不可解析"处理(substring 兜底)
            return "";
        }
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
            // 部分实现不支持这些 feature,忽略
        }
    }

    // ==================================================================
    // Definition XML 生成(写路径)
    //
    // 结构逐项对应 OGROpenFileGDBLayer::RefreshXMLDefinitionInMemory
    // (ogropenfilegdblayer_write.cpp:2616)与 XMLSerializeGeomFieldBase(:159)。
    // ⚠️ 与真实 ArcGIS 的一处有意差异:同时写 <Fields>/<Field>(任务书约定)
    //    与 <GPFieldInfoExs>/<GPFieldInfoEx>(ArcGIS/GDAL 真正使用的结构)。
    // ==================================================================

    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final String NS_TYPENS = "http://www.esri.com/schemas/ArcGIS/10.3";
    private static final String NS_TYPENS_PRO = "http://www.esri.com/schemas/ArcGIS/10.8";
    private static final String NS_XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String NS_XS = "http://www.w3.org/2001/XMLSchema";

    /** CLSID 常量(RefreshXMLDefinitionInMemory:2731)。 */
    private static final String CLSID_FEATURE_CLASS = "{52353152-891A-11D0-BEC6-00805F7C4268}";
    private static final String CLSID_TABLE = "{7A566981-C114-11D2-8A28-006097AFF44E}";

    /** XML 里 SpatialReference 用的默认量化参数(与字段描述区保持一致)。 */
    private static final double DF_X_ORIGIN = -180.0;
    private static final double DF_Y_ORIGIN = -90.0;
    private static final double DF_XY_SCALE = 1000000.0;
    private static final double DF_Z_ORIGIN = -100000.0;
    private static final double DF_Z_SCALE = 10000.0;
    private static final double DF_M_ORIGIN = -100000.0;
    private static final double DF_M_SCALE = 10000.0;
    private static final double DF_XY_TOLERANCE = 2e-6;
    private static final double DF_Z_TOLERANCE = 0.001;
    private static final double DF_M_TOLERANCE = 0.001;

    /**
     * 生成要素类的 {@code DEFeatureClassInfo} XML。
     *
     * @param name          图层名
     * @param fields        完整字段清单(应含 OBJECTID 与几何字段)
     * @param wkt           空间参考 WKT(可为空)
     * @param wkid          WKID(0 = 不写)
     * @param latestWkid    LatestWKID(0 = 不写)
     * @param hasZ          表级 HasZ
     * @param hasM          表级 HasM
     * @param shapeTypeEsri ESRI 几何类型串,如 {@code esriGeometryPolygon}
     * @param oidField      OID 字段名
     * @param shapeField    几何字段名
     * @param catalogPath   {@code <CatalogPath>},通常 {@code \图层名}
     * @param aliasName     {@code <AliasName>}(空则不写)
     * @param dsid          {@code <DSID>}(0 = 不写)
     * @return XML 文本
     */
    public static String makeFeatureClassXml(String name, List<GdbField> fields,
                                             String wkt, int wkid, int latestWkid,
                                             boolean hasZ, boolean hasM, String shapeTypeEsri,
                                             String oidField, String shapeField,
                                             String catalogPath, String aliasName, int dsid) {
        boolean pro = hasProFieldTypes(fields);
        List<GdbField> entries = orderFields(fields, oidField, shapeField, true);
        StringBuilder sb = new StringBuilder(2048);
        sb.append(XML_DECL);
        openRoot(sb, "DEFeatureClassInfo", pro);

        el(sb, "CatalogPath", catalogPath);
        el(sb, "Name", name);
        el(sb, "ChildrenExpanded", "false");
        el(sb, "DatasetType", "esriDTFeatureClass");
        if (dsid > 0) {
            el(sb, "DSID", dsid);
        }
        el(sb, "Versioned", "false");
        el(sb, "CanVersion", "false");
        if (pro) {
            el(sb, "RequiredGeodatabaseClientVersion", "13.2");
        }
        el(sb, "HasOID", "true");
        el(sb, "OIDFieldName", entries.get(0).getName());
        appendFieldsBlock(sb, entries);
        appendGpFieldInfoExs(sb, entries);
        el(sb, "CLSID", CLSID_FEATURE_CLASS);
        el(sb, "EXTCLSID", "");
        if (aliasName != null && !aliasName.isEmpty()) {
            el(sb, "AliasName", aliasName);
        }
        el(sb, "ModelName", "");
        el(sb, "HasGlobalID", "false");
        el(sb, "IsTimeInUTC", "true");
        el(sb, "FeatureType", "esriFTSimple");
        el(sb, "ShapeType", shapeTypeEsri);
        el(sb, "ShapeFieldName", entries.get(1).getName());
        el(sb, "HasM", hasM ? "true" : "false");
        el(sb, "HasZ", hasZ ? "true" : "false");
        el(sb, "HasSpatialIndex", "false");
        el(sb, "AreaFieldName", "");
        el(sb, "LengthFieldName", "");
        sb.append("<Extent xsi:nil=\"true\"/>");
        appendSpatialReference(sb, wkt, wkid, latestWkid);
        el(sb, "ChangeTracked", "false");
        sb.append("</typens:DEFeatureClassInfo>");
        return sb.toString();
    }

    /**
     * 生成非空间表的 {@code DETableInfo} XML。
     *
     * <p>与要素类的区别(见 {@code RefreshXMLDefinitionInMemory}):根元素
     * {@code typens:DETableInfo}、{@code DatasetType} 为 {@code esriDTTable}、
     * CLSID 取表的值,并且完全不含 {@code FeatureType}/{@code ShapeType}/
     * {@code ShapeFieldName}/{@code Extent}/{@code SpatialReference}。
     *
     * @param name        表名
     * @param fields      完整字段清单(应含 OBJECTID)
     * @param oidField    OID 字段名
     * @param catalogPath {@code <CatalogPath>}
     * @param aliasName   {@code <AliasName>}(空则不写)
     * @param dsid        {@code <DSID>}(0 = 不写)
     * @return XML 文本
     */
    public static String makeTableXml(String name, List<GdbField> fields, String oidField,
                                      String catalogPath, String aliasName, int dsid) {
        boolean pro = hasProFieldTypes(fields);
        List<GdbField> entries = orderFields(fields, oidField, "", false);
        StringBuilder sb = new StringBuilder(1024);
        sb.append(XML_DECL);
        openRoot(sb, "DETableInfo", pro);

        el(sb, "CatalogPath", catalogPath);
        el(sb, "Name", name);
        el(sb, "ChildrenExpanded", "false");
        el(sb, "DatasetType", "esriDTTable");
        if (dsid > 0) {
            el(sb, "DSID", dsid);
        }
        el(sb, "Versioned", "false");
        el(sb, "CanVersion", "false");
        if (pro) {
            el(sb, "RequiredGeodatabaseClientVersion", "13.2");
        }
        el(sb, "HasOID", "true");
        el(sb, "OIDFieldName", entries.get(0).getName());
        appendFieldsBlock(sb, entries);
        appendGpFieldInfoExs(sb, entries);
        el(sb, "CLSID", CLSID_TABLE);
        el(sb, "EXTCLSID", "");
        if (aliasName != null && !aliasName.isEmpty()) {
            el(sb, "AliasName", aliasName);
        }
        el(sb, "ModelName", "");
        el(sb, "IsTimeInUTC", "true");
        sb.append("</typens:DETableInfo>");
        return sb.toString();
    }

    /**
     * 生成工作空间行的 {@code DEWorkspace} XML。
     *
     * <p>对应 {@code CreateGDBItems} 里写的常量串
     * (ogropenfilegdbdatasource_write.cpp:920-937)。注意真实空库样板
     * ({@link GdbTemplates})里已经带了这一行,本方法主要供调用方重建 / 校验时使用。
     *
     * @param path {@code <CatalogPath>},默认 {@code \}
     * @return XML 文本
     */
    public static String makeWorkspaceXml(String path) {
        String catalogPath = path == null ? "\\" : path;
        StringBuilder sb = new StringBuilder(1024);
        sb.append(XML_DECL);
        sb.append("<DEWorkspace xsi:type=\"typens:DEWorkspace\"")
                .append(" xmlns:xsi=\"").append(NS_XSI).append('"')
                .append(" xmlns:xs=\"").append(NS_XS).append('"')
                .append(" xmlns:typens=\"").append(NS_TYPENS_PRO).append("\">");
        el(sb, "CatalogPath", catalogPath);
        el(sb, "Name", null);
        el(sb, "ChildrenExpanded", "false");
        el(sb, "WorkspaceType", "esriLocalDatabaseWorkspace");
        el(sb, "WorkspaceFactoryProgID", null);
        el(sb, "ConnectionString", null);
        sb.append("<ConnectionInfo xsi:nil=\"true\"/>");
        sb.append("<Domains xsi:type=\"typens:ArrayOfDomain\"/>");
        el(sb, "MajorVersion", "3");
        el(sb, "MinorVersion", "0");
        el(sb, "BugfixVersion", "0");
        el(sb, "Realm", null);
        sb.append("</DEWorkspace>");
        return sb.toString();
    }

    /** 写根元素 + 命名空间声明。 */
    private static void openRoot(StringBuilder sb, String tag, boolean pro) {
        sb.append("<typens:").append(tag)
                .append(" xmlns:typens=\"").append(pro ? NS_TYPENS_PRO : NS_TYPENS).append('"')
                .append(" xmlns:xsi=\"").append(NS_XSI).append('"')
                .append(" xmlns:xs=\"").append(NS_XS).append('"')
                .append(" xsi:type=\"typens:").append(tag).append("\">");
    }

    /**
     * 把字段清单规整成 {@code [OBJECTID, 几何?, 属性...]}。
     *
     * <p>顺序与 {@code GdbTable.create} 落盘顺序一致(OBJECTID 最前、几何第二),
     * 对应任务事实:Definition 的字段清单必须与实际写入的字段一致。
     */
    private static List<GdbField> orderFields(List<GdbField> fields, String oidField,
                                              String shapeField, boolean withGeom) {
        GdbField oid = null;
        GdbField geom = null;
        List<GdbField> attrs = new ArrayList<>();
        for (GdbField f : fields) {
            if (f.isObjectId()) {
                if (oid == null) {
                    oid = f;
                }
            } else if (f.isGeometry()) {
                if (geom == null) {
                    geom = f;
                }
            } else {
                attrs.add(f);
            }
        }
        if (oid == null) {
            oid = new GdbField(oidField, FieldType.OBJECTID)
                    .setNullable(false).setRequired(true).setEditable(false);
        }
        List<GdbField> out = new ArrayList<>();
        out.add(oid);
        if (withGeom) {
            if (geom == null) {
                geom = new com.gdal.openfilegdb.feature.GdbGeomField(shapeField);
            }
            out.add(geom);
        }
        out.addAll(attrs);
        return out;
    }

    /** 是否含 ArcGIS Pro 3.2+ 才有的字段类型(决定命名空间用 10.8)。 */
    private static boolean hasProFieldTypes(List<GdbField> fields) {
        for (GdbField f : fields) {
            FieldType t = f.getType();
            if (t == FieldType.INT64 || t == FieldType.DATE || t == FieldType.TIME
                    || t == FieldType.DATETIME_WITH_OFFSET) {
                return true;
            }
        }
        return false;
    }

    /** 追加 {@code <Fields><Field>...</Field></Fields>}。 */
    private static void appendFieldsBlock(StringBuilder sb, List<GdbField> entries) {
        sb.append("<Fields>");
        for (GdbField f : entries) {
            sb.append("<Field>");
            el(sb, "Name", f.getName());
            el(sb, "Type", esriFieldType(f.getType()));
            el(sb, "Length", fieldLength(f));
            el(sb, "IsNullable", f.isNullable() ? "true" : "false");
            el(sb, "AliasName", f.getAlias() == null || f.getAlias().isEmpty()
                    ? f.getName() : f.getAlias());
            if (f.getDefaultValue() != null) {
                el(sb, "DefaultValue", f.getDefaultValue());
            }
            sb.append("</Field>");
        }
        sb.append("</Fields>");
    }

    /** 追加 {@code <GPFieldInfoExs><GPFieldInfoEx>...</GPFieldInfoEx></GPFieldInfoExs>}。 */
    private static void appendGpFieldInfoExs(StringBuilder sb, List<GdbField> entries) {
        sb.append("<GPFieldInfoExs xsi:type=\"typens:ArrayOfGPFieldInfoEx\">");
        for (GdbField f : entries) {
            sb.append("<GPFieldInfoEx xsi:type=\"typens:GPFieldInfoEx\">");
            el(sb, "Name", f.getName());
            if (f.getAlias() != null && !f.getAlias().isEmpty()) {
                el(sb, "AliasName", f.getAlias());
            }
            if (f.getDefaultValue() != null) {
                el(sb, "DefaultValue", f.getDefaultValue());
            }
            el(sb, "FieldType", esriFieldType(f.getType()));
            if (f.isObjectId() || f.isGeometry()) {
                el(sb, "IsNullable", f.isNullable() ? "true" : "false");
                el(sb, "Required", "true");
                if (f.isObjectId()) {
                    el(sb, "Editable", "false");
                }
            } else {
                if (f.isNullable()) {
                    el(sb, "IsNullable", "true");
                }
                if (f.isRequired()) {
                    el(sb, "Required", "true");
                }
                if (!f.isEditable()) {
                    el(sb, "Editable", "false");
                }
            }
            el(sb, "Length", fieldLength(f));
            el(sb, "Precision", 0);
            el(sb, "Scale", 0);
            sb.append("</GPFieldInfoEx>");
        }
        sb.append("</GPFieldInfoExs>");
    }

    /** 追加根级 {@code <SpatialReference>}。 */
    private static void appendSpatialReference(StringBuilder sb, String wkt,
                                               int wkid, int latestWkid) {
        sb.append("<SpatialReference xsi:type=\"typens:")
                .append(srsKind(wkt)).append("\">");
        if (wkt != null && !wkt.isEmpty()) {
            el(sb, "WKT", wkt);
        }
        el(sb, "XOrigin", fmt(DF_X_ORIGIN));
        el(sb, "YOrigin", fmt(DF_Y_ORIGIN));
        el(sb, "XYScale", fmt(DF_XY_SCALE));
        el(sb, "ZOrigin", fmt(DF_Z_ORIGIN));
        el(sb, "ZScale", fmt(DF_Z_SCALE));
        el(sb, "MOrigin", fmt(DF_M_ORIGIN));
        el(sb, "MScale", fmt(DF_M_SCALE));
        el(sb, "XYTolerance", fmt(DF_XY_TOLERANCE));
        el(sb, "ZTolerance", fmt(DF_Z_TOLERANCE));
        el(sb, "MTolerance", fmt(DF_M_TOLERANCE));
        el(sb, "HighPrecision", "true");
        if (wkid > 0) {
            el(sb, "WKID", wkid);
        }
        if (latestWkid > 0) {
            el(sb, "LatestWKID", latestWkid);
        }
        sb.append("</SpatialReference>");
    }

    /** 由 WKT 首关键字推 ESRI 坐标系类型名。 */
    private static String srsKind(String wkt) {
        String head = wkt == null ? "" : wkt.trim().toUpperCase(Locale.ROOT);
        if (head.startsWith("PROJCS")) {
            return "ProjectedCoordinateSystem";
        }
        if (head.startsWith("GEOGCS")) {
            return "GeographicCoordinateSystem";
        }
        return "UnknownCoordinateSystem";
    }

    /**
     * {@code FGFT_*} → ESRI 字段类型字符串。
     *
     * <p>出处 {@code filegdb_gdbtoogrfieldtype.h} 与
     * {@code CreateXMLFieldDefinition}(ogropenfilegdblayer_write.cpp:871)。
     *
     * @param type 字段类型
     * @return ESRI 字符串
     */
    public static String esriFieldType(FieldType type) {
        switch (type) {
            case INT16:
                return "esriFieldTypeSmallInteger";
            case INT32:
                return "esriFieldTypeInteger";
            case FLOAT32:
                return "esriFieldTypeSingle";
            case FLOAT64:
                return "esriFieldTypeDouble";
            case STRING:
                return "esriFieldTypeString";
            case DATETIME:
                return "esriFieldTypeDate";
            case OBJECTID:
                return "esriFieldTypeOID";
            case GEOMETRY:
                return "esriFieldTypeGeometry";
            case BINARY:
                return "esriFieldTypeBlob";
            case RASTER:
                return "esriFieldTypeRaster";
            case GUID:
                return "esriFieldTypeGUID";
            case GLOBALID:
                return "esriFieldTypeGlobalID";
            case XML:
                return "esriFieldTypeXML";
            case INT64:
                return "esriFieldTypeBigInteger";
            case DATE:
                return "esriFieldTypeDateOnly";
            case TIME:
                return "esriFieldTypeTimeOnly";
            case DATETIME_WITH_OFFSET:
                return "esriFieldTypeTimestampOffset";
            default:
                return "esriFieldTypeString";
        }
    }

    /** XML 里的 {@code <Length>}:字符串取宽度(0 则默认 255),其余用定长值。 */
    private static int fieldLength(GdbField f) {
        if (f.getType() == FieldType.STRING) {
            return f.getLength() > 0 ? f.getLength() : 255;
        }
        String esri = esriFieldType(f.getType()).toLowerCase(Locale.ROOT);
        switch (esri) {
            case "esrifieldtypesmallinteger":
                return 2;
            case "esrifieldtypeinteger":
            case "esrifieldtypesingle":
            case "esrifieldtypeoid":
                return 4;
            case "esrifieldtypedouble":
            case "esrifieldtypedate":
            case "esrifieldtypebiginteger":
            case "esrifieldtypedateonly":
            case "esrifieldtypetimeonly":
                return 8;
            case "esrifieldtypetimestampoffset":
                return 10;
            case "esrifieldtypegeometry":
                return 0;
            default:
                return f.getLength();
        }
    }

    /** 追加 {@code <tag>value</tag>};{@code value == null} 时追加空元素。 */
    private static void el(StringBuilder sb, String tag, Object value) {
        sb.append('<').append(tag).append('>');
        if (value != null) {
            sb.append(escape(String.valueOf(value)));
        }
        sb.append("</").append(tag).append('>');
    }

    /** XML 文本转义。 */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 浮点格式化:整值不带小数点,其余给普通十进制(不用科学计数法)。
     *
     * <p>Definition XML 里的 {@code <XMin>} / {@code <Scale>} 等必须是普通十进制形态,
     * 与 ArcGIS 自己写出的 XML 一致,理由见 {@link GdbNumbers#plain(double)}。
     */
    private static String fmt(double v) {
        return GdbNumbers.plain(v);
    }

    // ==================================================================
    // 供调试
    // ==================================================================

    @Override
    public String toString() {
        return "<GdbSystemCatalog " + catalogPath.getFileName()
                + " entries=" + entries().size() + ">";
    }
}
