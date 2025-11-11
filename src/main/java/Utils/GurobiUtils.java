package Utils;

import com.gurobi.gurobi.*;
import impl.*;

import java.util.*;

public class GurobiUtils {
    // Gurobi核心对象
    private GRBEnv env;
    private GRBModel model;
    // 约束缓存
    private Map<String, GRBConstr> constrMap = new HashMap<>();

    public GurobiUtils(GRBEnv env, GRBModel model, Map<String, GRBConstr> constrMap){
        this.env = env;
        this.model = model;
        this.constrMap = constrMap;
    }


    /**
     * 计算并输出导致模型无可行解的冲突约束（基于官方IIS API）
     */
    public void printConflictConstraints() throws GRBException {
        System.out.println("\n========================================");
        System.out.println("【模型无可行解，正在分析冲突约束（IIS）...】");
        System.out.println("========================================");

        // 1. 计算不可行性诱导集合（IIS）：官方API调用，无返回值（通过属性查询结果）
        model.computeIIS();
        // 将冲突约束保存为.ilp文件（用Gurobi Studio打开可查看详细约束内容）
        //model.write("conflict_iis.ilp");
        //System.out.println("已将冲突约束保存到文件：conflict_iis.ilp");

        // 2. 收集并分类冲突约束（通过查询约束的IISConstr属性：1=在冲突集合中，0=不在）
        List<String> departureConflicts = new ArrayList<>();   // 出发约束冲突
        List<String> returnConflicts = new ArrayList<>();     // 返回约束冲突
        List<String> depotDepotConflicts = new ArrayList<>(); // 仓库间禁止约束冲突
        List<String> flowConflicts = new ArrayList<>();       // 流量守恒约束冲突
        List<String> otherConflicts = new ArrayList<>();      // 其他约束冲突

        // 遍历所有缓存的约束，查询IISConstr属性
        for (Map.Entry<String, GRBConstr> entry : constrMap.entrySet()) {
            String constrName = entry.getKey();
            GRBConstr constr = entry.getValue();

            // 关键：查询约束是否在IIS中（官方属性：IISConstr=1表示是冲突约束）
            int isInIIS = constr.get(GRB.IntAttr.IISConstr);
            if (isInIIS == 1) {
                // 根据约束命名规则分类（匹配我们定义的约束名前缀）
                if (constrName.startsWith("depart_")) {
                    departureConflicts.add(constrName);
                } else if (constrName.startsWith("return_")) {
                    returnConflicts.add(constrName);
                } else if (constrName.startsWith("forbid_depot_")) {
                    depotDepotConflicts.add(constrName);
                } else if (constrName.startsWith("flow_")) {
                    flowConflicts.add(constrName);
                } else {
                    otherConflicts.add(constrName);
                }
            }
        }

        // 3. 输出各类冲突约束（带原因分析）
        printConflictCategory("1. 载具出发约束冲突", departureConflicts,
                "约束逻辑：载具必须从所属仓库出发到围栏\n" +
                        "可能原因：1.载具所属仓库ID错误 2.无可用围栏路径 3.约束强制路径数=1但无可行路径");

        printConflictCategory("2. 载具返回约束冲突", returnConflicts,
                "约束逻辑：载具必须从围栏返回所属仓库\n" +
                        "可能原因：1.载具所属仓库ID错误 2.无围栏返回仓库的路径 3.约束强制路径数=1但无可行路径");

        printConflictCategory("3. 仓库间路径禁止约束冲突", depotDepotConflicts,
                "约束逻辑：禁止仓库→仓库的路径\n" +
                        "可能原因：其他约束（如出发/返回）迫使模型必须走仓库间路径，导致冲突");

        printConflictCategory("4. 流量守恒约束冲突", flowConflicts,
                "约束逻辑：围栏的进入路径数=离开路径数\n" +
                        "可能原因：路径不连续（如只有入边无出边，或反之）");

        printConflictCategory("5. 其他冲突约束", otherConflicts,
                "包含：访问-路径关联、装载-访问关联、载具容量、围栏需求、MTZ约束等");
    }


    /**
     * 辅助方法：格式化输出某一类冲突约束
     */
    public void printConflictCategory(String title, List<String> conflictConstrs, String reason) {
        System.out.println("\n" + title + "：");
        System.out.println("  约束说明：" + reason);
        if (conflictConstrs.isEmpty()) {
            System.out.println("  无冲突约束");
        } else {
            System.out.println("  冲突约束列表（共" + conflictConstrs.size() + "条）：");
            for (String constr : conflictConstrs) {
                System.out.println("    - " + constr);
            }
        }
    }


    /**
     * 将Gurobi求解状态码转换为描述文字
     */
    public static String getStatusDescription(int status) {
        return switch (status) {
            case GRB.Status.OPTIMAL -> "最优解（已找到全局最优解）";
            case GRB.Status.SUBOPTIMAL -> "次优解（因时间或精度限制，未找到全局最优）";
            case GRB.Status.INFEASIBLE -> "无可行解（约束冲突）";
            case GRB.Status.UNBOUNDED -> "无界解（目标函数可无限增大）";
            case GRB.Status.TIME_LIMIT -> "时间限制终止";
            default -> "未知状态（状态码：" + status + "）";
        };
    }
}
