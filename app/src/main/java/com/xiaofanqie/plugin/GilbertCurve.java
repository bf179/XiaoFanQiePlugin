package com.xiaofanqie.plugin;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Gilbert 空间填充曲线工具类。
 * <p>
 * 基于 <a href="https://xiaofanqiehunxiao.com">小番茄图片混淆</a> 的 JavaScript 实现
 * 精确翻译为 Java，用于图片的 Gilbert 曲线像素置换（混淆/解混淆）。
 * <p>
 * 算法原理：
 * 1. 对图像尺寸 (w, h) 生成 Gilbert 空间填充曲线的像素遍历顺序
 * 2. 使用黄金比例偏移量 L = round((√5-1)/2 * N) 作为配对距离
 * 3. 混淆：对于每个 Gilbert 曲线位置 s，将像素从 gilbert[s] 复制到 gilbert[(s+L)%N]
 * 4. 解混淆：对于每个 Gilbert 曲线位置 s，将像素从 gilbert[(s+L)%N] 复制到 gilbert[s]
 * <p>
 * 混淆与解混淆互为逆操作，可多次叠加。
 */
public class GilbertCurve {

    private GilbertCurve() {
    }

    /**
     * 生成 Gilbert 曲线坐标序列。
     *
     * @param width  图像宽度
     * @param height 图像高度
     * @return 长度为 width*height 的数组，每个元素为 int[2] = {x, y}
     */
    public static int[][] generateCurve(int width, int height) {
        List<int[]> result = new ArrayList<>(width * height);
        if (width >= height) {
            gilbertRecursive(0, 0, width, 0, 0, height, result);
        } else {
            // 宽度 < 高度时交换坐标轴
            gilbertRecursive(0, 0, 0, height, width, 0, result);
        }
        return result.toArray(new int[0][]);
    }

    /**
     * Gilbert 曲线递归生成核心。
     * <p>
     * 参数说明：
     * (x, y)  — 当前区域起始坐标
     * (ax, ay) — 主方向向量（较长边方向）
     * (bx, by) — 次方向向量（较短边方向）
     *
     * @param x      起始 x
     * @param y      起始 y
     * @param ax     主方向 x 分量
     * @param ay     主方向 y 分量
     * @param bx     次方向 x 分量
     * @param by     次方向 y 分量
     * @param result 结果列表
     */
    private static void gilbertRecursive(int x, int y,
                                         int ax, int ay,
                                         int bx, int by,
                                         List<int[]> result) {
        int m = Math.abs(ax + ay);  // 当前区域宽度（像素数）
        int l = Math.abs(bx + by);  // 当前区域高度（像素数）

        int u = Integer.signum(ax);  // 主方向 dx
        int d = Integer.signum(ay);  // 主方向 dy
        int L = Integer.signum(bx);  // 次方向 dx
        int s = Integer.signum(by);  // 次方向 dy

        // 单行：水平填充
        if (l == 1) {
            for (int i = 0; i < m; i++) {
                result.add(new int[]{x, y});
                x += u;
                y += d;
            }
            return;
        }

        // 单列：垂直填充
        if (m == 1) {
            for (int i = 0; i < l; i++) {
                result.add(new int[]{x, y});
                x += L;
                y += s;
            }
            return;
        }

        // 递归分割
        // 使用 Math.floorDiv 处理可能的负数值（JS 的 Math.floor 行为）
        int h = Math.floorDiv(ax, 2);
        int g = Math.floorDiv(ay, 2);
        int i = Math.floorDiv(bx, 2);
        int f = Math.floorDiv(by, 2);

        int halfWidth = Math.abs(h + g);
        int halfHeight = Math.abs(i + f);

        // 条件 2*m > 3*l 等价于 w/h > 1.5 —— 判断哪个方向更长
        if (2 * m > 3 * l) {
            // 宽度是长边：水平分割为左右两个子区域
            if (halfWidth % 2 == 1 && m > 2) {
                h += u;
                g += d;
            }
            gilbertRecursive(x, y, h, g, bx, by, result);
            gilbertRecursive(x + h, y + g, ax - h, ay - g, bx, by, result);
        } else {
            // 高度是长边：垂直分割，分三块
            if (halfHeight % 2 == 1 && l > 2) {
                i += L;
                f += s;
            }
            gilbertRecursive(x, y, i, f, h, g, result);
            gilbertRecursive(x + i, y + f, ax, ay, bx - i, by - f, result);
            // 第三块：回程连接段
            gilbertRecursive(x + (ax - u) + (i - L),
                    y + (ay - d) + (f - s),
                    -i, -f,
                    -(ax - h), -(ay - g),
                    result);
        }
    }

    /**
     * 对 Bitmap 进行小番茄解混淆（Gilbert 曲线逆置乱）。
     * <p>
     * 与网站「解混淆」按钮功能完全一致。
     *
     * @param bitmap 待解混淆的图片
     * @return 解混淆后的新 Bitmap
     */
    public static Bitmap unscramble(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int total = width * height;

        // 1. 提取像素
        int[] pixels = new int[total];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // 2. 生成 Gilbert 曲线坐标
        int[][] curve = generateCurve(width, height);

        // 3. 黄金比例偏移量
        int goldenOffset = (int) Math.round((Math.sqrt(5) - 1) / 2 * total);

        // 4. 解混淆（与 JS 的 "dec" 分支一致）
        int[] result = new int[total];
        for (int s = 0; s < total; s++) {
            int[] srcPos = curve[s];                       // Gilbert 位置 s
            int[] partnerPos = curve[(s + goldenOffset) % total]; // Gilbert 位置 (s+L)%N

            int srcIdx = srcPos[0] + srcPos[1] * width;         // 源位置（像素写入目标）
            int partnerIdx = partnerPos[0] + partnerPos[1] * width; // 配对位置（像素来源）

            // 解混淆：像素从 partner 位置移动到 src 位置
            result[srcIdx] = pixels[partnerIdx];
        }

        // 5. 创建结果 Bitmap
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        output.setPixels(result, 0, width, 0, 0, width, height);
        return output;
    }

    /**
     * 对 Bitmap 进行小番茄混淆（Gilbert 曲线置乱）。
     * <p>
     * 与网站「混淆」按钮功能完全一致。
     *
     * @param bitmap 待混淆的图片
     * @return 混淆后的新 Bitmap
     */
    public static Bitmap scramble(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int total = width * height;

        int[] pixels = new int[total];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int[][] curve = generateCurve(width, height);
        int goldenOffset = (int) Math.round((Math.sqrt(5) - 1) / 2 * total);

        int[] result = new int[total];
        for (int s = 0; s < total; s++) {
            int[] srcPos = curve[s];
            int[] partnerPos = curve[(s + goldenOffset) % total];

            int srcIdx = srcPos[0] + srcPos[1] * width;
            int partnerIdx = partnerPos[0] + partnerPos[1] * width;

            // 混淆：像素从 src 位置移动到 partner 位置（与解混淆相反）
            result[partnerIdx] = pixels[srcIdx];
        }

        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        output.setPixels(result, 0, width, 0, 0, width, height);
        return output;
    }
}
