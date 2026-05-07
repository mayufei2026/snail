package com.snail.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class FileDownload {

  // 假设这是 Web 应用中处理文件下载的接口
  public static void downloadFile(String userInputFileName) throws IOException {
    // 定义文件存储的根目录
    String baseDir = "/var/www/uploads/";

    // 🚨 漏洞点：直接拼接用户传入的文件名
    // 攻击者可以传入 "../../etc/passwd" 来读取系统敏感文件
    String filePath = baseDir + userInputFileName;

    File file = new File(filePath);

    // 模拟读取文件操作
    if (file.exists()) {
      System.out.println("正在读取文件: " + filePath);
      Files.readAllBytes(Paths.get(filePath));
    } else {
      System.out.println("文件不存在。");
    }
  }

  public static void main(String[] args) throws IOException {
    // 模拟攻击者传入恶意参数
    // 正常情况下用户传入 "report.pdf"
    // 恶意情况下传入 "../../../../etc/passwd"
    downloadFile("../../../../etc/passwd");
  }
}
