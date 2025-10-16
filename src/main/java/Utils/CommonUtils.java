package Utils;

import impl.Order;

import java.util.Comparator;
import java.util.List;

/**
 * 通用工具类，包含项目中常用的工具方法
 */
public class CommonUtils {

    /**
     * 获取当前时间（秒级）
     * 用于记录算法开始时间、计算耗时等
     * @return 当前时间的秒数表示（可能是时间戳/秒）
     */
    public static int currentTimeInSecond() {
        // 实现方式1：使用System.currentTimeMillis()转换为秒
        return (int) (System.currentTimeMillis() / 1000.0);
    }

    /**
     * 订单的对偶值比较器
     * 用于对orderPool进行排序，可能按对偶值降序排列
     */
    public static final Comparator<Order> dualComparator = (order1, order2) -> {
        // 假设Order类有getDualValue()方法获取对偶值
        double dual1 = order1.getDualValue();
        double dual2 = order2.getDualValue();

        // 降序排列（大的在前）
        return Double.compare(dual2, dual1);
    };

    /**
     * 格式化时间显示（可选辅助方法）
     * 用于将秒数转换为更易读的格式
     * @param seconds 秒数
     * @return 格式化的时间字符串（如 "0h1m3.5s"）
     */
    public static String formatTime(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) (seconds % 3600 / 60);
        double secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("h");
        if (minutes > 0) sb.append(minutes).append("m");
        sb.append(String.format("%.1fs", secs));

        return sb.toString();
    }

    /**
     * 计算两个点之间的距离（可选，根据业务场景）
     * 如果项目涉及地理信息或路径规划可能需要
     * @param x1 第一个点的x坐标
     * @param y1 第一个点的y坐标
     * @param x2 第二个点的x坐标
     * @param y2 第二个点的y坐标
     * @return 两点之间的距离
     */
    public static double calculateDistance(double x1, double y1, double x2, double y2) {
        // 欧氏距离
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    /**
     * 对列表进行分页（可选，根据业务需要）
     * @param list 原始列表
     * @param pageSize 每页大小
     * @param pageNum 页码（从1开始）
     * @return 分页后的子列表
     */
    public static <T> List<T> paginate(List<T> list, int pageSize, int pageNum) {
        if (list == null || list.isEmpty()) {
            return list;
        }

        int startIndex = (pageNum - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, list.size());

        if (startIndex >= endIndex) {
            return List.of();
        }

        return list.subList(startIndex, endIndex);
    }
}