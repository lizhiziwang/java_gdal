package com.gdal.openfilegdb.exception;

/**
 * FileGDB 版本不受支持。
 *
 * <p>文件第一个 int32 即版本号:{@code 3} 表示 ArcGIS 10.x(本工程主要目标,
 * 可读可写),{@code 4} 表示较新的 ArcGIS Pro(仅读取)。
 * 其它取值(例如 9.x)一律抛本异常。
 *
 * <p>对标 GDAL {@code FileGDBTable::Open()} 中对 {@code m_nVersion} 的判定,
 * 以及 {@code ogropenfilegdbdatasource_write.cpp} 在 {@code bUpdate} 时
 * 对 version 4 拒绝写入的处理。
 */
public class GdbVersionException extends OpenFileGdbException {

    private static final long serialVersionUID = 1L;

    public GdbVersionException(String message) {
        super(message);
    }
}
