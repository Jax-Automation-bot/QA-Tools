package com.jax.localfiletool.script;

import com.jax.localfiletool.model.MediaHashResult;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FileMd5Hash 的单元测试。
 * 使用 JUnit5 的 @TempDir，测试结束后临时目录会自动清理，无需手动删除。
 */
class FileMd5HashTest {

    private final FileMd5Hash service = new FileMd5Hash();

    /** 在临时目录里准备 1 张图片、1 个视频、1 个非媒体文件 */
    private void createSampleFiles(Path dir) throws Exception {
        write(dir, "a.jpg", "fake-jpeg-bytes");
        write(dir, "sub/clip.mp4", "fake-mp4-bytes");
        write(dir, "note.txt", "not media");
    }

    private void write(Path dir, String relativePath, String content) throws Exception {
        FileUtils.writeStringToFile(dir.resolve(relativePath).toFile(), content, StandardCharsets.UTF_8);
    }

    @Test
    void onlyProcessesMediaFiles(@TempDir Path dir) throws Exception {
        createSampleFiles(dir);

        List<MediaHashResult> results = service.mutateAll(dir.toString());

        // 只应处理 jpg 与 mp4，note.txt 被忽略
        assertEquals(2, results.size(), "应只处理 jpg 与 mp4 两个媒体文件");
        assertTrue(results.stream().allMatch(MediaHashResult::isChanged), "媒体文件应全部变更成功");
        assertEquals("not media",
                FileUtils.readFileToString(dir.resolve("note.txt").toFile(), StandardCharsets.UTF_8),
                "非媒体文件不应被改动");
    }

    @Test
    void changesHashOnEveryRun(@TempDir Path dir) throws Exception {
        createSampleFiles(dir);

        // 第一次执行：新旧哈希必须不同
        List<MediaHashResult> firstRun = service.mutateAll(dir.toString());
        for (MediaHashResult r : firstRun) {
            assertNotEquals(r.getOldMd5(), r.getNewMd5(), "同一次执行内，新旧 MD5 必须不同");
            assertNotEquals(r.getOldSha256(), r.getNewSha256(), "同一次执行内，新旧 SHA-256 必须不同");
        }

        // 第一次执行后，jpg 的哈希与体积，作为第二次的对比基准
        MediaHashResult jpgFirst = findByName(firstRun, "a.jpg");
        long sizeAfterFirstRun = dir.resolve("a.jpg").toFile().length();

        // 第二次执行：哈希要再次变化；体积要保持稳定（每次先剥离旧尾块再追加，所以不会越来越大）
        List<MediaHashResult> secondRun = service.mutateAll(dir.toString());
        MediaHashResult jpgSecond = findByName(secondRun, "a.jpg");

        assertNotEquals(jpgFirst.getNewMd5(), jpgSecond.getNewMd5(), "两次执行得到的 MD5 必须不同");
        assertEquals(sizeAfterFirstRun, dir.resolve("a.jpg").toFile().length(),
                "重复执行后文件体积应保持稳定（尾块不累积）");
    }

    @Test
    void throwsWhenPathDoesNotExist() {
        try {
            service.mutateAll("Z:/definitely/missing/path/xyz");
            // 能走到这一行，说明上面没有抛异常 —— 不符合预期，主动判定测试失败。
            // 注意：这里必须传一个“不存在”的路径，传真实存在的目录会正常执行、走到这里而失败。
            fail("路径不存在时应抛出 IllegalArgumentException，但实际没有抛出");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("路径不存在"), "异常信息应说明路径不存在");
        }
    }

    /** 从结果列表里按文件名找出对应的那条结果 */
    private MediaHashResult findByName(List<MediaHashResult> results, String fileName) {
        return results.stream()
                .filter(r -> r.getFilePath().endsWith(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果中找不到文件: " + fileName));
    }
}
