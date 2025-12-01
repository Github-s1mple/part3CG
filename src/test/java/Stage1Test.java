import baseinfo.Constants;
import com.gurobi.gurobi.GRBException;
import impl.*;
import stage1.FirstStageLocationModel;

import java.util.List;
import java.util.Map;

public class Stage1Test {
    public static void main(String[] args) throws GRBException {
        // 1. 构造测试输入数据（替换为你的实际数据）
        InputData input = new InputData();
        // 2. 初始化并构建模型
        FirstStageLocationModel model = new FirstStageLocationModel(input);
        model.setOutputFlag(true);  // 开启详细输出
        model.defineVariables();    // 定义变量
        model.setObjective();       // 设置目标函数
        model.addCoreConstraints(); // 添加约束

        // 3. 求解并获取结果
        LocationResult result = model.solve();

        // 4. 输出传递给第二阶段的核心信息
        if (result != null) {
            System.out.println("\n【传递给第二阶段的信息】");
            System.out.println("========================================");
            System.out.println("选中的候选点：" + result.getSelectedCandidates());
            System.out.println("基础需求分配：" + result.getBaseAllocation());
            System.out.println("额外需求分配：" + result.getExtraAllocation());
            System.out.println("========================================");
        }
    }
}
