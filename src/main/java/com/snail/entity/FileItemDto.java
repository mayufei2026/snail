package com.snail.entity;


import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

/**
 * 文件项对象
 *
 * @author wgong
 */
@Data
public class FileItemDto implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  /** 文件项Id */
  private String id;

  /** 已过时，为了兼容前端获取，等同于文件项Id */
  @Deprecated
  private String originalFileId;

  /** 文件名称 */
  private String fileName;

  /** 文件项类型标识符 */
  private String categoryIdentifier;

  /** 文件大小 */
  private Long fileSize;

  private String contentType;

  private String md5;
}
