package com.gdal.openfilegdb.exception;

/**
 * 二进制格式与预期不符:头部损坏、偏移越界、长度字段不自洽等。
 *
 * <p>对标 GDAL 中 {@code FileGDBTable::Open()} 等处对结构做校验后
 * 返回 {@code FALSE} 并报 {@code CPLE_AppDefined} 的场景
 * (见 {@code filegdbtable.cpp::FileGDBTable::Open}、{@code ::SelectRow})。
 */
public class GdbFormatException extends OpenFileGdbException {

    private static final long serialVersionUID = 1L;

    public GdbFormatException(String message) {
        super(message);
    }

    public GdbFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
