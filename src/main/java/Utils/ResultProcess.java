package Utils;

import impl.Order;
import impl.Orders;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ResultProcess {
    private final Orders orders;
    private final DecimalFormat df = new DecimalFormat("0.00");
    public ResultProcess(Orders orders){
        this.orders = orders;
    }

    public void showOrderDetail() {
        System.out.println("\n【订单路径径详情】");
        System.out.println("========================================");

        for (Order order : orders.getOrderList()) {
            System.out.printf("订单ID：%d（所属仓库：%d）%n", order.getOrderId(), order.getDepot());
            System.out.println("----------------------------------------");
            System.out.printf("总路径距离：%s 米%n", df.format(order.getDistance()));
            System.out.println("详细路径（含装载量）：");

            // 获取围栏顺序列表和装载量映射
            ArrayList<Integer> fenceList = order.getFenceList();
            HashMap<Integer, Double> loads = order.getLoads();

            // 路径起点：所属仓库
            System.out.printf("  起点：仓库%d（出发）%n", order.getDepot());

            // 路径中途：按顺序输出每个围栏及对应装载量
            if (fenceList != null && !fenceList.isEmpty()) {
                for (int i = 0; i < fenceList.size(); i++) {
                    int fenceId = fenceList.get(i);
                    // 从装载量映射中获取当前围栏的装载量（默认0.00）
                    double load = loads != null ? loads.getOrDefault(fenceId, 0.0) : 0.0;
                    // 输出格式：序号. 围栏ID（装载量）
                    System.out.printf("  %d. 围栏%d（装载量：%s）%n",
                            i + 1, fenceId, df.format(load));
                }
            } else {
                System.out.println("  路径中途：无访问围栏");
            }

            // 路径终点：返回所属仓库
            System.out.printf("  终点：仓库%d（返回）%n", order.getDepot());

            System.out.println("----------------------------------------");
        }
    }
}
