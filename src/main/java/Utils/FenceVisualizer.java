package Utils;

import impl.Fence;
import impl.Instance;
import impl.LocationResult;
import org.knowm.xchart.SwingWrapper;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fence 列表可视化工具类 - 兼容低版本XChart（无setMarkerSize方法）
 * 底图：默认蓝色散点 | 高亮：红色散点 | 热力图：按deliverDemand映射颜色深浅 | 横轴经度/纵轴纬度
 */
public class FenceVisualizer {

    // 热力图颜色映射：需求最小值→浅蓝色，最大值→深红色
    private static final Color MIN_DEMAND_COLOR = new Color(170, 216, 255); // 浅蓝
    private static final Color MAX_DEMAND_COLOR = new Color(250, 19, 19);     // 深红

    /**
     * 【原有方法】仅绘制单组Fence点（保持兼容）
     * @param fenceList 待可视化的 Fence 列表
     */
    public static void plotFencePoints(List<Fence> fenceList) {
        // 1. 校验输入
        if (fenceList == null || fenceList.isEmpty()) {
            System.out.println("Fence列表为空，无法绘制！");
            return;
        }

        // 2. 提取经度（横轴）、纬度（纵轴）数据
        List<Double> longitudeData = new ArrayList<>(); // 横轴：经度
        List<Double> latitudeData = new ArrayList<>();  // 纵轴：纬度
        for (Fence fence : fenceList) {
            // 只保留有效经纬度数据
            if (fence.getLon() != null && fence.getLat() != null) {
                longitudeData.add(fence.getLon());
                latitudeData.add(fence.getLat());
            }
        }

        if (longitudeData.isEmpty()) {
            System.out.println("无有效经纬度数据！");
            return;
        }

        // 3. 创建图表：明确指定横轴经度、纵轴纬度
        XYChart chart = new XYChartBuilder()
                .width(800)  // 图表宽度
                .height(600) // 图表高度
                .title("Fence经纬度分布")
                .xAxisTitle("经度")  // 横轴：经度
                .yAxisTitle("纬度")  // 纵轴：纬度
                .theme(Styler.ChartTheme.Matlab) // 简洁主题
                .build();

        // 4. 添加数据系列：仅打点，无多余样式
        XYSeries series = chart.addSeries("Fence点", longitudeData, latitudeData);
        series.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter); // 关键：仅渲染散点，无连线
        series.setMarker(SeriesMarkers.CIRCLE); // 标记为圆形

        // 低版本XChart：通过Styler统一设置标记大小
        chart.getStyler().setMarkerSize(5);

        // 5. 显示图表（仅展示点的位置）
        new SwingWrapper(chart).displayChart();
    }

    /**
     * 绘制底图+标红高亮的Fence对比图（原有功能）
     * @param baseFenceList 底图Fence列表（默认蓝色）
     * @param highlightFenceList 需标红的Fence列表（红色高亮）
     */
    public static void plotFenceWithHighlight(ArrayList<Fence> baseFenceList, List<Fence> highlightFenceList) {
        // 1. 校验底图数据
        if (baseFenceList == null || baseFenceList.isEmpty()) {
            System.out.println("底图Fence列表为空，无法绘制！");
            return;
        }

        // 2. 提取底图经纬度数据
        List<Double> baseLon = new ArrayList<>();
        List<Double> baseLat = new ArrayList<>();
        for (Fence fence : baseFenceList) {
            if (fence.getLon() != null && fence.getLat() != null) {
                baseLon.add(fence.getLon());
                baseLat.add(fence.getLat());
            }
        }
        if (baseLon.isEmpty()) {
            System.out.println("底图无有效经纬度数据！");
            return;
        }

        // 3. 创建图表
        XYChart chart = new XYChartBuilder()
                .width(800)
                .height(600)
                .title("Fence经纬度分布（红色为目标点）")
                .xAxisTitle("经度")
                .yAxisTitle("纬度")
                .theme(Styler.ChartTheme.Matlab)
                .build();

        // 低版本XChart：通过Styler设置全局标记大小（底图用此大小）
        chart.getStyler().setMarkerSize(4); // 底图点大小

        // 4. 添加底图系列（默认蓝色，圆形散点，无连线）
        XYSeries baseSeries = chart.addSeries("底图Fence", baseLon, baseLat);
        baseSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
        baseSeries.setMarker(SeriesMarkers.CIRCLE);
        baseSeries.setMarkerColor(Color.BLUE); // 底图固定为蓝色

        // 5. 添加高亮系列（红色，圆形散点，无连线）
        if (highlightFenceList != null && !highlightFenceList.isEmpty()) {
            List<Double> highlightLon = new ArrayList<>();
            List<Double> highlightLat = new ArrayList<>();
            for (Fence fence : highlightFenceList) {
                if (fence.getLon() != null && fence.getLat() != null) {
                    highlightLon.add(fence.getLon());
                    highlightLat.add(fence.getLat());
                }
            }
            if (!highlightLon.isEmpty()) {
                XYSeries highlightSeries = chart.addSeries("目标Fence（红）", highlightLon, highlightLat);
                highlightSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
                highlightSeries.setMarker(SeriesMarkers.DIAMOND); // 用菱形区分高亮点（替代大小）
                highlightSeries.setMarkerColor(Color.RED); // 高亮点固定为红色
            }
        }

        // 6. 显示图表
        new SwingWrapper(chart).displayChart();
    }

    /**
     * 【新增核心功能】绘制基于deliverDemand的热力图
     * @param fenceList 待可视化的Fence列表
     * @param title 图表标题（可自定义，如"配送需求热力图-第一阶段"）
     */
    public static void plotFenceHeatMapByDeliverDemand(List<Fence> fenceList, String title) {
        // 1. 输入校验
        if (fenceList == null || fenceList.isEmpty()) {
            System.out.println("Fence列表为空，无法绘制热力图！");
            return;
        }

        // 2. 过滤有效数据（经纬度+配送需求非空）
        List<Fence> validFences = fenceList.stream()
                .filter(f -> f.getLon() != null && f.getLat() != null && f.getDeliverDemand() != null)
                .collect(Collectors.toList());

        if (validFences.isEmpty()) {
            System.out.println("无有效经纬度/配送需求数据！");
            return;
        }

        // 3. 计算配送需求的最大值/最小值（用于颜色归一化）
        double minDemand = validFences.stream().mapToDouble(Fence::getDeliverDemand).min().getAsDouble();
        double maxDemand = validFences.stream().mapToDouble(Fence::getDeliverDemand).max().getAsDouble();
        double demandRange = maxDemand - minDemand;

        // 4. 创建图表
        XYChart chart = new XYChartBuilder()
                .width(1000)  // 热力图加宽，提升可读性
                .height(800)
                .title(title == null || title.isEmpty() ? "Fence配送需求热力图" : title)
                .xAxisTitle("经度")
                .yAxisTitle("纬度")
                .theme(Styler.ChartTheme.Matlab)
                .build();
        chart.getStyler().setMarkerSize(6); // 热力图点稍大，更易看颜色
        chart.getStyler().setLegendVisible(true); // 显示图例

        // 5. 按配送需求分组，映射不同颜色（核心热力逻辑）
        // 方案：按需求区间拆分为多个系列，每个系列对应不同颜色（兼容XChart单系列单颜色限制）
        // 简化版：分5个区间，覆盖从低到高的需求
        int intervalCount = 5;
        double intervalStep = demandRange / intervalCount;

        for (int i = 0; i < intervalCount; i++) {
            double lower = minDemand + i * intervalStep;
            double upper = (i == intervalCount - 1) ? maxDemand : minDemand + (i + 1) * intervalStep;

            // 筛选当前区间的围栏
            List<Fence> intervalFences = validFences.stream()
                    .filter(f -> f.getDeliverDemand() >= lower && f.getDeliverDemand() <= upper)
                    .collect(Collectors.toList());
            if (intervalFences.isEmpty()) continue;

            // 提取经纬度
            List<Double> lonList = intervalFences.stream().map(Fence::getLon).collect(Collectors.toList());
            List<Double> latList = intervalFences.stream().map(Fence::getLat).collect(Collectors.toList());

            // 计算当前区间的颜色（从浅蓝→深红渐变）
            double ratio = (lower - minDemand) / demandRange;
            Color intervalColor = interpolateColor(MIN_DEMAND_COLOR, MAX_DEMAND_COLOR, ratio);

            // 添加系列：图例显示需求区间，颜色对应热力
            String seriesName = String.format("需求 %.0f-%.0f", lower, upper);
            XYSeries series = chart.addSeries(seriesName, lonList, latList);
            series.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
            series.setMarker(SeriesMarkers.CIRCLE);
            series.setMarkerColor(intervalColor);
        }

        // 6. 显示热力图
        new SwingWrapper(chart).displayChart();
    }

    /**
     * 【简化版热力图】直接传入底图+高亮，同时显示热力（兼容原有调用逻辑）
     * @param baseFenceList 底图Fence列表（按deliverDemand显示热力）
     * @param highlightFenceList 标红高亮的Fence列表
     * @param title 图表标题
     */
    public static void plotFenceHeatMapWithHighlight(ArrayList<Fence> baseFenceList, List<Fence> highlightFenceList, String title) {
        // 1. 校验底图数据
        if (baseFenceList == null || baseFenceList.isEmpty()) {
            System.out.println("底图Fence列表为空，无法绘制！");
            return;
        }

        // 2. 计算底图需求的最大/最小值
        List<Fence> validBaseFences = baseFenceList.stream()
                .filter(f -> f.getLon() != null && f.getLat() != null && f.getDeliverDemand() != null)
                .collect(Collectors.toList());
        if (validBaseFences.isEmpty()) {
            System.out.println("底图无有效经纬度/配送需求数据！");
            return;
        }
        double minDemand = validBaseFences.stream().mapToDouble(Fence::getDeliverDemand).min().getAsDouble();
        double maxDemand = validBaseFences.stream().mapToDouble(Fence::getDeliverDemand).max().getAsDouble();
        double demandRange = maxDemand - minDemand;

        // 3. 创建图表
        XYChart chart = new XYChartBuilder()
                .width(1000)
                .height(800)
                .title(title == null || title.isEmpty() ? "Fence热力图（红色为目标点）" : title)
                .xAxisTitle("经度")
                .yAxisTitle("纬度")
                .theme(Styler.ChartTheme.Matlab)
                .build();
        chart.getStyler().setMarkerSize(5);
        chart.getStyler().setLegendVisible(true);

        // 4. 添加底图热力系列（按需求颜色渐变）
        for (Fence fence : validBaseFences) {
            // 单个点作为临时系列（兼容XChart单系列单颜色，牺牲性能换热力效果）
            List<Double> lon = List.of(fence.getLon());
            List<Double> lat = List.of(fence.getLat());
            double ratio = (fence.getDeliverDemand() - minDemand) / demandRange;
            Color pointColor = interpolateColor(MIN_DEMAND_COLOR, MAX_DEMAND_COLOR, ratio);

            // 系列名简化为需求值，避免图例冗余
            String seriesName = String.format("需求 %d", fence.getIndex());
            XYSeries baseSeries = chart.addSeries(seriesName, lon, lat);
            baseSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
            baseSeries.setMarker(SeriesMarkers.CIRCLE);
            baseSeries.setMarkerColor(pointColor);
        }

        // 5. 添加高亮系列（红色菱形，层级高于热力）
        if (highlightFenceList != null && !highlightFenceList.isEmpty()) {
            List<Double> highlightLon = new ArrayList<>();
            List<Double> highlightLat = new ArrayList<>();
            for (Fence fence : highlightFenceList) {
                if (fence.getLon() != null && fence.getLat() != null) {
                    highlightLon.add(fence.getLon());
                    highlightLat.add(fence.getLat());
                }
            }
            if (!highlightLon.isEmpty()) {
                XYSeries highlightSeries = chart.addSeries("目标Fence（红）", highlightLon, highlightLat);
                highlightSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
                highlightSeries.setMarker(SeriesMarkers.DIAMOND);
                highlightSeries.setMarkerColor(Color.RED);
            }
        }

        // 6. 显示图表
        new SwingWrapper(chart).displayChart();
    }

    // ====================== 工具方法 ======================
    /**
     * 颜色插值：根据比例在两个颜色之间渐变（核心热力图颜色逻辑）
     * @param startColor 起始色（低需求）
     * @param endColor 结束色（高需求）
     * @param ratio 比例（0→startColor，1→endColor）
     * @return 渐变后的颜色
     */
    private static Color interpolateColor(Color startColor, Color endColor, double ratio) {
        // 限制比例在0-1之间
        ratio = Math.max(0, Math.min(1, ratio));

        int r = (int) (startColor.getRed() + ratio * (endColor.getRed() - startColor.getRed()));
        int g = (int) (startColor.getGreen() + ratio * (endColor.getGreen() - startColor.getGreen()));
        int b = (int) (startColor.getBlue() + ratio * (endColor.getBlue() - startColor.getBlue()));

        return new Color(r, g, b);
    }

    // 测试方法（演示热力图+标红功能）
    public static void main(String[] args) {
        // 1. 加载底图Fence列表（原有逻辑）
        LocationResult firstStageResult = ResultPersistenceUtil.loadFirstStageResult("firstStageResult.json");
        Instance instance = new Instance(firstStageResult);
        ArrayList<Fence> baseFenceList = instance.getFences().getFenceList();

        // 2. 模拟标红的Fence列表
        List<Fence> highlightFenceList = new ArrayList<>();
        if (baseFenceList.size() >= 10) {
            highlightFenceList = baseFenceList.subList(0, 10);
        }

        // 3. 演示1：仅绘制热力图
        plotFenceHeatMapByDeliverDemand(baseFenceList, "第一阶段配送需求热力图");

        // 4. 演示2：热力图+标红高亮（推荐）
        plotFenceHeatMapWithHighlight(baseFenceList, highlightFenceList, "配送需求热力图（红色为目标围栏）");
    }
}