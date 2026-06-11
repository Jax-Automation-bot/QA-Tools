package com.jax.localfiletool.script;

import com.jax.localfiletool.model.MediaHashResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实数据手动测试：直接对本地目录里的真实图片 / 视频执行 MD5 / SHA-256 变更。
 *
 * <p>⚠️ 注意：这会真实修改 {@link #REAL_PATH} 目录下的媒体文件（在文件末尾追加随机字节）。
 * 请指向你专门用于测试的目录，不要指向重要的原始素材。</p>
 *
 * <p>用法：把 {@link #REAL_PATH} 改成你本地的目录，在 IDE 里直接运行本方法即可，
 * 控制台会打印每个文件变更前后的 MD5 / SHA-256。</p>
 */
class FileMd5HashTest {

    /** 改成你本地真实的测试目录 */
    private static final String REAL_PATH = "D:/TestCache/video - 副本";

    private final FileMd5Hash service = new FileMd5Hash();

    @Test
    void mutateRealDirectory() {
        List<MediaHashResult> results = service.mutateAll(REAL_PATH);

        System.out.println("目标目录: " + REAL_PATH);
        System.out.println("共处理媒体文件: " + results.size());
        for (MediaHashResult r : results) {
            System.out.println("----------------------------------------");
            System.out.println("文件 : " + r.getFilePath());
            System.out.println("类型 : " + r.getType());
            System.out.println("体积 : " + r.getOldSize() + " -> " + r.getNewSize());
            System.out.println("MD5  : " + r.getOldMd5() + " -> " + r.getNewMd5());
            System.out.println("SHA  : " + r.getOldSha256() + " -> " + r.getNewSha256());
            if (!r.isChanged()) {
                System.out.println("失败原因 : " + r.getError());
            }
        }

        // 真实数据下的基本校验：每个媒体文件都应变更成功，且新旧 MD5 不同。
        for (MediaHashResult r : results) {
            assertTrue(r.isChanged(), "文件应变更成功: " + r.getFilePath());
            assertTrue(!r.getOldMd5().equals(r.getNewMd5()), "新旧 MD5 应不同: " + r.getFilePath());
        }
    }
}
