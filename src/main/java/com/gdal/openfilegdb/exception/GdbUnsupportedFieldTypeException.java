package com.gdal.openfilegdb.exception;

/**
 * 遇到本实现（或 GDAL OpenFileGDB）不支持的字段类型。
 *
 * <p>GDAL 在 {@code filegdb_gdbtoogrfieldtype.h} 里把 {@code FGFT_*}
 * 映射到 {@code OGRFieldType} 与 {@code OGRFieldSubType};对未覆盖的类型
 * 会退化成 {@code OFTString} 或直接跳过。本工程对无法安全往返的类型
 * (例如记录体内为未知编码)显式抛本异常,避免静默产生错误数据。
 */
public class GdbUnsupportedFieldTypeException extends OpenFileGdbException {

    private static final long serialVersionUID = 1L;

    public GdbUnsupportedFieldTypeException(String message) {
        super(message);
    }

    public GdbUnsupportedFieldTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
