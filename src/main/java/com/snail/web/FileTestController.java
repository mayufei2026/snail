package com.snail.web;

import com.snail.entity.FileItemDto;
import com.snail.entity.UploadRecord;
import com.snail.service.FileGenerator;
import com.snail.service.FileServer;
import com.snail.service.FileUpload;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件压测
 *
 * @author mayf
 * @author Corilead DevTeam
 */
@RestController
@RequestMapping("/file-test")
public class FileTestController {

  private static final Logger1 logger = LoggerFactory.getLogger(FileTestController.class);
  private final FileGenerator fileGenerator;
  private final FileUpload fileUpload;
  private static final long MAX_FILE_SIZE = 200 * 1024 * 1024; // 200MB

  private static final String FILEPATH = "/home/corilead/dtp/data";

  public FileTestController(FileGenerator fileGenerator, FileUpload fileUpload) {
    this.fileGenerator = fileGenerator;
    this.fileUpload = fileUpload;
  }


  /**
   * 功能描述: 生成文件
   *
   * @param filePath the file
   * @return void
   */
  @GetMapping(value = "/createFile", name = "生成文件")
  public ResponseEntity<Void> createFile(
      @RequestParam(required = false) String filePath) {
    fileGenerator.createFile(filePath);
    return ResponseEntity.ok().build();
  }


  @GetMapping(value = "/test/upload", name = "上传文件")
  public void uploadTestFile(
      @RequestParam(defaultValue = "1") int count,
      @RequestParam String filePath) {

    logger.info("开始执行文件上传任务，执行次数: {}，文件路径: {}", count, filePath);

    // 参数校验
    if (count <= 0) {
      throw new IllegalArgumentException("执行次数必须大于0");
    }

    Path path = Paths.get(filePath);
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("文件或目录不存在: " + filePath);
    }

    // 获取所有需要上传的文件（如果是目录则列出所有文件，否则只有自身）
    List<Path> allFiles = new ArrayList<>();
    if (Files.isDirectory(path)) {
      try (Stream<Path> walk = Files.list(path)) {
        allFiles = walk.filter(Files::isRegularFile).collect(Collectors.toList());
      } catch (IOException e) {
        throw new RuntimeException("读取目录失败: " + filePath, e);
      }
    } else {
      allFiles.add(path);
    }

    if (allFiles.isEmpty()) {
      throw new IllegalArgumentException("指定路径下没有可上传的文件");
    }

    // 根据 count 生成待上传的文件列表（循环使用）
    List<Path> taskFiles = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      taskFiles.add(allFiles.get(i % allFiles.size()));
    }

    LocalDateTime startTime = LocalDateTime.now();
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    // 创建线程池（可根据实际情况调整核心线程数）
    int threadPoolSize = Math.min(taskFiles.size(), Runtime.getRuntime().availableProcessors() * 2);
    ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);

    // 提交所有上传任务
    List<CompletableFuture<Void>> futures = taskFiles.stream()
        .map(file -> CompletableFuture.runAsync(() -> {
          try (InputStream inputStream = Files.newInputStream(file)) {
            fileUpload.uploadTestFile(inputStream, file.getFileName().toString());
            successCount.incrementAndGet();
            logger.debug("文件上传成功: {}", file);
          } catch (Exception e) {
            failureCount.incrementAndGet();
            logger.error("文件上传失败: {}", file, e);
          }
        }, executor))
        .collect(Collectors.toList());

    // 等待所有任务完成
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // 关闭线程池
    executor.shutdown();
    try {
      if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }

    // 输出统计信息
    long elapsedMinutes = ChronoUnit.MINUTES.between(startTime, LocalDateTime.now());
    logger.info("文件上传任务完成。成功: {} 个，失败: {} 个，总耗时: {} 分钟",
        successCount.get(), failureCount.get(), elapsedMinutes);
  }


  /**
   * 上传文件
   *
   * @param timeLength 执行时长  单位分钟
   * @param filePath   文件路径
   */
  @GetMapping(value = "/upload", name = "上传文件")
  public void uploadFile(
      @RequestParam(defaultValue = "1") int timeLength,
      @RequestParam String filePath,
      @RequestParam(defaultValue = "100") int splitSize) {

    logger.info("开始执行文件上传任务，时长: {} 分钟，文件路径: {}", timeLength, filePath);

    // 验证参数
    if (timeLength <= 0) {
      throw new IllegalArgumentException("执行时长必须大于0分钟");
    }

    Path path = Paths.get(filePath);
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("文件或目录不存在: " + filePath);
    }

    // 计算结束时间
    LocalDateTime startTime = LocalDateTime.now();
    LocalDateTime endTime = startTime.plusMinutes(timeLength);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    try {
      // 判断是文件还是目录
      if (Files.isDirectory(path)) {
        // 上传目录下的所有文件
        uploadDirectoryFiles(path, endTime, successCount, failureCount, splitSize);
      } else {
        // 上传单个文件（循环上传直到时间结束）
        uploadSingleFileRepeatedly(path, endTime, successCount, failureCount, splitSize);
      }
    } catch (Exception e) {
      logger.error("文件上传过程中发生异常", e);
      throw new RuntimeException("文件上传失败", e);
    }

    // 输出统计信息
    logger.info("文件上传任务完成。成功: {} 个，失败: {} 个，总耗时: {} 分钟", successCount.get(),
        failureCount.get(), ChronoUnit.MINUTES.between(startTime, LocalDateTime.now()));
  }


  @GetMapping(value = "/download", name = "下载文件")
  public ResponseEntity<Void> downloadFile(
      @RequestParam String fileId,
      @RequestParam(defaultValue = "1") int timeLength,
      @RequestParam(required = false) String fileName
  ) {
    // 1. 参数验证
    if (timeLength <= 0) {
      return ResponseEntity.badRequest().build();
    }

    LocalDateTime startTime = LocalDateTime.now();
    LocalDateTime endTime = startTime.plusMinutes(timeLength);
    int iterationCount = 0;
    long totalFileSize = 0;
    long totalDownloadTime = 0;

    try {
      // 2. 提前创建目录（避免每次循环都创建）
      Path downloadDir = Paths.get(FILEPATH);
      if (!Files.exists(downloadDir)) {
        Files.createDirectories(downloadDir);
      }

      long fileIdLong;
      try {
        fileIdLong = NumberUtils.toLong(fileId);
      } catch (NumberFormatException e) {
        return ResponseEntity.badRequest().build();
      }

      // 3. 主循环 - 性能测试
      while (LocalDateTime.now().isBefore(endTime)) {
        iterationCount++;
        long iterationStart = System.currentTimeMillis();
        String uuidStr = UUID.randomUUID().toString();
        UploadRecord record = new UploadRecord();
        try {
          // 下载文件流
          long start = System.currentTimeMillis();
          record.setStartTime(start);
          InputStream inputStream = fileUpload.downloadFile(fileIdLong, uuidStr);
          long start1 = System.currentTimeMillis();
          logger.info("文件下载执行时间-1:" + (start1 - start) + "ms");
          if (inputStream == null) {
            return ResponseEntity.notFound().build();
          }
          record.setFileId(fileId);
          // 设置文件名
          String currentFileName = StringUtils.isBlank(fileName)
              ? "file_" + fileId + "_" + iterationCount
              : fileName + "_" + iterationCount;
          record.setFileName(currentFileName);
          Path filePath = downloadDir.resolve(currentFileName);

          // 写入文件

          long start3 = System.currentTimeMillis();
          // 5. 优化：使用缓冲流和分块写入
          long bytesCopied = copyStreamWith1MBBuffer(inputStream, filePath); // 1MB 缓冲区
          totalFileSize += bytesCopied;
          logger.info("文件已保存: {}, 大小: {} 字节", filePath, bytesCopied);
          long start4 = System.currentTimeMillis();
          logger.info("文件下载执行时间-2:" + (start4 - start3) + "ms");
          logger.info("文件下载执行时间-3:" + (start4 - start) + "ms");
          // 处理文件
          processFile(filePath);

          // 删除文件
          Files.deleteIfExists(filePath);
          long start5 = System.currentTimeMillis();
          record.setEndTime(start5);
          logger.info("文件下载执行时间-4:" + (start5 - start) + "ms");
          record.setUploadMethod("文件下载");

          long iterationEnd = System.currentTimeMillis();
          long iterationTime = iterationEnd - iterationStart;
          totalDownloadTime += iterationTime;

          // 记录单次迭代日志
          logger.info("第{}次迭代完成，耗时: {}ms", iterationCount, iterationTime);

          // 千兆网络优化：在下载速度过快时增加短暂间隔
          if (iterationTime < 50 && iterationCount % 10 == 0) {
            Thread.sleep(10); // 10ms间隔，避免过度占用CPU
          }

        } catch (IOException e) {
          logger.warn("第{}次迭代失败: {}", iterationCount, e);
          // 失败后短暂等待
          if (iterationCount % 5 == 0) {
            try {
              Thread.sleep(100);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        } finally {
          // 计算并记录总耗时
          record.calculateDuration();
          // 写入CSV日志
          logUploadToCSV(record);
          Path tmpFilePath = downloadDir.resolve(FileServer.TMPFILEPATH + File.separator + uuidStr);
          // 删除文件
          Files.deleteIfExists(tmpFilePath);
        }
      }

      // 4. 输出最终性能报告
      long totalTimeMinutes = timeLength;
      long totalTimeMs = totalTimeMinutes * 60 * 1000;
      double avgDownloadTime = iterationCount > 0 ? (double) totalDownloadTime / iterationCount : 0;
      double throughputMBps = totalTimeMs > 0
          ? (totalFileSize / (1024.0 * 1024.0)) / (totalTimeMs / 1000.0)
          : 0;

      logger.info("性能测试完成报告:");
      logger.info("总执行时间: {}分钟", timeLength);
      logger.info("总迭代次数: {}", iterationCount);
      logger.info("总下载数据量: {} MB", String.format("%.2f", totalFileSize / (1024.0 * 1024.0)));
      logger.info("平均每次下载耗时: {}ms", String.format("%.2f", avgDownloadTime));
      logger.info("平均吞吐量: {} MB/s", String.format("%.2f", throughputMBps));
      logger.info("网络利用率: {}% (千兆网络理论125MB/s)",
          String.format("%.2f", (throughputMBps / 125.0) * 100));

    } catch (NumberFormatException e) {
      return ResponseEntity.badRequest().build();
    } catch (IOException e) {
      logger.error("文件操作失败", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    } catch (Exception e) {
      Thread.currentThread().interrupt();
      logger.error("测试被中断");
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    return ResponseEntity.ok().build();
  }

  /**
   * 使用缓冲区分块复制流到文件（避免内存溢出）
   */
  private long copyStreamToFileWithBuffer(InputStream inputStream, Path targetFile, int bufferSize)
      throws IOException {

    long totalBytes = 0;
    byte[] buffer = new byte[bufferSize];

    try (OutputStream outputStream = Files.newOutputStream(targetFile, StandardOpenOption.CREATE)) {
      int bytesRead;
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
        totalBytes += bytesRead;

        // 每读取一定量数据后刷新一下
        if (totalBytes % (10 * 1024 * 1024) == 0) { // 每10MB刷新一次
          outputStream.flush();
          logger.debug("已写入 {} MB", totalBytes / (1024 * 1024));
        }
      }
      outputStream.flush();
    }

    return totalBytes;
  }

  /**
   * 使用1MB缓冲区复制流到文件
   */
  private long copyStreamWith1MBBuffer(InputStream inputStream, Path targetFile)
      throws IOException {

    final int BUFFER_SIZE = 1024 * 1024; // 1MB
    long totalBytes = 0;
    byte[] buffer = new byte[BUFFER_SIZE];

    try (OutputStream outputStream = Files.newOutputStream(targetFile,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

      int bytesRead;
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
        totalBytes += bytesRead;

        // 每100MB输出一次日志
        if (totalBytes % (100 * BUFFER_SIZE) == 0) { // 每100MB记录一次
          outputStream.flush();
          logger.debug("已写入 {} MB", totalBytes / BUFFER_SIZE);
        }
      }
      outputStream.flush();
    }

    return totalBytes;
  }


  // 文件处理逻辑（可选）
  private void processFile(Path filePath) throws IOException {
    // 这里可以添加文件处理的逻辑
    // 例如：文件校验、内容分析、格式转换等
    logger.debug("处理文件: {}", filePath);

    // 示例：检查文件大小
    long fileSize = Files.size(filePath);
    if (fileSize > 1024 * 1024 * 10) { // 10MB
      logger.warn("文件过大: {} MB", fileSize / (1024 * 1024));
    }
  }

  /**
   * 上传目录下的所有文件（循环直到时间结束）
   */
  private void uploadDirectoryFiles(Path directory, LocalDateTime endTime,
      AtomicInteger successCount, AtomicInteger failureCount, int splitSize) {
    logger.info("开始上传目录: {}", directory);

    try (Stream<Path> paths = Files.list(directory)) {
      // 获取目录下所有普通文件（非目录）
      List<Path> fileList = paths.filter(Files::isRegularFile).collect(Collectors.toList());

      if (fileList.isEmpty()) {
        logger.warn("目录中没有文件: {}", directory);
        return;
      }

      logger.info("找到 {} 个文件", fileList.size());

      int currentIndex = 0;

      // 循环上传直到时间结束
      while (LocalDateTime.now().isBefore(endTime)) {
        Path currentFile = fileList.get(currentIndex);

        try {
          boolean success = uploadSingleFile(currentFile, splitSize);

          if (success) {
            successCount.incrementAndGet();
            logger.debug("文件上传成功: {}", currentFile.getFileName());
          } else {
            failureCount.incrementAndGet();
            logger.warn("文件上传失败: {}", currentFile.getFileName());
          }
        } catch (Exception e) {
          failureCount.incrementAndGet();
          logger.error("上传文件时发生异常: {}", currentFile.getFileName(), e);
        }

        // 移动到下一个文件，如果到最后一个则重新开始
        currentIndex = (currentIndex + 1) % fileList.size();

        // 添加短暂延迟，避免过于频繁的请求
        try {
          Thread.sleep(10); // 100毫秒延迟
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          logger.warn("上传线程被中断");
          break;
        }

        // 记录进度
        if (successCount.get() % 10 == 0) {
          long remainingSeconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), endTime);
          logger.info("已上传 {} 个文件，剩余时间: {} 秒", successCount.get(), remainingSeconds);
        }
      }
    } catch (IOException e) {
      logger.error("读取目录失败: {}", directory, e);
      throw new RuntimeException("无法读取目录", e);
    }
  }

  /**
   * 重复上传单个文件直到时间结束
   */
  private void uploadSingleFileRepeatedly(Path file, LocalDateTime endTime,
      AtomicInteger successCount, AtomicInteger failureCount, int splitSize) {
    logger.info("开始循环上传单个文件: {}", file.getFileName());

    int iteration = 0;

    // 循环上传直到时间结束
    while (LocalDateTime.now().isBefore(endTime)) {
      iteration++;

      try {
        boolean success = uploadSingleFile(file, splitSize);

        if (success) {
          successCount.incrementAndGet();
          logger.debug("第 {} 次上传成功: {}", iteration, file.getFileName());
        } else {
          failureCount.incrementAndGet();
          logger.warn("第 {} 次上传失败: {}", iteration, file.getFileName());
        }
      } catch (Exception e) {
        failureCount.incrementAndGet();
        logger.error("第 {} 次上传时发生异常: {}", iteration, file.getFileName(), e);
      }

      // 添加短暂延迟
      try {
        Thread.sleep(10); // 100毫秒延迟
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.warn("上传线程被中断");
        break;
      }

      // 记录进度
      if (iteration % 20 == 0) {
        long remainingSeconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), endTime);
        logger.info("已上传 {} 次，成功: {} 次，剩余时间: {} 秒", iteration, successCount.get(),
            remainingSeconds);
      }
    }
  }

  /**
   * 上传单个文件
   *
   * @param filePath 文件路径
   * @return 上传是否成功
   */
  private boolean uploadSingleFile(Path filePath, int splitSize) {
    UploadRecord record = new UploadRecord();
    record.setFileName(filePath.getFileName().toString());
    long startTime = System.currentTimeMillis();
    record.setStartTime(startTime);
    try {
      File file = filePath.toFile();
      FileItemDto fileItemDto = null;
      // 验证文件
      if (!file.exists() || !file.isFile() || !file.canRead()) {
        logger.error("文件不可读或不存在: {}", filePath);
        return false;
      }

      // 检查文件大小
      long fileSize = file.length();
      record.setFileSize(fileSize);

      //大文件分片上传
      if (fileSize > MAX_FILE_SIZE) {
        logger.warn("文件过大，跳过上传: {} (大小: {} MB, 限制: {} MB)", filePath.getFileName(),
            fileSize / (1024 * 1024), MAX_FILE_SIZE / (1024 * 1024));
        // 记录上传方式
        record.setUploadMethod("分片上传");
        //处理大文件 分片上传
        fileItemDto = fileUpload.uploadFileInChunks(file, splitSize);
      } else {
        record.setUploadMethod("普通上传");
        // 创建文件资源
        try (InputStream inputStream = new FileInputStream(file)) {
          fileItemDto = fileUpload.uploadFile(inputStream, file.getName());
        }
      }
      long endTime = System.currentTimeMillis();
      record.setEndTime(endTime);
      record.setUploadTime(endTime - startTime);
      // 判断上传是否成功
      boolean success = (fileItemDto != null && fileItemDto.getId() != null);
      record.setStatus(success ? "成功" : "失败");
      record.setFileId(success ? fileItemDto.getId().toString() : "N/A");
    } catch (Exception e) {
      logger.error("上传文件失败: {}", filePath, e);
      return false;
    } finally {
      // 计算并记录总耗时
      record.calculateDuration();

      // 写入CSV日志
      logUploadToCSV(record);
    }
    return true;
  }

  /**
   * 将上传记录写入CSV文件（完整版）
   */
  private synchronized void logUploadToCSV(UploadRecord record) {
    String csvFilePath = "upload_records_" +
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".csv";
    File csvFile = new File(csvFilePath);

    try {
      // 检查文件是否存在并获取当前行数
      boolean isNewFile = !csvFile.exists() || csvFile.length() == 0;
      long currentLineCount = 0;

      if (!isNewFile) {
        // 读取文件获取当前行数（排除BOM）
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
          // 跳过可能的BOM
          reader.mark(4);
          int firstChar = reader.read();
          if (firstChar != 0xFEFF && firstChar != 0xEFBBBF) {
            reader.reset(); // 不是BOM，重置
          }

          // 计算行数
          while (reader.readLine() != null) {
            currentLineCount++;
          }
        } catch (Exception e) {
          logger.warn("读取CSV文件行数失败，将从头开始: {}", e.getMessage());
          isNewFile = true; // 如果读取失败，视为新文件
          currentLineCount = 0;
        }
      }

      // 使用FileOutputStream直接写入UTF-8
      try (FileOutputStream fos = new FileOutputStream(csvFile, true)) {

        // 如果是新文件，写入UTF-8 BOM和表头
        if (isNewFile) {
          // 写入UTF-8 BOM
          byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
          fos.write(bom);

          // 写入表头
          String header = "序号,文件名,文件大小,开始时间,结束时间,总耗时,上传方式,状态,文件ID,错误信息\n";
          fos.write(header.getBytes(StandardCharsets.UTF_8));
          currentLineCount = 1; // 表头算第一行
        }

        // 计算序号：如果只有表头，序号从1开始；否则从当前行数开始
        long sequence = currentLineCount; // 因为表头也算一行，所以当前行数就是下一个序号

        // 准备数据
        String csvRecord = String.format(
            "%d,\"%s\",%d,\"%s\",\"%s\",%dms,\"%s\",\"%s\",\"%s\",\"%s\"\n",
            sequence,
            record.getFileName(),
            record.getFileSize(),
            formatTimestamp(record.getStartTime()),
            formatTimestamp(record.getEndTime()),
            record.getDuration(),
            record.getUploadMethod(),
            record.getStatus(),
            record.getFileId(),
            record.getErrorMsg()
        );

        fos.write(csvRecord.getBytes(StandardCharsets.UTF_8));

        // 打印日志
        logger.info("CSV记录写入成功 - 文件: {}, 序号: {}, 状态: {}",
            record.getFileName(), sequence, record.getStatus());

      } catch (IOException e) {
        logger.error("写入CSV文件失败", e);
      }

    } catch (Exception e) {
      logger.error("记录上传日志失败", e);
    }
  }

  /**
   * 格式化时间戳为可读字符串
   */
  private String formatTimestamp(long timestamp) {
    if (timestamp <= 0) {
      return "N/A";
    }

    // 使用 SimpleDateFormat，确保总是显示3位毫秒
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    sdf.setTimeZone(TimeZone.getDefault());
    return sdf.format(new Date(timestamp));
  }
}
