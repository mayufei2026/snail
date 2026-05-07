package com.snail.service;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件生成工具类 - 优化版
 * 支持生成大文件（包括1GB），避免内存溢出
 */
@Service
public class FileGenerator {

  private static final Logger logger = LoggerFactory.getLogger(FileGenerator.class);

  // 内存限制：单次缓冲区最大大小
  private static final int MAX_BUFFER_SIZE = 8 * 1024 * 1024; // 8MB缓冲区
  private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024; // 1MB默认缓冲区

  // 进度监控阈值
  private static final int PROGRESS_INTERVAL_MB = 100; // 每100MB输出一次进度

  // 取消控制
  private final AtomicBoolean isCancelled = new AtomicBoolean(false);

  /**
   * 主方法，用于测试
   */
  public void createFile(String filePath) {
    try {
      // 指定文件生成目录
      //String targetDirectory = "D:\\data\\test_files"; // Linux路径
      String targetDirectory = "/data/test_files"; // Linux路径

      if (StringUtils.isNotBlank(filePath)) {
        targetDirectory = filePath;
      }

      logger.info("开始生成测试文件...");
      logger.info("文件将保存到: {}", targetDirectory);

      // 安全检查
      if (!checkDiskSpace(targetDirectory, 2L * 1024 * 1024 * 1024)) { // 2GB预留
        logger.error("磁盘空间不足，无法生成文件");
        return;
      }

      // 生成所有文件
      generateAllFiles(targetDirectory);

      logger.info("所有文件生成完成！");

    } catch (IOException e) {
      logger.error("生成文件时发生错误", e);
      // 不打印堆栈跟踪到控制台，只记录日志
    } catch (Exception e) {
      logger.error("未知错误", e);
    }
  }

  /**
   * 检查磁盘空间
   */
  private boolean checkDiskSpace(String directoryPath, long requiredSize) throws IOException {
    Path path = Paths.get(directoryPath);
    FileStore store = Files.getFileStore(path);
    long freeSpace = store.getUsableSpace();

    logger.info("磁盘空闲空间: {} MB", freeSpace / (1024 * 1024));
    logger.info("需要空间: {} MB", requiredSize / (1024 * 1024));

    return freeSpace > requiredSize * 1.2; // 多留20%空间
  }

  /**
   * 生成指定大小的文件（安全版本）
   */
  public static void generateFile(String directoryPath, long sizeInBytes, String unit)
      throws IOException {
    // 创建目录（如果不存在）
    Path dirPath = Paths.get(directoryPath);
    if (!Files.exists(dirPath)) {
      Files.createDirectories(dirPath);
    }

    // 生成文件名
    String sizeStr = formatSizeString(sizeInBytes);
    String fileName = sizeStr + ".txt";
    Path filePath = dirPath.resolve(fileName);

    logger.info("正在生成文件: {} (大小: {})", fileName, formatBytes(sizeInBytes));

    long startTime = System.currentTimeMillis();

    try {
      // 根据文件大小选择不同的生成策略
      if (sizeInBytes > 500 * 1024 * 1024) { // 大于500MB，使用NIO直接写入
        generateHugeFileNIO(filePath, sizeInBytes);
      } else if (sizeInBytes > 10 * 1024 * 1024) { // 10MB-500MB，使用缓冲写入
        generateLargeFileBuffered(filePath, sizeInBytes);
      } else if (sizeInBytes > 1024 * 1024) { // 1MB-10MB
        generateLargeFile(filePath, sizeInBytes);
      } else {
        generateSmallFile(filePath, sizeInBytes);
      }

      // 验证文件大小
      long actualSize = Files.size(filePath);
      if (actualSize != sizeInBytes) {
        logger.warn("文件大小不匹配，进行调整: 实际 {} vs 目标 {}",
            formatBytes(actualSize), formatBytes(sizeInBytes));
        adjustFileSize(filePath, sizeInBytes);
      }

      long duration = System.currentTimeMillis() - startTime;

      logger.info("文件生成成功: {} (实际大小: {}, 耗时: {} ms)",
          fileName, formatBytes(actualSize), duration);

    } catch (IOException e) {
      // 如果失败，删除可能生成的不完整文件
      Files.deleteIfExists(filePath);
      throw e;
    }
  }

  /**
   * 生成超大文件（>500MB） - 使用NIO FileChannel
   */
  private static void generateHugeFileNIO(Path filePath, long sizeInBytes)
      throws IOException {

    logger.debug("使用NIO FileChannel生成超大文件");

    // 预分配磁盘空间
    try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
      raf.setLength(sizeInBytes);
    }

    // 使用FileChannel进行高效写入
    try (FileChannel channel = FileChannel.open(filePath,
        StandardOpenOption.WRITE)) {

      // 使用直接缓冲区，避免堆内存压力
      ByteBuffer buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE);
      byte[] dataPattern = createDataPattern(DEFAULT_BUFFER_SIZE);

      long written = 0;
      int progressCounter = 0;

      while (written < sizeInBytes) {
        // 填充缓冲区
        buffer.clear();
        int writeSize = (int) Math.min(sizeInBytes - written, DEFAULT_BUFFER_SIZE);

        // 直接使用数组填充缓冲区
        buffer.put(dataPattern, 0, writeSize);
        buffer.flip();

        // 写入文件
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }

        written += writeSize;
        progressCounter += writeSize;

        // 输出进度
        if (progressCounter >= PROGRESS_INTERVAL_MB * 1024 * 1024) {
          int progress = (int) ((written * 100) / sizeInBytes);
          logger.info("生成进度: {}% (已写入: {})",
              progress, formatBytes(written));
          progressCounter = 0;
        }

        // 定期检查内存
        if ((written / DEFAULT_BUFFER_SIZE) % 100 == 0) {
          checkAndReleaseMemory();
        }
      }
    }
  }

  /**
   * 生成大文件（10MB-500MB） - 使用缓冲写入
   */
  private static void generateLargeFileBuffered(Path filePath, long sizeInBytes)
      throws IOException {

    logger.debug("使用缓冲写入生成大文件");

    int bufferSize = Math.min(MAX_BUFFER_SIZE, (int) Math.min(sizeInBytes, 4 * 1024 * 1024));
    byte[] buffer = createDataPattern(bufferSize);

    try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
        BufferedOutputStream bos = new BufferedOutputStream(fos, bufferSize)) {

      long remaining = sizeInBytes;

      while (remaining > 0) {
        int writeSize = (int) Math.min(remaining, bufferSize);
        bos.write(buffer, 0, writeSize);
        remaining -= writeSize;

        // 每写入一定数据后flush一次
        if (remaining % (10 * 1024 * 1024) == 0) {
          bos.flush();
        }
      }
    }
  }

  /**
   * 生成大文件（1MB-10MB） - 使用字符缓冲
   */
  private static void generateLargeFile(Path filePath, long sizeInBytes)
      throws IOException {

    logger.debug("使用字符缓冲生成文件");

    // 使用字符缓冲区，但避免一次创建太大的StringBuilder
    int bufferSize = Math.min(64 * 1024, (int) sizeInBytes); // 最大64KB
    char[] buffer = new char[bufferSize];
    fillBuffer(buffer);

    try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
      long remaining = sizeInBytes;
      long charCount = 0;

      // 估算字符数：UTF-8编码，英文字符1字节，中文字符3字节
      // 这里使用简单的估算，实际可能需要调整
      while (charCount * 2 < remaining) { // 保守估计，每个字符平均2字节
        int writeSize = (int) Math.min(buffer.length, remaining - charCount);
        writer.write(buffer, 0, Math.min(writeSize, buffer.length));
        charCount += Math.min(writeSize, buffer.length);
      }

      // 如果还差一些字节，用空格填充
      if (Files.size(filePath) < sizeInBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
          raf.setLength(sizeInBytes);
        }
      }
    }
  }

  /**
   * 生成小文件（<1MB）
   */
  private static void generateSmallFile(Path filePath, long sizeInBytes) throws IOException {
    // 使用StringBuilder但控制大小
    int safeSize = (int) Math.min(sizeInBytes, 1024 * 1024);
    StringBuilder content = new StringBuilder(safeSize);

    // 填充内容
    for (int i = 0; i < safeSize; i++) {
      char c = (char) ('a' + (i % 26));
      if (i % 52 < 26) {
        c = (char) ('a' + (i % 26));
      } else if (i % 78 < 52) {
        c = (char) ('A' + (i % 26));
      } else {
        c = (char) ('0' + (i % 10));
      }
      content.append(c);

      if ((i + 1) % 100 == 0 && (i + 1) < safeSize) {
        content.append(System.lineSeparator());
        i += System.lineSeparator().length() - 1;
      }
    }

    // 如果内容不够，扩展
    while (content.length() < sizeInBytes) {
      content.append(' ');
    }

    // 如果内容太长，截断
    if (content.length() > sizeInBytes) {
      content.setLength((int) sizeInBytes);
    }

    Files.writeString(filePath, content.toString(), StandardCharsets.UTF_8);
  }

  /**
   * 创建数据模式
   */
  private static byte[] createDataPattern(int size) {
    byte[] pattern = new byte[size];
    String text = "This is a test file for file size generation. ";
    text += "这是一个测试文件，用于测试文件大小生成功能。";
    text += "1234567890";

    byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

    for (int i = 0; i < size; i++) {
      pattern[i] = textBytes[i % textBytes.length];
    }

    return pattern;
  }

  /**
   * 填充字符缓冲区
   */
  private static void fillBuffer(char[] buffer) {
    String text = "这是一个测试文件，用于测试文件大小生成功能。";
    text += "文件内容包含中英文字符和数字1234567890。";
    text += "This is a test file for file size generation.";

    char[] textChars = text.toCharArray();
    for (int i = 0; i < buffer.length; i++) {
      buffer[i] = textChars[i % textChars.length];
    }
  }

  /**
   * 检查并释放内存
   */
  private static void checkAndReleaseMemory() {
    Runtime runtime = Runtime.getRuntime();
    long usedMemory = runtime.totalMemory() - runtime.freeMemory();
    long maxMemory = runtime.maxMemory();

    if ((double) usedMemory / maxMemory > 0.8) {
      logger.debug("内存使用率超过80%，尝试释放内存");
      System.gc();

      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * 调整文件大小
   */
  private static void adjustFileSize(Path filePath, long targetSize) throws IOException {
    long actualSize = Files.size(filePath);

    if (actualSize == targetSize) {
      return;
    }

    logger.debug("调整文件大小: {} -> {}", formatBytes(actualSize), formatBytes(targetSize));

    try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
      if (actualSize < targetSize) {
        // 追加内容
        raf.seek(actualSize);
        byte[] fillData = new byte[1024];
        Arrays.fill(fillData, (byte) ' ');

        long remaining = targetSize - actualSize;
        while (remaining > 0) {
          int writeSize = (int) Math.min(remaining, fillData.length);
          raf.write(fillData, 0, writeSize);
          remaining -= writeSize;
        }
      } else {
        // 截断文件
        raf.setLength(targetSize);
      }
    }
  }

  /**
   * 批量生成所有指定大小的文件
   */
  public void generateAllFiles(String directoryPath) throws IOException {
    // 定义要生成的文件大小
    int[] sizes = {100};
    String[] units = {"byte", "KB", "MB"};
    //String[] units = {"MB"};

    // 检查内存状态
    logger.info("开始生成前内存状态: {}", getMemoryInfo());

    for (int size : sizes) {
      for (String unit : units) {
        if (isCancelled.get()) {
          logger.info("文件生成被取消");
          return;
        }

        long sizeInBytes = convertToBytes(size, unit);

        // 对于GB级别文件，进行额外检查
        if ("GB".equalsIgnoreCase(unit)) {
          if (!checkMemoryForLargeFile(sizeInBytes)) {
            logger.warn("内存可能不足，跳过生成 {} {} 文件", size, unit);
            continue;
          }
        }

        try {
          generateFile(directoryPath, sizeInBytes, unit);

          // 生成大文件后检查内存
          if (sizeInBytes > 100 * 1024 * 1024) {
            logger.info("生成大文件后内存状态: {}", getMemoryInfo());
          }

        } catch (IOException e) {
          logger.error("生成文件失败: {} {}", size, unit, e);
          // 继续生成其他文件
        } catch (OutOfMemoryError e) {
          logger.error("内存溢出，无法生成 {} {} 文件", size, unit);
          // 尝试恢复
          System.gc();
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }

    logger.info("所有文件生成完成，最终内存状态: {}", getMemoryInfo());
  }

  /**
   * 检查是否有足够内存生成大文件
   */
  private boolean checkMemoryForLargeFile(long fileSize) {
    Runtime runtime = Runtime.getRuntime();
    long maxMemory = runtime.maxMemory();
    long usedMemory = runtime.totalMemory() - runtime.freeMemory();
    long availableMemory = maxMemory - usedMemory;

    // 需要的内存估算：文件大小/10（缓冲区）+ 额外开销
    long requiredMemory = Math.min(fileSize / 10, 200 * 1024 * 1024) + 50 * 1024 * 1024;

    boolean hasEnoughMemory = availableMemory > requiredMemory;

    if (!hasEnoughMemory) {
      logger.warn("内存可能不足 - 可用: {}, 需要: {}",
          formatBytes(availableMemory), formatBytes(requiredMemory));
    }

    return hasEnoughMemory;
  }

  /**
   * 获取内存信息
   */
  private String getMemoryInfo() {
    Runtime runtime = Runtime.getRuntime();
    return String.format("最大: %s, 已分配: %s, 已使用: %s, 空闲: %s",
        formatBytes(runtime.maxMemory()),
        formatBytes(runtime.totalMemory()),
        formatBytes(runtime.totalMemory() - runtime.freeMemory()),
        formatBytes(runtime.freeMemory())
    );
  }

  /**
   * 将大小转换为字节
   */
  private static long convertToBytes(int size, String unit) {
    switch (unit.toUpperCase()) {
      case "BYTE":
        return size;
      case "KB":
        return size * 1024L;
      case "MB":
        return size * 1024L * 1024L;
      case "GB":
        return size * 1024L * 1024L * 1024L;
      default:
        throw new IllegalArgumentException("不支持的单位: " + unit);
    }
  }

  /**
   * 格式化大小字符串
   */
  private static String formatSizeString(long sizeInBytes) {
    if (sizeInBytes < 1024) {
      return sizeInBytes + "byte";
    } else if (sizeInBytes < 1024 * 1024) {
      return (sizeInBytes / 1024) + "KB";
    } else if (sizeInBytes < 1024 * 1024 * 1024) {
      return (sizeInBytes / (1024 * 1024)) + "MB";
    } else {
      return (sizeInBytes / (1024 * 1024 * 1024)) + "GB";
    }
  }

  /**
   * 格式化字节数为可读字符串
   */
  private static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }

  /**
   * 生成指定大小的文件（简化接口）
   */
  public static void generateFile(String directoryPath, int size, String unit) throws IOException {
    long sizeInBytes = convertToBytes(size, unit);
    generateFile(directoryPath, sizeInBytes, unit);
  }

  /**
   * 取消文件生成
   */
  public void cancel() {
    isCancelled.set(true);
    logger.info("已发送取消请求");
  }

  /**
   * 清理生成的文件
   */
  public void cleanup(String directoryPath) throws IOException {
    Path dirPath = Paths.get(directoryPath);
    if (Files.exists(dirPath)) {
      logger.info("清理目录: {}", directoryPath);

      try (var stream = Files.walk(dirPath)) {
        stream.filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".txt"))
            .forEach(p -> {
              try {
                long size = Files.size(p);
                Files.delete(p);
                logger.debug("删除文件: {} (大小: {})",
                    p.getFileName(), formatBytes(size));
              } catch (IOException e) {
                logger.warn("删除文件失败: {}", p.getFileName(), e);
              }
            });
      }

      logger.info("目录清理完成");
    }
  }
}