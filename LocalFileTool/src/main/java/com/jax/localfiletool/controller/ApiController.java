package com.jax.localfiletool.controller;

import com.jax.localfiletool.model.MediaHashResult;
import com.jax.localfiletool.script.FileMd5Hash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 媒体文件哈希变更接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class ApiController {

    private final FileMd5Hash fileMd5Hash;

    /**
     * 更新指定目录下所有图片 / 视频文件的 MD5 / SHA-256。
     *
     * <p>示例：{@code POST /api/media/mutate?path=D:/photos}</p>
     *
     * @param path 目标目录路径
     * @return 每个媒体文件处理前后的哈希对比结果
     */
    @PostMapping("/mutate")
    public List<MediaHashResult> mutate(@RequestParam String path) {
        log.info("收到媒体哈希变更请求，路径: {}", path);
        return fileMd5Hash.mutateAll(path);
    }
}
