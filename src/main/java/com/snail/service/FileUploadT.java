package com.snail.service;


import com.snail.entity.FileItemDto;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


/**
 * 接口调用示例
 */
@Service
public class FileUploadT {

  private static final Logger logger = LoggerFactory.getLogger(FileUploadT.class);
  private final FileServer fileServer;
  private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
  private static final long FILE_SIZE = 1024 * 1024 ; // 1MB
  public FileUploadT(FileServer fileServer) {
    this.fileServer = fileServer;
  }

  /**
   * 用户登录状态下：上传文件流，返回文件文件元数据
   *
   * @param stream   文件流
   * @param filename 文件名称
   * @return FileItemDto 文件元数据
   */
  public FileItemDto uploadFile(InputStream stream, String filename) throws Exception {
    return fileServer.uploadRequest(stream, filename);
  }

  /**
   * 用户登录状态下：上传文件流，返回文件文件元数据
   *
   * @param stream   文件流
   * @param filename 文件名称
   * @return FileItemDto 文件元数据
   */
  public FileItemDto uploadTestFile(InputStream stream, String filename) throws Exception {
    return fileServer.uploadTestRequest(stream, filename);
  }


  /**
   * 用户登录状态下：大文件分片上传文件流，返回文件文件元数据
   *
   * @param stream   文件流
   * @param chunk    当前片
   * @param chunks   总片数
   * @param uuid     UUID
   * @param filename 文件名称 带后缀
   * @return FileItemDto 文件元数据
   */
  public FileItemDto uploadFile(InputStream stream, int chunk, int chunks, String uuid,
      String filename) throws Exception {
    return fileServer.uploadSplitRequest(stream, chunk, chunks, uuid, filename);
  }


  /**
   * 根据文件id,获取文件流
   *
   * @param fileId 文件id
   * @return InputStream 文件流
   */
  public InputStream downloadFile(Long fileId  ,String  uuidStr) throws Exception {
    return fileServer.downloadRequest(fileId ,uuidStr);
  }

  /**
   * 根据文件id,删除文件
   *
   * @param fileId
   * @throws IOException
   */
  public void deleteFile(Long fileId) throws Exception {
    fileServer.deleteRequest(fileId);
  }


  /**
   * 模拟对文件进行分片处理上传示例（仅供参考）
   *
   * @param file 文件
   * @return FileItemDto  文件元数据信息
   */
  public FileItemDto uploadFileInChunks(File file , int splitSize) throws Exception {
    // 生成唯一标识和基础信息
    String uuid = UUID.randomUUID().toString();
    String filename = file.getName();
    long fileSize = file.length();
    Long maxFileSize = MAX_FILE_SIZE;
    if (splitSize > 0 ) {
      maxFileSize = splitSize * FILE_SIZE;
    }
    // 计算分片总数
    int totalChunks = (int) Math.ceil((double) fileSize / maxFileSize);
    logger.info("文件: {} (大小: {})，将被分为 {} 个分片",
        filename, formatFileSize(fileSize), totalChunks);

    FileItemDto result = null;
    int currentChunk = 0;

    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      // 逐个分片处理
      while (currentChunk < totalChunks) {
        // 计算当前分片位置和大小
        long position = (long) currentChunk * maxFileSize;
        long remaining = fileSize - position;
        int chunkSize = (int) Math.min(maxFileSize, remaining);

        // 读取分片数据
        byte[] buffer = new byte[chunkSize];
        raf.seek(position);
        raf.readFully(buffer);

        // 创建分片输入流
        try (InputStream chunkStream = new ByteArrayInputStream(buffer)) {
          // 上传当前分片
          logger.info("上传分片 {}/{} (大小: {})",
              currentChunk + 1, totalChunks, formatFileSize(chunkSize));

          result = this.uploadFile(chunkStream, currentChunk, totalChunks, uuid, filename);

          // 检查上传结果
          if (result != null) {
            logger.error("分片 {} 上传成功", currentChunk + 1);
            return result; // 返回错误结果
          }
        }
        currentChunk++;
      }
    }

    logger.info("文件 {} 全部分片上传完成", filename);
    return result != null ? result : new FileItemDto();
  }

  /**
   * 格式化文件大小
   */
  private String formatFileSize(long size) {
    if (size < 1024) {
      return size + " B";
    }
    if (size < 1024 * 1024) {
      return String.format("%.1f KB", size / 1024.0);
    }
    if (size < 1024 * 1024 * 1024) {
      return String.format("%.1f MB", size / (1024.0 * 1024));
    }
    return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
  }
}
