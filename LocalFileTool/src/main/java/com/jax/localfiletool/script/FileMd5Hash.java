package com.jax.localfiletool.script;

import com.jax.localfiletool.model.MediaHashResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 媒体文件 MD5 / SHA-256 变更脚本。
 *
 * <p>扫描指定目录下（递归）的图片与视频文件，计算其 MD5 与 SHA-256，
 * 然后向文件尾部追加一段随机字节，使每次执行后 MD5 / SHA-256 都与上一次不同。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *     <li>追加的字节位于文件末尾，并带有固定魔数标记。每次执行会先剥离上一次追加的尾块，
 *     再追加新的随机尾块，因此文件不会随执行次数无限增长。</li>
 *     <li>JPEG / PNG / GIF / MP4 等常见格式对文件末尾的多余字节是容忍的，
 *     追加后仍可正常查看 / 播放。</li>
 *     <li>MD5 推荐使用 Apache Commons Codec，文件读写使用 Apache Commons IO。</li>
 * </ul>
 */
@Slf4j
@Service
public class FileMd5Hash {

    /** 尾块魔数，用于识别并剥离上一次追加的内容 */
    private static final byte[] MAGIC = "JAXMUT01".getBytes(StandardCharsets.US_ASCII);

    /** 每次追加的随机负载长度（字节） */
    private static final int PAYLOAD_LEN = 16;

    /** 完整尾块长度 = 随机负载 + 魔数 */
    private static final int TRAILER_LEN = PAYLOAD_LEN + MAGIC.length;

    /** 图片扩展名（小写） */
    private static final Set<String> IMAGE_EXT = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "heic", "heif");

    /** 视频扩展名（小写） */
    private static final Set<String> VIDEO_EXT = Set.of(
            "mp4", "avi", "mov", "mkv", "flv", "wmv", "webm", "m4v", "mpeg", "mpg", "3gp", "ts");

    private final SecureRandom random = new SecureRandom();

    /**
     * 扫描指定目录下的所有图片 / 视频文件并更新其 MD5 / SHA-256。
     *
     * @param dirPath 目标目录路径
     * @return 每个媒体文件处理前后的哈希对比结果
     */
    public List<MediaHashResult> mutateAll(String dirPath) {
        File root = new File(dirPath);
        if (!root.exists()) {
            throw new IllegalArgumentException("路径不存在: " + dirPath);
        }
        if (!root.isDirectory()) {
            throw new IllegalArgumentException("路径不是目录: " + dirPath);
        }

        // 递归列出目录下所有文件，再按扩展名筛选媒体文件
        Collection<File> files = FileUtils.listFiles(root, null, true);
        List<MediaHashResult> results = new ArrayList<>();
        for (File file : files) {
            String type = classify(file.getName());
            if (type == null) {
                continue;
            }
            results.add(mutateOne(file, type));
        }

        long ok = results.stream().filter(MediaHashResult::isChanged).count();
        log.info("处理目录 [{}] 完成：媒体文件 {} 个，成功变更 {} 个", dirPath, results.size(), ok);
        return results;
    }

    /**
     * 处理单个媒体文件：计算旧哈希 → 重写内容 → 计算新哈希。
     */
    private MediaHashResult mutateOne(File file, String type) {
        MediaHashResult.MediaHashResultBuilder builder = MediaHashResult.builder()
                .filePath(file.getAbsolutePath())
                .type(type);
        try {
            byte[] before = FileUtils.readFileToByteArray(file);
            String oldMd5 = DigestUtils.md5Hex(before);
            builder.oldSize(before.length)
                    .oldMd5(oldMd5)
                    .oldSha256(DigestUtils.sha256Hex(before));

            // 先剥离上一次追加的尾块，再追加新的随机尾块；
            // 循环确保新内容的 MD5 一定不同于旧内容（极小概率随机重复时重试）。
            byte[] base = stripTrailer(before);
            byte[] mutated;
            String newMd5;
            do {
                mutated = appendTrailer(base);
                newMd5 = DigestUtils.md5Hex(mutated);
            } while (newMd5.equals(oldMd5));

            FileUtils.writeByteArrayToFile(file, mutated, false);

            builder.newSize(mutated.length)
                    .newMd5(newMd5)
                    .newSha256(DigestUtils.sha256Hex(mutated))
                    .changed(true);
            log.debug("已变更 [{}] {} -> {}", file.getName(), oldMd5, newMd5);
        } catch (IOException e) {
            log.error("处理文件失败: {}", file.getAbsolutePath(), e);
            builder.changed(false).error(e.getMessage());
        }
        return builder.build();
    }

    /**
     * 若文件末尾存在本工具追加的尾块（以魔数结尾），则去除它，返回原始内容。
     */
    private byte[] stripTrailer(byte[] data) {
        if (data.length >= TRAILER_LEN) {
            int off = data.length - MAGIC.length;
            boolean match = true;
            for (int i = 0; i < MAGIC.length; i++) {
                if (data[off + i] != MAGIC[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return Arrays.copyOf(data, data.length - TRAILER_LEN);
            }
        }
        return data;
    }

    /**
     * 在原始内容末尾追加 [随机负载 + 魔数] 尾块。
     */
    private byte[] appendTrailer(byte[] base) {
        byte[] payload = new byte[PAYLOAD_LEN];
        random.nextBytes(payload);

        byte[] out = new byte[base.length + TRAILER_LEN];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(payload, 0, out, base.length, PAYLOAD_LEN);
        System.arraycopy(MAGIC, 0, out, base.length + PAYLOAD_LEN, MAGIC.length);
        return out;
    }

    /**
     * 按扩展名判断文件类型。
     *
     * @return IMAGE / VIDEO，非媒体文件返回 null
     */
    private String classify(String fileName) {
        String ext = FilenameUtils.getExtension(fileName).toLowerCase();
        if (IMAGE_EXT.contains(ext)) {
            return "IMAGE";
        }
        if (VIDEO_EXT.contains(ext)) {
            return "VIDEO";
        }
        return null;
    }
}
