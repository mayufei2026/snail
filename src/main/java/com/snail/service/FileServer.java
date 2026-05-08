package com.snail.service;

import com.snail.entity.FileItemDto;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求转发器 - 用于在分布式文件系统实例间转发上传和下载请求 实现跨实例的文件操作请求路由
 */
@Component
public class FileServer {

  private static final Logger LOGGER  = LoggerFactory.getLogger(FileServer.class);

  private final RestTemplate restTemplate;
  public static final String HOST = "http://192.168.10.10:808";
  public static final String HOST1 = "http://127.0.0.1:8081";
  public static final String API_URL = "/plm/api/v3/dfs";
  public static final String FILE_UPLOAD_PATH = "/files/upload";
  public static final String TEST_FILE_UPLOAD_PATH = "/test/files/upload";
  public static final String FILE_DOWNLOAD_PATH = "/files/download";
  public static final String FILE_DELETE_PATH = "/files/delete";
  public static final String FILE_UPLOAD_SPLIT_PATH = "/files/upload-split";
  public static final String TMPFILEPATH  = "/home/corilead/dtp/tmp";

  public FileServer(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /**
   * 转发文件上传请求到其他实例
   */
  public FileItemDto uploadRequest(InputStream stream, String filename)
      throws Exception {
    String targetUrl = buildTargetUrl(HOST1, FILE_UPLOAD_PATH);

    LOGGER .info("file upload request to {}", targetUrl);
    HttpHeaders headers = buildForwardHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    MultiValueMap<String, Object> requestBody = buildUploadRequestBody(stream, filename);

    return executePostRequest(targetUrl, headers, requestBody, FileItemDto.class);
  }

  /**
   * 测试-转发文件上传请求到其他实例
   */
  public FileItemDto uploadTestRequest(InputStream stream, String filename)
      throws Exception {
    String targetUrl = buildTargetUrl(HOST, TEST_FILE_UPLOAD_PATH);

    LOGGER .info("file upload request to {}", targetUrl);
    HttpHeaders headers = buildForwardHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    MultiValueMap<String, Object> requestBody = buildUploadRequestBody(stream, filename);

    return executePostRequest(targetUrl, headers, requestBody, FileItemDto.class);
  }

  /**
   * 转发文件下载请求到其他实例
   */
  public InputStream downloadRequest(Long fileId ,String  uuidStr) throws Exception {

    String targetUrl =
        buildTargetUrl(HOST1, FILE_DOWNLOAD_PATH) + "?fileId=" + fileId;
    LOGGER .info("file download request to {}", targetUrl);
    HttpHeaders headers = buildForwardHeaders();

    return executeGetRequest(targetUrl, headers , uuidStr);
  }

  /**
   * 转发文件删除请求到其他实例
   */
  public void deleteRequest(Long fileId) throws Exception {

    String targetUrl = buildTargetUrl(HOST1, API_URL + FILE_DELETE_PATH) + "?fileId=" + fileId;
    LOGGER .info("file delete request to {}", targetUrl);
    HttpHeaders headers = buildForwardHeaders();
    try {
      ResponseEntity<Void> response =
          restTemplate.exchange(targetUrl, HttpMethod.DELETE, new HttpEntity<>(headers),
              Void.class);
      // 检查响应状态
      if (!response.getStatusCode().is2xxSuccessful()) {
        throw new Exception("删除文件失败，状态码: " + response.getStatusCode());
      }
    } catch (Exception e) {
      throw new Exception("远程删除文件异常: " + e);
    }
  }

  /**
   * 在 RequestForwarder 中添加分片合并转发方法
   */
  public FileItemDto uploadSplitRequest(
      InputStream stream, int chunk, int chunks, String uuid, String filename)
      throws Exception {

    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

    String targetUrl = buildTargetUrl(HOST1, FILE_UPLOAD_SPLIT_PATH);

    LOGGER .info("file uploadSplit request to {}", targetUrl);
    if (attributes == null) {
      throw new IllegalStateException("当前不在请求上下文中，无法获取请求头");
    }
    HttpHeaders headers = buildForwardHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ByteArrayResource resource =
        new ByteArrayResource(StreamUtils.copyToByteArray(stream)) {
          @Override
          public String getFilename() {
            return filename;
          }
        };
    // 构建请求体
    MultiValueMap<String, Object> objectMap = new LinkedMultiValueMap<>();
    objectMap.add("file", resource);
    objectMap.add("chunk", chunk);
    objectMap.add("chunks", chunks);
    objectMap.add("uuid", uuid);
    objectMap.add("fileName", filename);

    try {
      HttpEntity<MultiValueMap<String, Object>> requestEntity =
          new HttpEntity<>(objectMap, headers);
      ResponseEntity<FileItemDto> response =
          restTemplate.postForEntity(targetUrl, requestEntity, FileItemDto.class);
      return response.getBody();
    } catch (ResourceAccessException e) {
      throw new Exception("分片上传转发请求超时: " + targetUrl + ";" + e);
    } catch (RestClientException e) {
      throw new Exception("分片上传转发请求失败: " + targetUrl + ";" + e);
    }
  }

  /**
   * 构建转发请求头 复制原始请求的所有头部信息，并添加转发标识
   */
  private HttpHeaders buildForwardHeaders() {
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    HttpHeaders headers = new HttpHeaders();
    if (attributes != null) {
      HttpServletRequest request = attributes.getRequest();
      copyRequestHeaders(request, headers);
    }
    return headers;
  }

  /**
   * 复制请求头信息
   */
  private void copyRequestHeaders(HttpServletRequest request, HttpHeaders headers) {
    Enumeration<String> headerNames = request.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String headerName = headerNames.nextElement();
      Enumeration<String> values = request.getHeaders(headerName);
      while (values.hasMoreElements()) {
        headers.add(headerName, values.nextElement());
      }
    }
  }

  /**
   * 构建上传请求体
   */
  private MultiValueMap<String, Object> buildUploadRequestBody(InputStream stream, String filename)
      throws IOException {
    MultiValueMap<String, Object> requestBody = new LinkedMultiValueMap<>();
    ByteArrayResource resource = createByteArrayResource(stream, filename);
    requestBody.add("file", resource);
    return requestBody;
  }

  /**
   * 创建字节数组资源
   */
  private ByteArrayResource createByteArrayResource(InputStream stream, String filename)
      throws IOException {
    return new ByteArrayResource(StreamUtils.copyToByteArray(stream)) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }

  /**
   * 执行POST请求
   */
  private <T> T executePostRequest(
      String url, HttpHeaders headers, Object requestBody, Class<T> responseType) throws Exception {
    try {
      HttpEntity<?> requestEntity = new HttpEntity<>(requestBody, headers);
      ResponseEntity<T> response = restTemplate.postForEntity(url, requestEntity, responseType);
      return response.getBody();
    } catch (ResourceAccessException e) {
      throw new Exception("Request timeout to url: " + url + ";" + e);
    } catch (RestClientException e) {
      throw new Exception("Request failed to url: " + url + ";" + e);
    }
  }

  /**
   * 执行GET请求
   */
  private InputStream executeGetRequest(String url, HttpHeaders headers , String randomStr)
      throws Exception {
    try {
      HttpEntity<String> requestEntity = new HttpEntity<>("", headers);

      // 解决方案：使用临时文件存储下载内容，避免直接返回RestTemplate的响应流
      return restTemplate.execute(url, HttpMethod.GET,
          request -> {
            // 设置请求头
            if (headers != null) {
              headers.forEach((key, values) ->
                  request.getHeaders().addAll(key, values));
            }
          },
          response -> {
            // 检查响应状态
            if (!response.getStatusCode().is2xxSuccessful()) {
              try {
                throw new Exception("Request failed with status: " +
                    response.getStatusCode() + " to url: " + url);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }

            // 1. 确保临时目录存在
            File tempDir = new File(TMPFILEPATH);
            if (!tempDir.exists() && !tempDir.mkdirs()) {
              throw new IOException("无法创建临时目录: " + TMPFILEPATH);
            }
            // 注意：不再使用deleteOnExit()，改为在流关闭时立即删除
            // 2. 创建临时文件
            File tempFile = new File(tempDir, randomStr);
            // 2. 将响应流写入临时文件
            try (InputStream responseStream = response.getBody();
                 FileOutputStream fos = new FileOutputStream(tempFile);
                 BufferedInputStream bis = new BufferedInputStream(responseStream);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 1024 * 1024)) { // 1MB缓冲区
              
              byte[] buffer = new byte[1024 * 1024]; // 1MB缓冲区
              int bytesRead;
              while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
              }
              bos.flush();
            }
            
            // 3. 返回临时文件的输入流，这个流不会被RestTemplate关闭
            // 当调用方关闭这个流时，临时文件会立即被删除
            return new FileInputStream(tempFile) {
              @Override
              public void close() throws IOException {
                try {
                  super.close();
                } finally {
                  // 立即删除临时文件，避免/tmp目录被写满
                  boolean deleted = tempFile.delete();
                  if (!deleted) {
                    LOGGER .warn("Failed to delete temp file: {}", tempFile.getAbsolutePath());
                  } else {
                    LOGGER .debug("Deleted temp file: {}", tempFile.getAbsolutePath());
                  }
                }
              }
            };
          }
      );
    } catch (ResourceAccessException e) {
      throw new Exception("Request timeout to url: " + url + ";" + e);
    } catch (RestClientException e) {
      throw new Exception("Request failed to url: " + url + ";" + e);
    }
  }

  /**
   * 处理下载响应
   */
  private InputStream handleDownloadResponse(ResponseEntity<byte[]> response) throws Exception {
    if (response.getStatusCode().is2xxSuccessful()) {
      if (response.getStatusCode() == HttpStatus.OK && Objects.nonNull(response.getBody())) {
        return new ByteArrayInputStream(response.getBody());
      }
      return null;
    } else {
      throw new Exception("文件下载异常，错误码：" + response.getStatusCode());
    }
  }

  /**
   * 构建目标实例的完整URL
   */
  private String buildTargetUrl(String host, String path) {
    return host + path;
  }
}
