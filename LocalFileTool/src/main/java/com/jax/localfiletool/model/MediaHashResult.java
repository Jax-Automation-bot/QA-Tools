package com.jax.localfiletool.model;

import lombok.Builder;
import lombok.Data;

/**
 * 单个媒体文件处理前后的 MD5 / SHA-256 对比结果。
 */
@Data
@Builder
public class MediaHashResult {

    /** 文件绝对路径 */
    private String filePath;

    /** 文件类型：IMAGE / ANIMATION / VIDEO / AUDIO */
    private String type;

    /** 处理前文件大小（字节） */
    private long oldSize;

    /** 处理后文件大小（字节） */
    private long newSize;

    /** 处理前 MD5 */
    private String oldMd5;

    /** 处理后 MD5 */
    private String newMd5;

    /** 处理前 SHA-256 */
    private String oldSha256;

    /** 处理后 SHA-256 */
    private String newSha256;

    /** 是否成功变更 */
    private boolean changed;

    /** 失败原因（成功时为 null） */
    private String error;
}
