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
 * <p>扫描指定目录下（递归）的图片、动态图片、视频与音频文件，计算其 MD5 与 SHA-256，
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
            "jpg", "jpeg", "png", "bmp", "webp", "tif", "tiff", "heic", "heif");

    /** 动态图片扩展名（小写）。gif / apng 等以连续帧表现动画的图片格式 */
    private static final Set<String> ANIMATION_EXT = Set.of(
            "gif", "apng");

    /** 视频扩展名（小写） */
    private static final Set<String> VIDEO_EXT = Set.of(
            "mp4", "avi", "mov", "mkv", "flv", "wmv", "webm", "m4v", "mpeg", "mpg", "3gp", "ts");

    /** 音频扩展名（小写） */
    private static final Set<String> AUDIO_EXT = Set.of(
            "mp3", "wav", "flac", "aac", "ogg", "oga", "m4a", "wma", "aiff", "aif", "opus", "ape", "amr");

    private final SecureRandom random = new SecureRandom();

    /**
     * 扫描指定目录下的所有图片 / 视频文件并更新其 MD5 / SHA-256。
     *
     * @param dirPath 目标目录路径
     * @return 每个媒体文件处理前后的哈希对比结果
     */
    public List<MediaHashResult> mutateAll(String dirPath) {
        File filePath = new File(dirPath);
        if (!filePath.exists()) {
            throw new IllegalArgumentException("路径不存在: " + dirPath);
        }
        if (!filePath.isDirectory()) {
            throw new IllegalArgumentException("路径不是目录: " + dirPath);
        }

        // 递归列出目录下所有文件，再按扩展名筛选媒体文件
        Collection<File> files = FileUtils.listFiles(filePath, null, true);

        log.info("扫描出:"+files.size()+"个文件");

        List<MediaHashResult> results = new ArrayList<>();
        for (File file : files) {
            String type = classify(file.getName());
            log.info("文件名 = " + file);
            log.info("文件类型  = " + type);

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
            log.info("文件:"+file.getAbsolutePath() + " &旧md5 = " + oldMd5);

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
            log.info("文件:"+file+" &新md5 = " + newMd5);

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
        // 安全检查：文件长度连一个完整尾块(24字节)都不到，
        // 就不可能包含我们追加的尾块，没必要往下比，直接原样返回
        if (data.length >= TRAILER_LEN) {

            // off = 魔数在文件中"应该"开始的位置。
            // 文件总长往前数 MAGIC.length(8) 个字节，就是末尾这 8 字节的起点。
            // 例如文件长 122，则 off = 114，魔数若存在就占索引 114~121。
            int off = data.length - MAGIC.length;

            // 先乐观假设末尾就是我们的魔数，比对过程中一旦发现不符再改成 false
            boolean match = true;

            // i 表示"当前是第几次循环"，从 0 开始：
            //   i=0 → 第1次，比对魔数的第1个字节
            //   i=1 → 第2次，比对魔数的第2个字节
            //   ... 一直到 i=7 → 第8次（最后一次），比对魔数的第8个字节
            for (int i = 0; i < MAGIC.length; i++) {

                // data[off + i] = 文件末尾这段里，当前正在检查的那个字节。
                //   i=0 时是 data[114]，应对应魔数 'J'
                //   i=1 时是 data[115]，应对应魔数 'A'
                //   ...
                //   i=7 时是 data[121]，应对应魔数 '1'
                // MAGIC[i] = 魔数 "JAXMUT01" 中当前位置应有的字节。
                // 两者比较：文件里的实际字节 != 魔数里期望的字节 → 说明对不上
                if (data[off + i] != MAGIC[i]) {
                    match = false;  // 标记为"不是我们的尾块"
                    break;          // 跳出循环：只要有一个字节不符就能确定结论，
                                    // 没必要再比剩下的字节，立即结束
                }
                // 若本次字节相符，则不进入 if，循环继续 i++ 比下一个字节。
                // 当 8 个字节全部比完都相符（i 增到 8，不再满足 i < MAGIC.length），
                // 循环自然结束，此时 match 仍为 true。
            }

            // match 为 true：末尾 8 字节与魔数完全一致，确认是我们上次追加的尾块
            if (match) {
                // 复制前 (总长 - 24) 个字节，相当于砍掉末尾整个尾块(随机负载16 + 魔数8)，
                // 返回剥离后的原始内容
                return Arrays.copyOf(data, data.length - TRAILER_LEN);
            }
        }

        // 走到这里有两种情况：① 文件太短；② 末尾对不上魔数。
        // 都说明这文件没有我们的尾块，原样返回，不做任何改动。
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
     * @return IMAGE / ANIMATION / VIDEO / AUDIO，非媒体文件返回 null
     */
    private String classify(String fileName) {
        String ext = FilenameUtils.getExtension(fileName).toLowerCase();


        if (IMAGE_EXT.contains(ext)) {
            log.info("文件名 = "+ fileName+" &后缀 = " +ext+" &属性 = image");
            return "IMAGE";
        }
        if (ANIMATION_EXT.contains(ext)) {
            log.info("文件名 = "+ fileName+" &后缀 = " +ext+" &属性 = animation");
            return "ANIMATION";
        }
        if (VIDEO_EXT.contains(ext)) {
            log.info("文件名 = "+ fileName+" &后缀 = " +ext+" &属性 = video");
            return "VIDEO";
        }
        if (AUDIO_EXT.contains(ext)) {
            log.info("文件名 = "+ fileName+" &后缀 = " +ext+" &属性 = audio");
            return "AUDIO";
        }
        return null;
    }
}
