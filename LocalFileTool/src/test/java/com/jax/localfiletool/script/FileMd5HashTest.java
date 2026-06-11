package com.jax.localfiletool.script;

import com.jax.localfiletool.model.MediaHashResult;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileMd5HashTest {

    private final FileMd5Hash service = new FileMd5Hash();

    @Test
    void mutatesMediaChangesHashEachRunAndStaysBounded() throws Exception {
        Path dir = Files.createTempDirectory("media-hash-test");
        File jpg = dir.resolve("a.jpg").toFile();
        File mp4 = dir.resolve("sub/clip.mp4").toFile();
        File txt = dir.resolve("note.txt").toFile();
        FileUtils.writeByteArrayToFile(jpg, "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8));
        FileUtils.writeByteArrayToFile(mp4, "fake-mp4-bytes".getBytes(StandardCharsets.UTF_8));
        FileUtils.writeStringToFile(txt, "not media", StandardCharsets.UTF_8);

        // 第一次执行：媒体文件被识别并变更，非媒体文件被忽略
        List<MediaHashResult> run1 = service.mutateAll(dir.toString());
        assertEquals(2, run1.size(), "应只处理 jpg 与 mp4 两个媒体文件");
        assertTrue(run1.stream().allMatch(MediaHashResult::isChanged));
        assertTrue(run1.stream().allMatch(r -> !r.getOldMd5().equals(r.getNewMd5())),
                "新旧 MD5 必须不同");
        assertTrue(run1.stream().allMatch(r -> !r.getOldSha256().equals(r.getNewSha256())),
                "新旧 SHA-256 必须不同");
        assertEquals("not media", FileUtils.readFileToString(txt, StandardCharsets.UTF_8),
                "非媒体文件不应被改动");

        Map<String, String> md5AfterRun1 = run1.stream()
                .collect(Collectors.toMap(MediaHashResult::getFilePath, MediaHashResult::getNewMd5));
        long jpgSizeAfterRun1 = jpg.length();

        // 第二次执行：MD5 再次变化，且文件不会无限增长（先剥离旧尾块再追加）
        List<MediaHashResult> run2 = service.mutateAll(dir.toString());
        for (MediaHashResult r : run2) {
            assertNotEquals(md5AfterRun1.get(r.getFilePath()), r.getNewMd5(),
                    "第二次执行后 MD5 必须再次变化");
        }
        assertEquals(jpgSizeAfterRun1, jpg.length(),
                "重复执行后文件大小应保持稳定（尾块不累积）");

        FileUtils.deleteDirectory(dir.toFile());
    }

    @Test
    void rejectsInvalidPath() {
        try {
            service.mutateAll("Z:/definitely/missing/path/xyz");
            assertFalse(true, "不存在的路径应抛出异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("路径不存在"));
        }
    }
}
