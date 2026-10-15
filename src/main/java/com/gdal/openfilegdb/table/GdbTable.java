package com.gdal.openfilegdb.table;

import com.gdal.openfilegdb.exception.GdbFormatException;
import com.gdal.openfilegdb.exception.GdbIoException;
import com.gdal.openfilegdb.exception.GdbNotFoundException;
import com.gdal.openfilegdb.exception.GdbVersionException;
import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.FileGdbGeometryCodec;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeomField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.io.ByteReader;
import com.gdal.openfilegdb.io.ByteWriter;
import com.gdal.openfilegdb.io.LittleEndian;
import com.gdal.openfilegdb.io.Utf16;
import com.gdal.openfilegdb.util.BitArray;
import com.gdal.openfilegdb.util.GdbDateTimeUtil;
import com.gdal.openfilegdb.util.GuidUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个 {@code .gdbtable} 文件(可能配套一个 {@code .gdbtablx})。
 *
 * <p>本类是整个读写链路的核心,严格对照 GDAL
 * {@code ogr/ogrsf_frmts/openfilegdb/filegdbtable.cpp} 与
 * {@code filegdbtable_write.cpp} 实现:
 *
 * <table border="1">
 *   <caption>方法对照</caption>
 *   <tr><th>本类方法</th><th>GDAL 对应</th></tr>
 *   <tr><td>{@link #open(Path)}</td><td>{@code FileGDBTable::Open}</td></tr>
 *   <tr><td>{@link #readFields}</td><td>{@code FileGDBTable::Open}(字段描述区部分)</td></tr>
 *   <tr><td>{@link #readRowValues(int)}</td><td>{@code SelectRow} + {@code GetAllFieldValues}</td></tr>
 *   <tr><td>{@link #iterator()}</td><td>{@code GetAndSelectNextNonEmptyRow} 循环</td></tr>
 *   <tr><td>{@link #create}</td><td>{@code FileGDBTable::Create} + {@code WriteHeader} + {@code WriteFieldDescriptors}</td></tr>
 *   <tr><td>{@link #appendFeature}</td><td>{@code FileGDBTable::CreateFeature}</td></tr>
 *   <tr><td>{@link #sync()}</td><td>{@code FileGDBTable::Sync}</td></tr>
 * </table>
 *
 * <h2>文件布局(version 3)</h2>
 * <pre>
 *   ---------- 主头 (40 字节) ----------
 *   +0   int32   version                 3 (ArcGIS 10.x) 或 4
 *   +4   int32   nValidRecordCount       有效记录数(v4 挪到 +16)
 *   +8   int32   nHeaderBufferMaxSize    记录体最大字节数(写头部时回填)
 *   +12  int32   魔数 5
 *   +16  int32   0(v4 下是 64 位有效记录数)
 *   +20  int32   0
 *   +24  uint64  nFileSize               整个 .gdbtable 的字节数(写头部时回填)
 *   +32  uint64  nOffsetFieldDesc        字段描述区的起始偏移,通常是 40
 *
 *   ---------- 字段描述区 (从 nOffsetFieldDesc 开始) ----------
 *   +0   uint32  nFieldDescLength        本区长度(不含自身这 4 字节)
 *   +4   uint32  nSecondaryHeaderVersion 10.x 为 4
 *   +8   byte    tableGeomType           FGTGT_* 表级几何类型
 *   +9   byte    flags                   bit0 = 字符串是 UTF-8
 *   +10  byte    (未用)
 *   +11  byte    geomTypeFlags           bit6 = 有 M,bit7 = 有 Z
 *   +12  uint16  nFields                 字段个数
 *   +14  ...     字段定义,共 nFieldDescLength-10 字节,末尾是 0xDEADBEEF
 *
 *   ---------- 记录区 (紧跟字段描述区,扁平无块) ----------
 *   反复出现:  [uint32 记录体长度][记录体]
 *   记录体 = [空值位图][字段值...]
 * </pre>
 *
 * <p><b>关于"block"与"RLE 压缩"的澄清</b>:网上流传的"gdbtable 由若干 block
 * 组成、block 用 RLE 压缩"的说法在本驱动中<b>不成立</b>。核对
 * {@code filegdbtable.cpp} 后可以确认:{@code .gdbtable} 是扁平的记录序列,
 * 没有任何 block 头、block 链或记录级压缩。FileGDB 里唯一真正的"压缩"是
 * 几何 blob 内部的点串编码(见 {@link FileGdbGeometryCodec})。
 */
public final class GdbTable implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GdbTable.class);

    /** 主头固定长度。字段描述区从 {@code nOffsetFieldDesc}(通常就是 40)开始。 */
    public static final int MAIN_HEADER_SIZE = 40;

    /** 字段描述区的固定前缀长度:u32 区长度 + u32 二级版本 + byte[4] layerFlags + u16 字段数。 */
    public static final int FIELD_DESC_PREFIX_SIZE = 14;

    /**
     * 解析字段描述区时给缓冲区尾部预留的零字节,避免越界读。
     *
     * <p>见 {@code filegdbtable.cpp} 里的 {@code ZEROES_AFTER_END_OF_BUFFER}。
     */
    private static final int ZEROES_AFTER_END_OF_BUFFER = 4;

    /** 单条记录体的长度上限(防御性检查,防止把损坏的长度字段当真)。 */
    private static final int MAX_RECORD_SIZE = 100 * 1024 * 1024;

    /**
     * 几何字段未指定空间索引格网分辨率时的兜底默认值。
     *
     * <p>取值照抄 ArcGIS 在地理坐标系(度)下建要素类的实测默认:
     * {@code {0.012, 0.4, 12.0}}(见 {@code pyopenfilegdb/_gdb_template.py} 里
     * {@code GDB_Items.Shape} 的 grids,以及本工程 {@code GdbTemplates.items()})。
     *
     * <p><b>为什么必须有兜底:</b>GDAL 解析字段描述区时要求
     * {@code 1 <= nGridSizeCount <= 3}({@code filegdbtable.cpp:1412-1421},
     * {@code returnErrorIf(nGridSizeCount == 0 || nGridSizeCount > 3)}),
     * 写 0 个会产出一个连 GDAL 自己都打不开的 {@code .gdbtable}。
     */
    private static final double[] DEFAULT_GRID_RESOLUTIONS = {0.012, 0.4, 12.0};

    private final Path path;
    private final Path dir;
    /** {@code .gdbtable} 的 8 位十六进制前缀,例如 {@code a00000001}。 */
    private final String basename;

    // ---- 头部 ----
    private int version = 3;
    private int validRecordCount;
    private int headerBufferMaxSize;
    private long fileSize;
    private long offsetFieldDesc = MAIN_HEADER_SIZE;
    private int fieldDescLength;
    private int secondaryHeaderVersion = 4;

    // ---- 表级几何信息 ----
    private int tableGeomType;
    private boolean stringsAreUtf8;
    private boolean hasZ;
    private boolean hasM;

    // ---- 字段 ----
    private final List<GdbField> fields = new ArrayList<>();
    private GdbGeomField geomField;
    private int geomFieldIndex = -1;
    private int oidFieldIndex = -1;

    private int nullableFieldCount;
    private int nullableBitmapSize;

    /** 记录区的起始偏移 = 字段描述区末尾,用于追加记录。 */
    private long recordsStart;

    /** 行索引。 */
    private GdbTableIndex tablx;

    /**
     * 只读与读写的统一句柄。
     *
     * <p>用 {@link FileChannel} 的位置读写({@code read(buf, pos)} /
     * {@code write(buf, pos)})而非 {@code RandomAccessFile.seek},因为位置读写
     * 在 JDK 中是线程安全的(C 层直接使用 pread/pwrite),符合"读操作尽量线程安全"
     * 的约束。
     */
    private FileChannel channel;

    /** 是否以可写方式打开 / 新建(见 {@link #isWritable()})。 */
    private boolean writable;

    // ---- 写路径状态 ----
    /** 有"还没落到底盘账目里"的改动(表头计数 / 文件大小 / 索引),{@link #sync()} 才写。 */
    private boolean dirty;
    /** 几何字段的全表包围盒被改过,需要在 {@link #sync()} 时回填字段描述区。 */
    private boolean dirtyGeomBbox;
    /** 全表包围盒 {@code [xmin, ymin, xmax, ymax]};{@code null} = 还没有任何几何。 */
    private double[] bbox;

    private GdbTable(Path path) {
        this.path = path.toAbsolutePath();
        this.dir = this.path.getParent();
        String fn = this.path.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        this.basename = dot > 0 ? fn.substring(0, dot) : fn;
    }

    // ==================================================================
    // 打开 / 创建
    // ==================================================================

    /**
     * 打开 {@code .gdbtable} 并解析头部与字段定义(只读)。
     *
     * <p>等价于 GDAL {@code FileGDBTable::Open} 在 {@code bUpdate=false} 下的路径。
     *
     * @param path 文件路径
     * @return 表对象
     */
    public static GdbTable open(Path path) {
        return openInternal(path, false);
    }

    /**
     * 以可写方式打开一个已存在的 {@code .gdbtable}。
     *
     * <p>对应 GDAL 的 {@code bUpdate=TRUE} 路径。目前只支持 version 3
     * (ArcGIS 10.x);version 4 会抛 {@link GdbVersionException}。
     *
     * @param path 文件路径
     * @return 可写的表对象
     */
    public static GdbTable openForWrite(Path path) {
        return openInternal(path, true);
    }

    private static GdbTable openInternal(Path path, boolean forWrite) {
        GdbTable self = new GdbTable(path);
        if (!Files.exists(path)) {
            throw new GdbNotFoundException("文件不存在: " + path);
        }
        self.openChannel(forWrite);
        try {
            self.readHeaderAndFields();
        } catch (RuntimeException e) {
            self.closeQuietly();
            throw e;
        }
        if (forWrite && self.version != 3) {
            self.closeQuietly();
            throw new GdbVersionException(
                    path.getFileName() + ": 版本 " + self.version + " 不支持写入(仅支持 3)");
        }
        // 表级包围盒装载:读 / 写两条路径都要做。
        // 读路径是为了让 getExtent() 反映文件里声明的真实范围(此前只写在写路径,
        // 导致只读打开的表 getExtent() 恒为 null);写路径是为了追加要素时能在
        // 既有范围上继续并集扩张。
        self.loadBboxFromGeometryField();
        if (forWrite) {
            // 文件大小"量文件",不读表头 —— 对应 GDAL FileGDBTable::OpenFile 在
            // m_bUpdate 下那两句 VSIFSeekL(...SEEK_END) / VSIFTellL(...)。头部是
            // 可以懒写的(写要素不再逐条 sync),所以表头里的 +24 完全可能落后于
            // 真实长度;信它就会把一个新记录覆盖到已有记录上面。
            self.fileSize = self.fileSizeOnDisk();
        }
        // 加载行索引
        Path tablxPath = self.dir.resolve(self.basename + ".gdbtablx");
        if (Files.exists(tablxPath)) {
            self.tablx = GdbTableIndex.open(tablxPath);
        } else if (self.validRecordCount > 0) {
            self.closeQuietly();
            throw new GdbFormatException(
                    path.getFileName() + ": 缺少 .gdbtablx,无法定位记录");
        }
        return self;
    }

    /**
     * 从几何字段描述区装载表级包围盒到 {@link #bbox}。
     *
     * <p>对应 GDAL 读 {@code FileGDBGeomField} 的 {@code GetXMin()/GetYMin()/
     * GetXMax()/GetYMax()}(即字段描述区尾部那 4 个 double)。
     *
     * <p>⚠️ <b>NaN 与"四个分量全 0"都视为"范围未知"</b>:空表(或从未写入过
     * 要素的表)的 bbox 写的是 ESRI NaN;而"恰好是 (0,0)-(0,0)"这种退化范围
     * 无法与"尚未设置"区分,按惯例当未知处理(与写路径原口径一致)。
     */
    private void loadBboxFromGeometryField() {
        GdbGeomField gf = geomField;
        if (gf == null) {
            return;
        }
        double xmin = gf.getXmin();
        double ymin = gf.getYmin();
        double xmax = gf.getXmax();
        double ymax = gf.getYmax();
        if (Double.isNaN(xmin) || Double.isNaN(ymin)
                || Double.isNaN(xmax) || Double.isNaN(ymax)) {
            return;
        }
        if (xmin == 0.0 && ymin == 0.0 && xmax == 0.0 && ymax == 0.0) {
            return;
        }
        bbox = new double[]{xmin, ymin, xmax, ymax};
    }

    private void openChannel(boolean forWrite) {
        try {
            if (forWrite) {
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            } else {
                channel = FileChannel.open(path, StandardOpenOption.READ);
            }
            writable = forWrite;
        } catch (IOException e) {
            throw new GdbIoException("无法打开 " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * 是否以可写方式打开(对应 GDAL 的 {@code bUpdate})。
     *
     * <p>{@link #sync()} 之外的写操作(追加/更新/删除要素)都要求它是 {@code true},
     * 否则会抛 {@link GdbWriteException} —— 与 GDAL 在只读打开时拒绝写的口径一致。
     *
     * @return 是否可写
     */
    public boolean isWritable() {
        return writable;
    }

    private void readHeaderAndFields() {
        byte[] header = readFully(0, MAIN_HEADER_SIZE);
        if (header.length < MAIN_HEADER_SIZE) {
            throw new GdbFormatException(
                    path.getFileName() + ": 文件不足 " + MAIN_HEADER_SIZE + " 字节");
        }
        version = LittleEndian.getInt32(header, 0);
        if (version != 3 && version != 4) {
            throw new GdbVersionException(path.getFileName() + ": 不支持的 FileGDB 版本 "
                    + version + " (仅支持 3/4)");
        }
        // 对应 filegdbtable.cpp::FileGDBTable::Open: v4 把有效记录数挪到 +16 的 int64
        validRecordCount = version == 3
                ? LittleEndian.getInt32(header, 4)
                : (int) LittleEndian.getUInt32(header, 16);
        headerBufferMaxSize = LittleEndian.getInt32(header, 8);
        fileSize = LittleEndian.getUInt64(header, 24);
        offsetFieldDesc = LittleEndian.getUInt64(header, 32);
        if (offsetFieldDesc <= 0 || offsetFieldDesc > MAX_RECORD_SIZE * 100L) {
            throw new GdbFormatException(
                    path.getFileName() + ": 非法的字段描述区偏移 " + offsetFieldDesc);
        }

        byte[] prefix = readFully(offsetFieldDesc, FIELD_DESC_PREFIX_SIZE);
        if (prefix.length < FIELD_DESC_PREFIX_SIZE) {
            throw new GdbFormatException(path.getFileName() + ": 字段描述区前缀不完整");
        }
        fieldDescLength = (int) LittleEndian.getUInt32(prefix, 0);
        if (fieldDescLength < 10 || fieldDescLength > 10 * 1024 * 1024) {
            throw new GdbFormatException(
                    path.getFileName() + ": 非法的字段描述区长度 " + fieldDescLength);
        }
        secondaryHeaderVersion = (int) LittleEndian.getUInt32(prefix, 4);
        int geomType = prefix[8] & 0xFF;
        if (isValidLayerGeomType(geomType)) {
            tableGeomType = geomType;
        }
        stringsAreUtf8 = (prefix[9] & 0x1) != 0;
        int geomFlags = prefix[11] & 0xFF;
        // 对应 filegdbtable.cpp: layerFlags 的 bit30 = hasM, bit31 = hasZ
        hasM = (geomFlags & (1 << 6)) != 0;
        hasZ = (geomFlags & (1 << 7)) != 0;
        int nFields = LittleEndian.getUInt16(prefix, 12);

        byte[] body = readFully(offsetFieldDesc + FIELD_DESC_PREFIX_SIZE, fieldDescLength - 10);
        readFields(body, nFields);

        // 记录区的起始偏移。字段描述区第 0 个 uint32 存的是"区长度"(不含自身 4 字节),
        // 所以区末 = offsetFieldDesc + 4 + fieldDescLength,记录紧跟其后,
        // 中间没有填充字节。
        recordsStart = offsetFieldDesc + 4 + fieldDescLength;
    }

    /**
     * 解析字段描述区主体。
     *
     * <p>对应 {@code filegdbtable.cpp::FileGDBTable::Open} 中的字段循环。
     */
    private void readFields(byte[] body, int nFields) {
        // 尾部补零,对应 GDAL 的 ZEROES_AFTER_END_OF_BUFFER
        byte[] buf = new byte[body.length + ZEROES_AFTER_END_OF_BUFFER];
        System.arraycopy(body, 0, buf, 0, body.length);
        ByteReader r = new ByteReader(buf, 0, body.length);

        for (int i = 0; i < nFields; i++) {
            String name = r.utf16Prefixed();
            String alias = r.utf16Prefixed();
            int ftype = r.u8();

            GdbField field;
            if (ftype == FieldType.GEOMETRY.code() || ftype == FieldType.RASTER.code()) {
                field = readGeomOrRasterField(r, name, alias, ftype);
            } else {
                field = readSimpleField(r, name, alias, ftype);
            }
            fields.add(field);
            if (field.isGeometry()) {
                if (geomFieldIndex >= 0) {
                    throw new GdbFormatException("表里有多个几何字段,不支持");
                }
                geomFieldIndex = fields.size() - 1;
                geomField = (GdbGeomField) field;
            } else if (field.isObjectId()) {
                if (oidFieldIndex >= 0) {
                    throw new GdbFormatException("表里有多个 OBJECTID 字段,不支持");
                }
                oidFieldIndex = fields.size() - 1;
            }
            if (field.isNullable()) {
                nullableFieldCount++;
            }
        }
        nullableBitmapSize = BitArray.sizeInBytes(nullableFieldCount);
    }

    /**
     * 解析非几何/栅格字段。
     *
     * <p>三种子布局(GDAL 里是一个 switch 的三个分支):
     * <pre>
     *   STRING      : int32 maxWidth | byte flags | varuint defaultLen
     *   OID/BIN/GUID: byte(未用) | byte flags
     *   其它        : byte(未用) | byte flags | byte defaultLen
     * </pre>
     */
    private GdbField readSimpleField(ByteReader r, String name, String alias, int ftype) {
        int flags = 0;
        int maxWidth = 0;
        int defaultLen = 0;

        if (ftype == FieldType.STRING.code()) {
            maxWidth = r.i32();
            if (maxWidth < 0) {
                throw new GdbFormatException("字段 " + name + ": 负的字符串长度 " + maxWidth);
            }
            flags = r.u8();
            defaultLen = (int) r.varUInt();
        } else if (ftype == FieldType.OBJECTID.code() || ftype == FieldType.BINARY.code()
                || ftype == FieldType.GUID.code() || ftype == FieldType.GLOBALID.code()
                || ftype == FieldType.XML.code()) {
            r.skip(1);
            flags = r.u8();
        } else {
            r.skip(1);
            flags = r.u8();
            defaultLen = r.u8();
        }

        Object defaultValue = null;
        if ((flags & 0x04) != 0 && defaultLen > 0) {
            defaultValue = decodeDefault(r, ftype, defaultLen);
        }

        GdbField field = new GdbField(name, FieldType.fromCodeOrUndefined(ftype))
                .setLength(maxWidth)
                .setNullable((flags & 0x01) != 0)
                .setRequired((flags & 0x02) != 0)
                .setEditable((flags & 0x04) != 0)
                .setAlias(alias)
                .setDefaultValue(defaultValue);

        if (ftype == FieldType.OBJECTID.code() && (flags & 0x01) != 0) {
            // GDAL 断言 OBJECTID 的 flags 必须恰好是 MASK_REQUIRED;这里只对明显异常报错,
            // 避免个别文件直接读不了。
            throw new GdbFormatException("OBJECTID 字段 " + name + " 不应为 nullable");
        }
        return field;
    }

    /** 读一段默认值。GDAL 只对若干定长类型做转换,其余跳过。 */
    private Object decodeDefault(ByteReader r, int ftype, int n) {
        int start = r.position();
        Object value = null;
        if (ftype == FieldType.STRING.code()) {
            value = stringsAreUtf8
                    ? new String(r.bytes(n), java.nio.charset.StandardCharsets.UTF_8)
                    : r.utf16Bytes(n);
            return value;
        }
        if (ftype == FieldType.INT16.code() && n == 2) {
            value = r.i16();
        } else if (ftype == FieldType.INT32.code() && n == 4) {
            value = r.i32();
        } else if (ftype == FieldType.FLOAT32.code() && n == 4) {
            value = r.f32();
        } else if (ftype == FieldType.FLOAT64.code() && n == 8) {
            value = r.f64();
        } else if ((ftype == FieldType.DATETIME.code() || ftype == FieldType.DATE.code()
                || ftype == FieldType.TIME.code()) && n == 8) {
            value = GdbDateTimeUtil.daysToDateTime(r.f64());
        } else if (ftype == FieldType.INT64.code() && n == 8) {
            value = r.i64();
        }
        // 无论识别与否,都要跳过整段(GDAL: pabyIter += defaultValueLength)
        r.position(start + n);
        return value;
    }

    /**
     * 解析几何字段(或栅格字段)。
     *
     * <p>GDAL 里 {@code FGFT_GEOMETRY} / {@code FGFT_RASTER} 走同一段代码,区别只在于
     * 栅格字段多一个"栅格列名"和"栅格类型"字节,且尾部是 0/4 个 double 而不是
     * 包围盒 + Z/M 范围。
     */
    private GdbField readGeomOrRasterField(ByteReader r, String name, String alias, int ftype) {
        int flags = r.data()[r.position() + 1] & 0xFF;
        r.skip(2);
        if (ftype == FieldType.RASTER.code()) {
            r.skip(1); // 栅格列名字符数,后面才是名字;本项目不解析栅格
        }
        int wktLen = r.u16();
        String wkt = r.utf16Bytes(wktLen);

        int geomFlags = r.u8();
        boolean hasMOst = (geomFlags & 2) != 0;
        boolean hasZOst = (geomFlags & 4) != 0;

        GdbGeomField field = new GdbGeomField(name);
        // 注意:以下四个 setter 继承自 GdbField,返回基类类型,故不能串成一条链
        field.setAlias(alias);
        field.setNullable((flags & 0x01) != 0);
        field.setRequired((flags & 0x02) != 0);
        field.setEditable((flags & 0x04) != 0);
        field.setWkt(wkt)
                .setHasMOriginScaleTolerance(hasMOst)
                .setHasZOriginScaleTolerance(hasZOst);

        if (ftype == FieldType.GEOMETRY.code() || geomFlags > 0) {
            field.setXOrigin(r.f64()).setYOrigin(r.f64()).setXyScale(r.f64());
            if (field.getXyScale() == 0) {
                throw new GdbFormatException("几何字段 " + name + ": XYScale 为 0");
            }
            if (hasMOst) {
                field.setMOrigin(r.f64()).setMScale(r.f64());
            }
            if (hasZOst) {
                field.setZOrigin(r.f64()).setZScale(r.f64());
            }
            field.setXyTolerance(r.f64());
            if (hasMOst) {
                field.setMTolerance(r.f64());
            }
            if (hasZOst) {
                field.setZTolerance(r.f64());
            }
        }

        if (ftype == FieldType.RASTER.code()) {
            r.skip(1); // 栅格类型字节
            return field;
        }

        // 几何字段尾部:全表包围盒 + Z/M 范围 + 空间索引格网分辨率
        double xmin = r.f64();
        double ymin = r.f64();
        double xmax = r.f64();
        double ymax = r.f64();
        field.setExtent(xmin, ymin, xmax, ymax);
        if (hasZ) {
            field.setZRange(r.f64(), r.f64());
        }
        if (hasM) {
            field.setMRange(r.f64(), r.f64());
        }
        // 尾部还有一个空间索引格网块: byte 0x00 | uint32 分辨率个数(1..3) | 每个 double
        // 见 filegdbtable.cpp 中 m_nGeomFieldSpatialIndexGridResSubOffset 那一段。
        r.need(5);
        r.skip(1); // 恒为 0
        int nGrid = (int) r.u32();
        if (nGrid < 1 || nGrid > 3) {
            throw new GdbFormatException("几何字段 " + name + ": 非法的空间索引格网数 " + nGrid);
        }
        r.need(nGrid * 8);
        double[] grid = new double[nGrid];
        for (int i = 0; i < nGrid; i++) {
            grid[i] = r.f64();
        }
        field.setSpatialIndexGridResolutions(grid);
        return field;
    }

    /** 对应 GDAL 的 {@code IS_VALID_LAYER_GEOM_TYPE} 宏。 */
    private static boolean isValidLayerGeomType(int b) {
        return b == 1 || b == 2 || b == 3 || b == 4 || b == 9;
    }

    // ==================================================================
    // 基本信息
    // ==================================================================

    /** 表的绝对路径。 */
    public Path getPath() {
        return path;
    }

    /** 8 位十六进制前缀(如 {@code a00000001})。 */
    public String getBasename() {
        return basename;
    }

    /** 版本号(3 或 4)。 */
    public int getVersion() {
        return version;
    }

    /** 字段清单(只读视图)。 */
    public List<GdbField> getFields() {
        return java.util.Collections.unmodifiableList(fields);
    }

    /**
     * 按名字取字段(大小写不敏感)。
     *
     * @param name 字段名
     * @return 字段定义;不存在返回 {@code null}
     */
    public GdbField getFieldByName(String name) {
        for (GdbField f : fields) {
            if (f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    /**
     * 按名字取字段,取不到抛异常。
     *
     * @param name 字段名
     * @return 字段定义
     */
    public GdbField requireField(String name) {
        GdbField f = getFieldByName(name);
        if (f == null) {
            throw new GdbNotFoundException("字段不存在: " + name + " (表 " + basename + ")");
        }
        return f;
    }

    /** 几何字段;无几何字段返回 {@code null}。 */
    public GdbGeomField getGeomField() {
        return geomField;
    }

    /** 几何字段下标;无则 -1。 */
    public int getGeomFieldIndex() {
        return geomFieldIndex;
    }

    /** OBJECTID 字段下标;无则 -1。 */
    public int getOidFieldIndex() {
        return oidFieldIndex;
    }

    /** OBJECTID 字段名;无 OID 字段时返回空串。 */
    public String getOidFieldName() {
        return oidFieldIndex < 0 ? "" : fields.get(oidFieldIndex).getName();
    }

    /** 几何字段名;无几何字段时返回空串。 */
    public String getGeometryFieldName() {
        return geomFieldIndex < 0 ? "" : fields.get(geomFieldIndex).getName();
    }

    /** FileGDB 表级几何类型码({@code FGTGT_*})。 */
    public int getTableGeomType() {
        return tableGeomType;
    }

    /** 表级几何类型。 */
    public GeometryType getGeometryType() {
        return GeometryType.fromTableGeomType(tableGeomType);
    }

    /** 是否带 Z。 */
    public boolean hasZ() {
        return hasZ;
    }

    /** 是否带 M。 */
    public boolean hasM() {
        return hasM;
    }

    /** 字符串是否以 UTF-8 存储(ArcGIS 10.x 默认 UTF-16LE)。 */
    public boolean isStringsAreUtf8() {
        return stringsAreUtf8;
    }

    /** 有效记录数(不含已删除空洞)。 */
    public int getValidRecordCount() {
        return validRecordCount;
    }

    /** 槽位总数(含空洞),来自 {@code .gdbtablx} 头。 */
    public int getRecordCount() {
        return tablx != null ? tablx.recordCount() : validRecordCount;
    }

    /** 行索引(可能为 {@code null})。 */
    public GdbTableIndex getTableIndex() {
        return tablx;
    }

    /** 记录区起始偏移。 */
    public long getRecordsStart() {
        return recordsStart;
    }

    /** 全表包围盒 {@code [xmin,ymin,xmax,ymax]};无几何时返回 {@code null}。 */
    public double[] getExtent() {
        return bbox == null ? null : bbox.clone();
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /**
     * 读取第 {@code row} 条(0 基)记录的原始字段值。
     *
     * @param row 0 基行号
     * @return 与 {@link #getFields()} 同序的值数组,元素可能为 {@code null}(空值);
     *         空槽返回 {@code null}
     */
    public Object[] readRowValues(int row) {
        if (tablx == null) {
            throw new GdbFormatException("没有 .gdbtablx,无法按行号读取");
        }
        if (row < 0 || row >= tablx.recordCount()) {
            return null;
        }
        long offset = tablx.offsetForRow(row);
        if (offset == 0) {
            return null;
        }
        byte[] blob = readRowBlob(offset);
        if (blob == null) {
            return null;
        }
        return decodeRow(blob, row, false);
    }

    /**
     * 读取第 {@code row} 条记录的 {@code 字段名 -> 值} 映射(底层接口)。
     *
     * <p>第 {@code row} 条对应的 OID 是 {@code row + 1}(GDAL {@code SetFID(iRow + 1)})。
     * 几何字段在此返回 {@link GdbGeometry}(已解码)。要"几何不解码"请用
     * {@link #readFeature(int)} 或 {@link #iterator()}。
     *
     * @param row 0 基行号
     * @return 有序映射;空槽返回 {@code null}
     */
    public Map<String, Object> readRow(int row) {
        Object[] values = readRowValues(row);
        if (values == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            out.put(fields.get(i).getName(), values[i]);
        }
        return out;
    }

    /**
     * 读取第 {@code row} 条要素。
     *
     * <p>{@link GdbFeature#getAttributes()} 里 <b>不含</b> 几何字段,也不含 OBJECTID
     * 字段 —— OID 由行号推导,放在 {@link GdbFeature#getOid()} 里(等价于 OGR 的 FID)。
     *
     * <p>几何是 <b>惰性</b> 的:返回的要素携带未解码的几何 blob,首次访问
     * {@link GdbFeature#getGeometry()} 时才真正解析。只读属性的遍历不会为几何
     * 付解码代价。
     *
     * @param row 0 基行号
     * @return 要素;空槽返回 {@code null}
     */
    public GdbFeature readFeature(int row) {
        if (tablx == null) {
            throw new GdbFormatException("没有 .gdbtablx,无法按行号读取");
        }
        if (row < 0 || row >= tablx.recordCount()) {
            return null;
        }
        long offset = tablx.offsetForRow(row);
        if (offset == 0) {
            return null;
        }
        byte[] blob = readRowBlob(offset);
        if (blob == null) {
            return null;
        }
        return valuesToFeature(row + 1, decodeRow(blob, row, true));
    }

    /**
     * 按偏移读出记录体。
     *
     * <p>记录前面是一个 uint32 长度;最高位为 1 说明是"已删除但索引未同步"的槽位
     * (GDAL 会报 warning 并跳过)。
     *
     * @param offset 文件偏移
     * @return 记录体字节;空槽/已删除返回 {@code null}
     */
    private byte[] readRowBlob(long offset) {
        byte[] head = readFully(offset, 4);
        if (head.length < 4) {
            return null;
        }
        long length = LittleEndian.getUInt32(head, 0);
        if ((length & 0x80000000L) != 0) {
            // 删除标记
            return null;
        }
        if (length < nullableBitmapSize || length > MAX_RECORD_SIZE) {
            throw new GdbFormatException(
                    basename + ": 偏移 " + offset + " 处的记录长度 " + length + " 不合理");
        }
        byte[] blob = readFully(offset + 4, (int) length);
        if (blob.length < length) {
            throw new GdbFormatException(basename + ": 偏移 " + offset + " 处记录被截断");
        }
        return blob;
    }

    /**
     * 按字段定义解析记录体。
     *
     * <p>对应 GDAL {@code FileGDBTable::GetFieldValue}:先按顺序消费空值位图,
     * 再依字段类型逐个取值。已删除的字段(位图 = 1)不占字节。
     *
     * @param blob       记录体
     * @param row        0 基行号(OID = row + 1)
     * @param lazyGeom   true 时几何字段只记位置不解码
     * @return 与字段同序的值数组
     */
    private Object[] decodeRow(byte[] blob, int row, boolean lazyGeom) {
        Object[] out = new Object[fields.size()];
        byte[] geomBlobHolder = null;
        int end = blob.length;
        int pos = nullableBitmapSize;
        int bit = 0;

        try {
            for (int i = 0; i < fields.size(); i++) {
                GdbField f = fields.get(i);
                int bbyte = -1;
                int bmask = 0;
                if (f.isNullable()) {
                    bbyte = bit >> 3;
                    bmask = 1 << (bit & 7);
                    bit++;
                }
                // -- 空值位图:bit=1 表示该字段为空,记录体里不占字节
                if (bbyte >= 0 && (blob[bbyte] & bmask) != 0) {
                    out[i] = null;
                    continue;
                }

                FieldType t = f.getType();
                if (t == FieldType.OBJECTID) {
                    // OID 不占记录空间,由行号推导
                    out[i] = (long) (row + 1);
                    continue;
                }

                switch (t) {
                    case INT16:
                        need(pos, 2, end, row, f.getName());
                        out[i] = LittleEndian.getInt16(blob, pos);
                        pos += 2;
                        break;
                    case INT32:
                        need(pos, 4, end, row, f.getName());
                        out[i] = LittleEndian.getInt32(blob, pos);
                        pos += 4;
                        break;
                    case FLOAT32:
                        need(pos, 4, end, row, f.getName());
                        out[i] = LittleEndian.getFloat32(blob, pos);
                        pos += 4;
                        break;
                    case FLOAT64:
                        need(pos, 8, end, row, f.getName());
                        out[i] = LittleEndian.getFloat64(blob, pos);
                        pos += 8;
                        break;
                    case INT64:
                        need(pos, 8, end, row, f.getName());
                        out[i] = LittleEndian.getInt64(blob, pos);
                        pos += 8;
                        break;
                    case DATETIME:
                    case DATE:
                    case TIME: {
                        need(pos, 8, end, row, f.getName());
                        out[i] = GdbDateTimeUtil.daysToDateTime(LittleEndian.getFloat64(blob, pos));
                        pos += 8;
                        break;
                    }
                    case STRING:
                    case XML: {
                        long[] nv = com.gdal.openfilegdb.io.VarInt.read(blob, pos, end);
                        int n = (int) nv[0];
                        pos = (int) nv[1];
                        need(pos, n, end, row, f.getName());
                        out[i] = stringsAreUtf8
                                ? new String(blob, pos, n, java.nio.charset.StandardCharsets.UTF_8)
                                : Utf16.decode(blob, pos, n);
                        pos += n;
                        break;
                    }
                    case GEOMETRY: {
                        long[] nv = com.gdal.openfilegdb.io.VarInt.read(blob, pos, end);
                        int n = (int) nv[0];
                        pos = (int) nv[1];
                        need(pos, n, end, row, f.getName());
                        if (n == 0) {
                            out[i] = null;
                        } else if (lazyGeom) {
                            geomBlobHolder = Arrays.copyOfRange(blob, pos, pos + n);
                            out[i] = geomBlobHolder;
                        } else {
                            out[i] = FileGdbGeometryCodec.decode(
                                    Arrays.copyOfRange(blob, pos, pos + n),
                                    geomField, hasZ, hasM);
                        }
                        pos += n;
                        break;
                    }
                    case GUID:
                    case GLOBALID:
                        need(pos, 16, end, row, f.getName());
                        out[i] = GuidUtil.format(blob, pos);
                        pos += 16;
                        break;
                    case BINARY: {
                        long[] nv = com.gdal.openfilegdb.io.VarInt.read(blob, pos, end);
                        int n = (int) nv[0];
                        pos = (int) nv[1];
                        need(pos, n, end, row, f.getName());
                        out[i] = Arrays.copyOfRange(blob, pos, pos + n);
                        pos += n;
                        break;
                    }
                    case RASTER: {
                        // 托管栅格:读进来也只是丢掉(GDAL 的 eRasterColumnType)
                        long[] nv = com.gdal.openfilegdb.io.VarInt.read(blob, pos, end);
                        int n = (int) nv[0];
                        pos = (int) nv[1];
                        need(pos, n, end, row, f.getName());
                        out[i] = null;
                        pos += n;
                        break;
                    }
                    case DATETIME_WITH_OFFSET: {
                        need(pos, 10, end, row, f.getName());
                        LocalDateTime dt = GdbDateTimeUtil.daysToDateTime(
                                LittleEndian.getFloat64(blob, pos));
                        short off = LittleEndian.getInt16(blob, pos + 8);
                        out[i] = new Object[]{dt, (int) off};
                        pos += 10;
                        break;
                    }
                    default:
                        throw new GdbFormatException(
                                "字段 " + f.getName() + ": 未处理的字段类型 " + t);
                }
            }
        } catch (IndexOutOfBoundsException e) {
            throw new GdbFormatException(
                    basename + ": 行 " + row + " 记录解码失败(越界)", e);
        }
        return out;
    }

    private void need(int pos, int n, int end, int row, String fieldName) {
        if (pos + n > end) {
            throw new GdbFormatException(
                    basename + ": 行 " + row + " 字段 " + fieldName + " 越界");
        }
    }

    /**
     * 把原始值数组规整成 {@link GdbFeature}。
     *
     * <p>OID 与几何从属性表里摘出去,分别落到
     * {@link GdbFeature#getOid()} 与 {@link GdbFeature#getGeometry()}
     * —— 与 OGR 的 feature 模型一致。
     */
    private GdbFeature valuesToFeature(long oid, Object[] values) {
        GdbFeature feature = new GdbFeature();
        feature.setOid(oid);
        for (int i = 0; i < fields.size(); i++) {
            GdbField f = fields.get(i);
            Object v = values[i];
            if (f.isObjectId()) {
                continue;
            }
            if (f.isGeometry()) {
                if (v instanceof byte[]) {
                    byte[] blob = (byte[]) v;
                    if (blob.length > 0) {
                        feature.setPendingGeometry(blob, geomField, hasZ, hasM);
                    }
                }
                continue;
            }
            feature.putAttributeRaw(f.getName(), v);
        }
        return feature;
    }

    /**
     * 顺序迭代所有要素,自动跳过空槽。
     *
     * <p>对应 GDAL 的 {@code GetAndSelectNextNonEmptyRow} 循环。
     *
     * <p>⚠️ 记录在 {@code .gdbtable} 里<b>既不连续也不按行号排列</b>,所以这里
     * 不是顺序读,每条都要按偏移 seek —— 省下的只是"每条重开一次文件"。
     *
     * @return 要素迭代器
     */
    public Iterator<GdbFeature> iterator() {
        if (tablx == null) {
            throw new GdbFormatException("没有 .gdbtablx,无法迭代记录");
        }
        // Java 8:匿名内部类不能使用钻石操作符(那是 Java 9+ 的特性),须写全类型参数
        return new Iterator<GdbFeature>() {
            private int nextRow = 1; // 1 基游标,与 Python 的 row+1 一致
            private GdbFeature pending;
            private final int total = tablx.recordCount();

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    return true;
                }
                while (nextRow <= total) {
                    GdbFeature f = readFeature(nextRow - 1);
                    nextRow++;
                    if (f != null) {
                        pending = f;
                        return true;
                    }
                }
                return false;
            }

            @Override
            public GdbFeature next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                GdbFeature f = pending;
                pending = null;
                return f;
            }
        };
    }

    /** 迭代所有要素(便于 for-each)。 */
    public Iterable<GdbFeature> features() {
        return this::iterator;
    }

    /** 统计非空槽位数(顺序扫一遍索引,不读记录体)。 */
    public int countFeatures() {
        if (tablx == null) {
            return 0;
        }
        int n = 0;
        for (int row = 0; row < tablx.recordCount(); row++) {
            if (tablx.offsetForRow(row) != 0) {
                n++;
            }
        }
        return n;
    }

    // ==================================================================
    // 写入
    // ==================================================================

    /**
     * 新建一个空的 {@code .gdbtable}(并同时建好 {@code .gdbtablx})。
     *
     * <p>对应 GDAL {@code FileGDBTable::Create} + {@code WriteHeader} +
     * {@code WriteFieldDescriptors}。
     *
     * @param path            文件路径
     * @param fields          完整字段清单。必须至多一个 OBJECTID 字段(会被自动挪到最前)
     *                        与至多一个几何字段
     * @param tableGeomType   {@code FGTGT_*} 表级几何类型
     * @param hasZ            表级 hasZ
     * @param hasM            表级 hasM
     * @param stringsAreUtf8  字符串是否用 UTF-8(ArcGIS 10.x 用 false → UTF-16LE)
     * @param creator         写在 40 字节头之后的自由字符串;{@code null}(默认)<b>不写</b>,
     *                        此时头部布局与 ArcGIS 完全一致(字段描述区从 40 开始)。
     *                        GDAL 会写 {@code "GDAL x.y.z"},想模仿它传个字符串即可
     * @param offsetSize      {@code .gdbtablx} 里每个偏移占几字节;5 足够 1TB
     * @return 可写的表对象
     */
    public static GdbTable create(Path path, List<GdbField> fields, int tableGeomType,
                                  boolean hasZ, boolean hasM, boolean stringsAreUtf8,
                                  String creator, int offsetSize) {
        GdbTable self = new GdbTable(path);

        List<GdbField> list = new ArrayList<>(fields);
        long nOid = list.stream().filter(GdbField::isObjectId).count();
        if (nOid > 1) {
            throw new GdbWriteException("字段清单最多 1 个 OBJECTID 字段,实得 " + nOid);
        }
        long nGeom = list.stream().filter(GdbField::isGeometry).count();
        if (nGeom > 1) {
            throw new GdbWriteException("字段清单最多 1 个几何字段,实得 " + nGeom);
        }
        if (tableGeomType != 0 && nGeom != 1) {
            throw new GdbWriteException("声明了几何类型,但字段清单里没有几何字段");
        }

        // ArcGIS 建的每一个表都是 ObjectID 打头(实测所有真实 .gdb),这里统一把 OID 挪到最前。
        // ⚠️ 但 OID 字段不是必需的:GDAL 自己写的 GDB_DBTune (a00000002) 就完全没有 OID 字段。
        int oidPos = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isObjectId()) {
                oidPos = i;
                break;
            }
        }
        if (oidPos > 0) {
            list.add(0, list.remove(oidPos));
        }

        self.fields.addAll(list);
        for (int i = 0; i < list.size(); i++) {
            GdbField f = list.get(i);
            if (f.isGeometry()) {
                self.geomFieldIndex = i;
                self.geomField = (GdbGeomField) f;
            } else if (f.isObjectId()) {
                self.oidFieldIndex = i;
            }
            if (f.isNullable()) {
                self.nullableFieldCount++;
            }
        }
        self.nullableBitmapSize = BitArray.sizeInBytes(self.nullableFieldCount);
        normalizeGridResolutions(self.geomField);
        self.tableGeomType = tableGeomType;
        self.hasZ = hasZ;
        self.hasM = hasM;
        self.stringsAreUtf8 = stringsAreUtf8;
        self.dir.toFile().mkdirs();

        // --- 头部(40 字节)+ 可选的 creator 串 ---
        // ⚠️ creator 串不是 FileGDB 格式的一部分。GDAL 在 40 字节之后写一个
        // "GDAL x.y.z" 字符串,并在注释里明说"这不是规范的一部分,我们只是利用
        // 文件里可能存在幽灵区域"(filegdbtable_write.cpp)。真实的 ArcGIS 文件
        // 没有这段:offset 40 处直接就是字段描述区。
        byte[] creatorBytes = creator == null || creator.isEmpty()
                ? new byte[0]
                : creator.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteWriter head = new ByteWriter(MAIN_HEADER_SIZE + creatorBytes.length + 4);
        head.zeros(MAIN_HEADER_SIZE);
        if (creatorBytes.length > 0) {
            head.u32(creatorBytes.length);
            head.raw(creatorBytes);
        }
        try {
            Files.write(path, head.toByteArray());
        } catch (IOException e) {
            throw new GdbIoException("无法创建 " + path + ": " + e.getMessage(), e);
        }
        self.offsetFieldDesc = MAIN_HEADER_SIZE
                + (creatorBytes.length > 0 ? 4 + creatorBytes.length : 0);

        self.openChannel(true);
        self.writeFieldDescriptors();
        self.recordsStart = self.offsetFieldDesc + 4 + self.fieldDescLength;
        self.fileSize = self.recordsStart;
        self.tablx = GdbTableIndex.create(
                self.dir.resolve(self.basename + ".gdbtablx"), offsetSize);
        self.writeHeader();
        // 还欠一个 .gdbtablx 文件(它是第一次 sync()/flush() 才建出来的),
        // 所以这里就标脏,免得"建完就 close、一条要素都没写"的表少了索引文件。
        self.dirty = true;
        return self;
    }

    /** 以默认参数创建(GDAL 风格:不写 creator,offsetSize = 5)。 */
    public static GdbTable create(Path path, List<GdbField> fields, int tableGeomType,
                                  boolean hasZ, boolean hasM, boolean stringsAreUtf8) {
        return create(path, fields, tableGeomType, hasZ, hasM, stringsAreUtf8, null, 5);
    }

    /**
     * 把几何字段的空间索引格网分辨率归一化到 GDAL 能接受的 1..3 个。
     *
     * <p>对应 GDAL 读侧的断言 {@code returnErrorIf(nGridSizeCount == 0
     * || nGridSizeCount > 3)}({@code filegdbtable.cpp:1412-1421})。GDAL 的写侧
     * ({@code filegdbtable_write_fields.cpp:568})只是把字段上已有的分辨率原样写出,
     * 不补默认值 —— 也就是说"写 0 个"会产出自己都读不回来的文件。这里补一道兜底,
     * 保证本库写出的 {@code .gdbtable} 永远可被 GDAL(和本库自己)打开。
     *
     * @param geomField 几何字段;为 {@code null}(非空间表)时什么都不做
     */
    private static void normalizeGridResolutions(GdbGeomField geomField) {
        if (geomField == null) {
            return;
        }
        double[] grids = geomField.getSpatialIndexGridResolutions();
        if (grids == null || grids.length == 0) {
            LOG.warn("几何字段 {} 未指定空间索引格网分辨率,回退到 ArcGIS 默认值 {}",
                    geomField.getName(), Arrays.toString(DEFAULT_GRID_RESOLUTIONS));
            geomField.setSpatialIndexGridResolutions(DEFAULT_GRID_RESOLUTIONS.clone());
        } else if (grids.length > 3) {
            LOG.warn("几何字段 {} 指定了 {} 个格网分辨率,GDAL 只接受最多 3 个,已截断",
                    geomField.getName(), grids.length);
            geomField.setSpatialIndexGridResolutions(Arrays.copyOf(grids, 3));
        }
    }

    /**
     * 回填 40 字节主头。
     *
     * <p>对应 GDAL {@code FileGDBTable::WriteHeader}。各字段含义(偏移为文件起始):
     * {@code +0} 版本、{@code +4} 有效记录数、{@code +8} 记录体最大字节数、
     * {@code +12/16/20} 魔数 5/0/0、{@code +24} 文件总字节数、{@code +32} 字段描述区偏移。
     */
    private void writeHeader() {
        if (channel == null) {
            throw new GdbWriteException("表不是以可写方式打开的");
        }
        // 对应 GDAL Sync() 里那一行:
        //   m_nHeaderBufferMaxSize = max(m_nHeaderBufferMaxSize, max(m_nRowBufferMaxSize, m_nFieldDescLength))
        // 字段描述区本身也参与取最大 —— 只记"最长记录体"会在字段多而记录短的
        // 表上写出偏小的值。
        if (fieldDescLength > headerBufferMaxSize) {
            headerBufferMaxSize = fieldDescLength;
        }
        ByteWriter h = new ByteWriter(MAIN_HEADER_SIZE);
        h.u32(version);
        h.u32(validRecordCount);
        h.u32(headerBufferMaxSize);
        h.u32(5);
        h.u32(0);
        h.u32(0);
        h.u64(fileSize);
        h.u64(offsetFieldDesc);
        writeAt(0, h.internalBuffer(), 0, MAIN_HEADER_SIZE);
    }

    /**
     * 把字段描述区写到 {@link #offsetFieldDesc}。
     *
     * <p>布局(对应 {@code WriteFieldDescriptors} + {@code WriteFieldDescriptor}):
     * <pre>
     *   u32  区长度(不含自身这 4 字节,即 GDAL 的 m_nFieldDescLength)
     *   u32  二级版本 = 4
     *   u32  nLayerFlags
     *   u16  字段个数
     *   ...  逐个字段描述
     *   u32  0xEFBEADDE(即 DE AD BE EF 的小端读法)
     * </pre>
     */
    private void writeFieldDescriptors() {
        // body = [u32 二级版本][u32 layerFlags][u16 nFields][fields...][DEADBEEF]
        ByteWriter body = new ByteWriter(256);
        body.u32(4);
        int layerFlags = tableGeomType & 0xFF;
        if (stringsAreUtf8) {
            layerFlags |= (1 << 8);
        }
        if (tableGeomType != 0) {
            layerFlags |= (1 << 9);  // "高精度存储"标志
        }
        if (hasM) {
            layerFlags |= (1 << 30);
        }
        if (hasZ) {
            layerFlags |= (1 << 31);
        }
        body.u32(layerFlags);
        body.u16(fields.size());
        for (GdbField f : fields) {
            encodeFieldDescriptor(body, f, hasZ, hasM, stringsAreUtf8, true);
        }
        body.u8(0xDE);
        body.u8(0xAD);
        body.u8(0xBE);
        body.u8(0xEF);

        ByteWriter section = new ByteWriter(body.size() + 4);
        section.u32(body.size());
        section.raw(body.internalBuffer(), 0, body.size());

        writeAt(offsetFieldDesc, section.internalBuffer(), 0, section.size());
        fieldDescLength = body.size();
    }

    /**
     * 写一个字段描述。
     *
     * <p>对应 GDAL {@code filegdbtable_write_fields.cpp::WriteFieldDescriptor}。
     *
     * @param withBbox 几何字段是否写 bbox 段。{@link #writeGeomBbox()} 需要先量出
     *                 bbox 的相对偏移,所以会用 {@code false} 走一遍
     * @return 几何字段返回 bbox 段相对 <b>body 起点</b> 的偏移,其余返回 -1
     */
    private int encodeFieldDescriptor(ByteWriter out, GdbField f, boolean tableHasZ,
                                      boolean tableHasM, boolean utf8, boolean withBbox) {
        // ⚠️ alias 按原样写,空串就写长度 0。GDAL 走的是
        // WriteUTF16String(abyBuffer, psField->GetAlias()),不会用名字兜底;
        // 真实 ArcGIS 文件里 ObjectID / Shape / NAME 这些字段的 alias 都是空的。
        // 拿名字兜底会把描述区撑大一倍。
        out.utf16Prefixed(f.getName());
        out.utf16Prefixed(f.getAlias());
        out.u8(f.getType().code());

        int flag = 0;
        if (f.isNullable()) {
            flag |= 0x01;
        }
        if (f.isRequired()) {
            flag |= 0x02;
        }
        if (f.isEditable()) {
            flag |= 0x04;
        }

        FieldType t = f.getType();
        if (t == FieldType.OBJECTID) {
            // OBJECTID 特殊:第二个字节是魔数 2,不是 flag
            out.u8(4);
            out.u8(2);
            return -1;
        }
        if (t == FieldType.GEOMETRY) {
            GdbGeomField gf = (GdbGeomField) f;
            out.u8(0);              // 未用
            out.u8(flag);
            String wkt = gf.getWkt();
            int units = Utf16.units(wkt);
            out.u16(Math.min(units, 65534) * 2);  // 字节数(不是码元数!)
            out.utf16Bytes(wkt);
            int gflag = 1;
            if (gf.isHasMOriginScaleTolerance()) {
                gflag |= (1 << 1);
            }
            if (gf.isHasZOriginScaleTolerance()) {
                gflag |= (1 << 2);
            }
            out.u8(gflag);
            out.f64(gf.getXOrigin());
            out.f64(gf.getYOrigin());
            out.f64(gf.getXyScale());
            if (gf.isHasMOriginScaleTolerance()) {
                out.f64(gf.getMOrigin());
                out.f64(gf.getMScale());
            }
            if (gf.isHasZOriginScaleTolerance()) {
                out.f64(gf.getZOrigin());
                out.f64(gf.getZScale());
            }
            out.f64(gf.getXyTolerance());
            if (gf.isHasMOriginScaleTolerance()) {
                out.f64(gf.getMTolerance());
            }
            if (gf.isHasZOriginScaleTolerance()) {
                out.f64(gf.getZTolerance());
            }
            int bboxRel = out.size();
            if (withBbox) {
                out.f64(gf.getXmin());
                out.f64(gf.getYmin());
                out.f64(gf.getXmax());
                out.f64(gf.getYmax());
                if (tableHasZ) {
                    out.f64(gf.getZmin());
                    out.f64(gf.getZmax());
                }
                if (tableHasM) {
                    out.f64(gf.getMmin());
                    out.f64(gf.getMmax());
                }
            }
            out.u8(0);              // 空间索引指示位
            double[] grids = gf.getSpatialIndexGridResolutions();
            out.u32(grids.length);
            for (double g : grids) {
                out.f64(g);
            }
            return bboxRel;
        }
        if (t == FieldType.STRING) {
            out.u32(f.getLength());
            out.u8(flag);
            writeDefault(out, f, utf8);
            return -1;
        }
        if (t == FieldType.BINARY || t == FieldType.XML) {
            out.u8(0);
            out.u8(flag);
            return -1;
        }
        if (t == FieldType.GUID || t == FieldType.GLOBALID) {
            out.u8(38);             // 带花括号的 GUID 字符串显示宽度
            out.u8(flag);
            return -1;
        }
        // 其余定长类型: u8 字节宽度 | u8 flag | 默认值
        int width;
        switch (t) {
            case INT16:
                width = 2;
                break;
            case INT32:
            case FLOAT32:
                width = 4;
                break;
            case FLOAT64:
            case DATETIME:
            case DATE:
            case TIME:
            case INT64:
                width = 8;
                break;
            case DATETIME_WITH_OFFSET:
                width = 10;
                break;
            default:
                throw new GdbWriteException("字段 " + f.getName() + ": 不支持写入的类型 " + t);
        }
        out.u8(width);
        out.u8(flag);
        writeDefault(out, f, utf8);
        return -1;
    }

    /**
     * 写默认值段:{@code u8 长度 + 内容};没有默认值时长度写 0。
     *
     * <p>对应 GDAL 里 {@code WriteUInt8(abyBuffer, 0); // size of default value}。
     */
    private void writeDefault(ByteWriter out, GdbField f, boolean utf8) {
        Object v = f.getDefaultValue();
        if (v == null) {
            out.u8(0);
            return;
        }
        switch (f.getType()) {
            case INT16:
                out.u8(2);
                out.i16(((Number) v).shortValue());
                break;
            case INT32:
                out.u8(4);
                out.i32(((Number) v).intValue());
                break;
            case FLOAT32:
                out.u8(4);
                out.f32(((Number) v).floatValue());
                break;
            case FLOAT64:
                out.u8(8);
                out.f64(((Number) v).doubleValue());
                break;
            case DATETIME:
            case DATE:
            case TIME:
                out.u8(8);
                out.f64(toDays(v));
                break;
            case INT64:
                out.u8(8);
                out.i64(((Number) v).longValue());
                break;
            case STRING: {
                byte[] raw = encodeString(v, utf8);
                out.varUInt(raw.length);
                out.raw(raw);
                break;
            }
            default:
                out.u8(0);
        }
    }

    /**
     * 把 {@link GdbFeature} 编码成记录体。
     *
     * <p>记录体布局:
     * <pre>
     *   [空值位图: ceil(nullable/8) 字节,bit=1 表示空]
     *   [各字段值,按字段顺序;OBJECTID 不占空间]
     *   [几何字段: varuint 长度 + 几何 blob]
     * </pre>
     *
     * <p>⚠️ 位图初值 0xFF(全"空"),取到值时才把对应 bit 清 0 —— 与 GDAL 的写法
     * 一致({@code m_abyBuffer[...] &= ~(1 << ...)})。
     *
     * @param feature 要素
     * @return 记录体字节
     */
    public byte[] encodeFeature(GdbFeature feature) {
        ByteWriter out = new ByteWriter(64 + nullableBitmapSize);
        for (int i = 0; i < nullableBitmapSize; i++) {
            out.u8(0xFF);
        }
        int bit = 0;
        for (GdbField f : fields) {
            boolean nullable = f.isNullable();
            if (f.isObjectId()) {
                // OID 不进记录体,但它的 nullable 位仍占一格
                if (nullable) {
                    clearBit(out, bit);
                    bit++;
                }
                continue;
            }

            Object value;
            if (f.isGeometry()) {
                value = feature.getGeometry();
            } else if (feature.hasAttribute(f.getName())) {
                value = feature.getAttribute(f.getName());
            } else {
                value = f.getDefaultValue();
            }
            if (value == null && !nullable) {
                // 非空字段必须落一个值:用该类型的零值顶上
                value = zeroValue(f);
            }
            if (nullable) {
                if (value == null) {
                    bit++;   // 位图里保持 1(=空),记录体里不占字节
                    continue;
                }
                clearBit(out, bit);
                bit++;
            }

            if (f.isGeometry()) {
                encodeGeometryField(out, (GdbGeometry) value);
            } else {
                encodeFieldValue(out, f, value);
            }
        }
        return out.toByteArray();
    }

    private static void clearBit(ByteWriter out, int bit) {
        byte[] buf = out.internalBuffer();
        buf[bit >> 3] &= (byte) ~(1 << (bit & 7));
    }

    /**
     * 几何字段 = {@code varuint 字节长度} + 几何 blob。
     *
     * <p>对应 {@code EncodeFeature} 里
     * {@code WriteVarUInt(m_abyBuffer, m_abyGeomBuffer.size())} 那段;
     * blob 本身由 {@link FileGdbGeometryCodec#encode} 生成。
     */
    private void encodeGeometryField(ByteWriter out, GdbGeometry geom) {
        byte[] blob = FileGdbGeometryCodec.encode(geom, geomField, hasZ, hasM, true);
        out.varUInt(blob.length);
        out.raw(blob);

        // 顺带维护几何字段的全表包围盒(对应 GDAL 的 m_bDirtyGeomFieldBBox)。
        // 内存里立刻更新 geomField 上的值 —— GDAL 也是这样(包围盒对象当场改,
        // 只有落盘推迟到 Sync)。
        if (geom != null && !geom.isEmpty()) {
            double[] env = geom.envelope();
            if (env == null) {
                return;
            }
            if (bbox == null) {
                bbox = new double[]{env[0], env[1], env[2], env[3]};
            } else {
                bbox[0] = Math.min(bbox[0], env[0]);
                bbox[1] = Math.min(bbox[1], env[1]);
                bbox[2] = Math.max(bbox[2], env[2]);
                bbox[3] = Math.max(bbox[3], env[3]);
            }
            if (geomField != null) {
                geomField.setExtent(bbox[0], bbox[1], bbox[2], bbox[3]);
            }
            dirtyGeomBbox = true;
        }
    }

    /** 写一个非几何字段的值。对应 {@code EncodeFeature} 的主 switch。 */
    private void encodeFieldValue(ByteWriter out, GdbField f, Object value) {
        switch (f.getType()) {
            case INT16:
                out.i16(numberValue(value).shortValue());
                break;
            case INT32:
                out.i32(numberValue(value).intValue());
                break;
            case INT64:
                out.i64(numberValue(value).longValue());
                break;
            case FLOAT32:
                out.f32(numberValue(value).floatValue());
                break;
            case FLOAT64:
                out.f64(numberValue(value).doubleValue());
                break;
            case DATETIME:
            case DATE:
            case TIME:
                out.f64(toDays(value));
                break;
            case GUID:
            case GLOBALID:
                out.raw(GuidUtil.parse(String.valueOf(value)));
                break;
            case STRING:
            case XML: {
                byte[] raw = encodeString(value, stringsAreUtf8);
                out.varUInt(raw.length);
                out.raw(raw);
                break;
            }
            case BINARY: {
                byte[] raw = (byte[]) value;
                out.varUInt(raw.length);
                out.raw(raw);
                break;
            }
            case DATETIME_WITH_OFFSET: {
                Object[] pair = (Object[]) value;
                out.f64(toDays(pair[0]));
                out.i16(((Number) pair[1]).shortValue());
                break;
            }
            default:
                throw new GdbWriteException("字段 " + f.getName() + ": 不支持写入的类型 " + f.getType());
        }
    }

    private static Number numberValue(Object v) {
        if (v instanceof Number) {
            return (Number) v;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1 : 0;
        }
        throw new GdbWriteException("期望数值,实得 " + v.getClass().getSimpleName() + ": " + v);
    }

    /** 非空字段遇到 {@code null} 时顶上去的类型零值(对应 GDAL 的 memset 0)。 */
    private static Object zeroValue(GdbField f) {
        switch (f.getType()) {
            case INT16:
            case INT32:
            case INT64:
                return 0L;
            case FLOAT32:
            case FLOAT64:
                return 0.0;
            case DATETIME:
            case DATE:
            case TIME:
                return 0.0;
            case DATETIME_WITH_OFFSET:
                return new Object[]{0.0, 0};
            case GUID:
            case GLOBALID:
                return "00000000-0000-0000-0000-000000000000";
            case BINARY:
                return new byte[0];
            default:
                return "";
        }
    }

    /** {@code datetime -> FileGDB 天数(double)}。 */
    private static double toDays(Object value) {
        if (value instanceof LocalDateTime) {
            return GdbDateTimeUtil.dateTimeToDays((LocalDateTime) value);
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        throw new GdbWriteException("期望日期时间或天数,实得 " + value);
    }

    /** 字符串 → 记录体里的原始字节(不含 varuint 长度前缀)。 */
    private static byte[] encodeString(Object value, boolean utf8) {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        String text = value instanceof String ? (String) value : String.valueOf(value);
        return utf8
                ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : Utf16.encode(text);
    }

    /**
     * 追加一条要素,返回 OID(从 1 开始)。
     *
     * <p>对应 GDAL {@code FileGDBTable::CreateFeature}。OID 不进记录体,而是由
     * {@code .gdbtablx} 的槽位下标({@code OID - 1})隐式承载,所以写入顺序即 OID 顺序。
     *
     * <p>⚠️ 这里 <b>不落盘头部</b>:记录体当场写,索引只就地把这一行覆盖掉
     * ({@link GdbTableIndex#writeOffsetInPlace(int)},对应 GDAL
     * {@code WriteFeatureOffset}),计数/文件大小/索引头由 {@link #sync()} 统一写。
     *
     * @param feature 要素
     * @return 新要素的 OID
     */
    public long appendFeature(GdbFeature feature) {
        if (channel == null || tablx == null) {
            throw new GdbWriteException("表不是以可写方式打开的");
        }
        byte[] blob = encodeFeature(feature);
        long offset = fileSize;
        ByteWriter rec = new ByteWriter(blob.length + 4);
        rec.u32(blob.length);
        rec.raw(blob);
        writeAt(offset, rec.internalBuffer(), 0, rec.size());
        fileSize = offset + 4 + blob.length;

        if (blob.length > headerBufferMaxSize) {
            headerBufferMaxSize = blob.length;
        }
        long oid = tablx.recordCount() + 1L;
        tablx.setOffset((int) (oid - 1), offset);
        tablx.writeOffsetInPlace((int) (oid - 1));
        validRecordCount++;
        dirty = true;
        feature.setOid(oid);
        return oid;
    }

    /**
     * 就地更新一条要素。
     *
     * <p>对应 GDAL {@code FileGDBTable::UpdateFeature}:新记录体 <b>不大于</b> 旧的时候
     * 原地重写并用 0 填满尾部;变大了才追加到文件末尾、把旧槽标记为已删除。
     *
     * @param feature 要素(必须已带 OID)
     */
    public void updateFeature(GdbFeature feature) {
        if (channel == null || tablx == null) {
            throw new GdbWriteException("表不是以可写方式打开的");
        }
        int row = (int) (feature.getOid() - 1);
        long offset = tablx.offsetForRow(row);
        if (offset == 0) {
            throw new GdbWriteException("OID " + feature.getOid() + " 不存在(该槽已删除)");
        }
        byte[] head = readFully(offset, 4);
        long oldLen = LittleEndian.getUInt32(head, 0);
        if ((oldLen & 0x80000000L) != 0) {
            throw new GdbWriteException("OID " + feature.getOid() + " 已被删除");
        }
        byte[] blob = encodeFeature(feature);
        dirty = true;

        if (blob.length <= oldLen) {
            ByteWriter rec = new ByteWriter(blob.length + 4);
            rec.u32(blob.length);
            rec.raw(blob);
            byte[] tail = new byte[(int) (oldLen - blob.length)];
            writeAt(offset, rec.internalBuffer(), 0, rec.size());
            writeAt(offset + 4 + blob.length, tail, 0, tail.length);
            if (blob.length > headerBufferMaxSize) {
                headerBufferMaxSize = blob.length;
            }
            return;
        }
        // 变大:追加新的,旧槽置为"已删除"(长度取负)
        long newOffset = fileSize;
        ByteWriter rec = new ByteWriter(blob.length + 4);
        rec.u32(blob.length);
        rec.raw(blob);
        writeAt(newOffset, rec.internalBuffer(), 0, rec.size());
        fileSize = newOffset + 4 + blob.length;

        ByteWriter neg = new ByteWriter(4);
        neg.u32((-oldLen) & 0xFFFFFFFFL);
        writeAt(offset, neg.internalBuffer(), 0, 4);

        tablx.setOffset(row, newOffset);
        tablx.writeOffsetInPlace(row);
        if (blob.length > headerBufferMaxSize) {
            headerBufferMaxSize = blob.length;
        }
    }

    /**
     * 删除一条要素(逻辑删除)。
     *
     * <p>对应 GDAL {@code FileGDBTable::DeleteFeature}:{@code .gdbtablx} 槽位写 0,
     * {@code .gdbtable} 里的长度字 <b>取负</b>。二者合起来让任何读取方都能把该槽判为空。
     * 索引空间不回收(不维护 {@code .freelist})。
     *
     * @param oid 要素 ID
     * @return 是否真的删掉了一条
     */
    public boolean deleteFeature(long oid) {
        if (channel == null || tablx == null) {
            throw new GdbWriteException("表不是以可写方式打开的");
        }
        int row = (int) (oid - 1);
        long offset = tablx.offsetForRow(row);
        if (offset == 0) {
            return false;
        }
        byte[] head = readFully(offset, 4);
        long oldLen = LittleEndian.getUInt32(head, 0);
        if ((oldLen & 0x80000000L) != 0) {
            return false;
        }
        ByteWriter neg = new ByteWriter(4);
        neg.u32((-oldLen) & 0xFFFFFFFFL);
        writeAt(offset, neg.internalBuffer(), 0, 4);
        tablx.setOffset(row, 0);
        tablx.writeOffsetInPlace(row);
        validRecordCount--;
        dirty = true;
        return true;
    }

    /**
     * 把头部、几何包围盒与 {@code .gdbtablx} 刷到磁盘。
     *
     * <p>对应 GDAL {@code FileGDBTable::Sync}:字段描述区的包围盒、主头计数与文件大小、
     * 索引头与 trailer 都在这里一次写完;
     * <p>没有挂起改动时 <b>空转</b>(幂等,反复调不花代价)。
     */
    public void sync() {
        if (channel == null) {
            return;
        }
        if (!dirty && !dirtyGeomBbox) {
            return;
        }
        if (dirtyGeomBbox && geomField != null) {
            writeGeomBbox();
            dirtyGeomBbox = false;
        }
        writeHeader();
        if (tablx != null) {
            tablx.flush();
        }
        try {
            channel.force(false);
        } catch (IOException ignored) {
            // force 失败不影响数据正确性,忽略
        }
        LOG.debug("表 {} 已 sync: {} 条有效记录 / {} 个槽位,文件 {} 字节",
                basename, validRecordCount, getRecordCount(), fileSize);
        dirty = false;
    }

    /**
     * 把几何字段的全表包围盒回填进字段描述区。
     *
     * <p>对应 GDAL {@code Sync} 里用 {@code m_nGeomFieldBBoxSubOffset} 就地改写的那段。
     * 为了不改动偏移,这里按字段描述区的结构 <b>重新定位</b> 一次几何字段的 bbox 起点。
     *
     * <p>一条几何都没写过时写 ESRI 的空范围 NaN —— 与 ArcGIS 对空要素类的写法一致。
     * 注意用的是 {@link GdbDateTimeUtil#ESRI_NAN},不是 {@link Double#NaN}:
     * 两者只差最低有效位,但 ArcGIS 用后者会让对比工具报差异。
     */
    private void writeGeomBbox() {
        GdbGeomField gf = geomField;
        if (gf == null || channel == null) {
            return;
        }
        // 重新构造一遍字段描述区,量出 bbox 的相对偏移
        ByteWriter probe = new ByteWriter(256);
        probe.u32(4);
        probe.u32(0);
        probe.u16(fields.size());
        int bboxRel = -1;
        for (int i = 0; i < fields.size(); i++) {
            GdbField f = fields.get(i);
            if (i == geomFieldIndex) {
                bboxRel = encodeFieldDescriptor(probe, f, hasZ, hasM, stringsAreUtf8, false);
            } else {
                encodeFieldDescriptor(probe, f, hasZ, hasM, stringsAreUtf8, true);
            }
        }
        if (bboxRel < 0) {
            return;
        }
        double nan = GdbDateTimeUtil.ESRI_NAN;
        double[] b = bbox != null ? bbox : new double[]{nan, nan, nan, nan};
        gf.setExtent(b[0], b[1], b[2], b[3]);

        ByteWriter out = new ByteWriter(32);
        out.f64(b[0]);
        out.f64(b[1]);
        out.f64(b[2]);
        out.f64(b[3]);
        if (hasZ) {
            out.f64(gf.getZmin());
            out.f64(gf.getZmax());
        }
        if (hasM) {
            out.f64(gf.getMmin());
            out.f64(gf.getMmax());
        }
        // bboxRel 是相对 body 起点的偏移;body 前面还有 4 字节的区长度
        long pos = offsetFieldDesc + 4 + bboxRel;
        writeAt(pos, out.internalBuffer(), 0, out.size());
    }

    /**
     * 落盘并关闭。
     *
     * <p>{@link AutoCloseable} 的实现,可直接用于 try-with-resources。
     */
    @Override
    public void close() {
        try {
            sync();
        } finally {
            if (tablx != null) {
                tablx.close();
            }
            closeQuietly();
        }
    }

    private void closeQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭失败无需上抛
            }
            channel = null;
        }
    }

    // ==================================================================
    // 底层 IO(位置读写,线程安全)
    // ==================================================================

    /** 从指定位置读取 {@code len} 字节;不足时返回实际读到的字节数。 */
    private byte[] readFully(long pos, int len) {
        if (len <= 0) {
            return new byte[0];
        }
        ByteBuffer bb = ByteBuffer.allocate(len);
        int total = 0;
        try {
            while (total < len) {
                int n = channel.read(bb, pos + total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (IOException e) {
            throw new GdbIoException("读取 " + basename + " 失败(pos=" + pos + "): "
                    + e.getMessage(), e);
        }
        return total == len ? bb.array() : Arrays.copyOf(bb.array(), total);
    }

    /** 在指定位置写入一段字节。 */
    private void writeAt(long pos, byte[] buf, int off, int len) {
        if (channel == null) {
            throw new GdbWriteException("表不是以可写方式打开的");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        try {
            while (bb.hasRemaining()) {
                channel.write(bb, pos + (len - bb.remaining()));
            }
        } catch (IOException e) {
            throw new GdbIoException("写入 " + basename + " 失败(pos=" + pos + "): "
                    + e.getMessage(), e);
        }
    }

    private long fileSizeOnDisk() {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new GdbIoException("无法获取 " + basename + " 的大小: " + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "<GdbTable " + basename + " v" + version + " fields=" + fields.size()
                + " records=" + getRecordCount() + " geom=" + getGeometryType().ogrName() + ">";
    }
}
