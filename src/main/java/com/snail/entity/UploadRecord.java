package com.snail.entity;

import lombok.Data;

@Data
public class UploadRecord {
  private String fileName;
  private long fileSize;
  private long startTime;
  private long endTime;
  private long duration;
  private String status;
  private String errorMsg;
  private String fileId;
  private String uploadMethod;
  private long uploadTime; // 实际上传耗时（不包括验证等时间）
  private long chunkTime;  // 分片上传耗时（如果有）

  // 构造函数、getter和setter省略...

  public void calculateDuration() {
    if (startTime > 0 && endTime > 0) {
      this.duration = endTime - startTime;
    }
  }

  public String getFormattedDuration() {
    return formatDuration(duration);
  }


  /**
   * 格式化耗时（人类可读）
   */
  private String formatDuration(long durationMs) {
    if (durationMs < 1000) {
      return durationMs + "ms";
    } else if (durationMs < 60000) {
      return String.format("%.2fs", durationMs / 1000.0);
    } else if (durationMs < 3600000) {
      long minutes = durationMs / 60000;
      long seconds = (durationMs % 60000) / 1000;
      return String.format("%d分%d秒", minutes, seconds);
    } else {
      long hours = durationMs / 3600000;
      long minutes = (durationMs % 3600000) / 60000;
      long seconds = (durationMs % 60000) / 1000;
      return String.format("%d小时%d分%d秒", hours, minutes, seconds);
    }
  }


}
