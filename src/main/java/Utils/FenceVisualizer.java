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

/**
 * Fence 列表可视化工具类 - 兼容低版本XChart（无setMarkerSize方法）
 * 底图：默认蓝色散点 | 高亮：红色散点 | 无连线 | 横轴经度/纵轴纬度
 */
public class FenceVisualizer {

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
     * 绘制底图+标红高亮的Fence对比图
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

    // 测试方法（演示如何使用底图+标红功能）
    public static void main(String[] args) {
        // 1. 加载底图Fence列表（原有逻辑）
        LocationResult firstStageResult = ResultPersistenceUtil.loadFirstStageResult("firstStageResult.json");
        Instance instance = new Instance(firstStageResult);
        ArrayList<Fence> baseFenceList = instance.getFences().getFenceList();

        // 2. 模拟你要标红的另一组Fence（替换为你的实际数据）
        List<Fence> highlightFenceList = new ArrayList<>();
        // 示例：从底图中筛选前10个作为标红示例（你可替换为自己的目标列表）
        if (baseFenceList.size() >= 10) {
            highlightFenceList = baseFenceList.subList(0, 10);
        }

        // 3. 调用新增方法，绘制底图+标红的对比图
        FenceVisualizer.plotFenceWithHighlight(baseFenceList, highlightFenceList);
    }
}