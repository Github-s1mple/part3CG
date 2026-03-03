package impl;

import baseinfo.Constants;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Setter
@Getter
public class Fences {
    private ArrayList<Fence> fenceList;
    private ArrayList<Integer> fenceIndexList;
    private int fenceNum;

    public Fences(){
        fenceList = new ArrayList<Fence>();
        fenceIndexList = new ArrayList<Integer>();
        fenceNum = 0;
    }

    public Fence getFence(Integer fenceIndex) {
        for (Fence fence : fenceList) {
            if (Objects.equals(fence.getIndex(), fenceIndex)) {
                return fence;
            }
        }
        System.err.println("解析围栏 " + fenceIndex + " 失败");
        return null;
    }

    public void generateFenceIndexList() {
        if (fenceList != null) {
            for (Fence fence : fenceList) {
                fenceIndexList.add(fence.getIndex());
                fenceNum += 1;
            }
        }
    }

    public void addFence(Fence fence) {
        this.fenceList.add(fence);
    }

    public int size() {
        return  this.fenceList.size();
    }

    public void sortValidArcFenceByOriginalValue(Fence targetFence) {
        // 1. 入参校验
        if (targetFence == null) {
            return;
        }

        // 目标围栏的class（double类型）
        double targetFenceClass = targetFence.getFClass();

        ArrayList<Integer> validArcFence = targetFence.getValidArcFence();
        if (validArcFence == null || validArcFence.isEmpty()) {
            return;
        }
        if (fenceList.isEmpty()) {
            return;
        }

        // 2. 第一步：过滤 - 修正class对比逻辑（统一double类型 + 浮点精度）
        int originalSize = validArcFence.size();
        Iterator<Integer> iterator = validArcFence.iterator();
        // 统计过滤原因，便于排查
        int nullFenceCount = 0;
        int diffClassCount = 0;
        int lowDemandCount = 0;

        while (iterator.hasNext()) {
            Integer fenceId = iterator.next();
            Fence fence = getFence(fenceId);

            // 处理无效围栏/空值：直接移除
            if (fence == null || fence.getDeliverDemand() == null) {
                iterator.remove();
                nullFenceCount++;
                continue;
            }

            // 核心修正：用double类型的getFClass()对比，且处理浮点精度
            double currentFenceClass = fence.getFClass();
            // 浮点值对比：误差小于1e-6视为相等
            if (Math.abs(currentFenceClass - targetFenceClass) > 1e-6) {
                iterator.remove();
                diffClassCount++;
                continue;
            }

            // 原有核心过滤逻辑：deliverDemand < MIN_DISPATCH_NUM 则移除
            if (fence.getDeliverDemand() < Constants.MIN_DISPATCH_NUM) {
                iterator.remove();
                lowDemandCount++;
            }
        }

        // 过滤后为空，直接返回
        if (validArcFence.isEmpty()) {
            targetFence.setValidArcFence(validArcFence);
            return;
        }

        // 3. 第二步：排序 - 修正class校验逻辑（浮点精度）
        validArcFence.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer fenceId1, Integer fenceId2) {
                Fence fence1 = getFence(fenceId1);
                Fence fence2 = getFence(fenceId2);

                // 处理无效围栏（空值）：放到列表最后
                if (fence1 == null && fence2 == null) return 0;
                if (fence1 == null) return 1;
                if (fence2 == null) return -1;

                // 修正：排序前校验class（浮点精度）
                double class1 = fence1.getFClass();
                double class2 = fence2.getFClass();
                // 不同class的放到后面
                if (Math.abs(class1 - targetFenceClass) > 1e-6) return 1;
                if (Math.abs(class2 - targetFenceClass) > 1e-6) return -1;

                // 获取originalFenceValue（空值默认0）
                double value1 = fence1.getFenceValue() == 0.0 ? fence1.getOriginalFenceValue() : fence1.getFenceValue();
                double value2 = fence2.getFenceValue() == 0.0 ? fence2.getOriginalFenceValue() : fence2.getFenceValue();

                value1 = value1 * fence1.getDeliverDemand();
                value2 = value2 * fence2.getDeliverDemand();
                // 降序排序（处理浮点精度）
                if (Math.abs(value2 - value1) < 1e-6) {
                    return 0;
                }
                return value2 < value1 ? -1 : 1; // 大值在前
            }
        });

        // 4. 重新赋值
        targetFence.setValidArcFence(validArcFence);
    }

    public void sortValidArcFenceByOriginalValue(Depot depot) {
        // 1. 入参校验
        if (depot == null) {
            return;
        }

        // 获取仓库的class值（double类型，和Fence的FClass对应）
        double depotFClass = depot.getDClass();

        ArrayList<Integer> validArcFence = depot.getValidArcFence();
        if (validArcFence == null || validArcFence.isEmpty()) {
            return;
        }
        if (fenceList.isEmpty()) {
            return;
        }

        // 2. 第一步：过滤 - 新增class相等判断（浮点精度） + 原有deliverDemand过滤
        int originalSize = validArcFence.size();
        Iterator<Integer> iterator = validArcFence.iterator();
        // 统计过滤原因，便于排查
        int nullFenceCount = 0;
        int diffClassCount = 0;
        int lowDemandCount = 0;

        while (iterator.hasNext()) {
            Integer fenceId = iterator.next();
            Fence fence = getFence(fenceId);

            // 处理无效围栏/空值：直接移除
            if (fence == null || fence.getDeliverDemand() == null) {
                iterator.remove();
                nullFenceCount++;
                continue;
            }

            // 核心新增：过滤不同class的围栏（double类型 + 浮点精度）
            double currentFenceClass = fence.getFClass();
            if (Math.abs(currentFenceClass - depotFClass) > 1e-6) {
                iterator.remove();
                diffClassCount++;
                continue;
            }

            // 原有核心过滤逻辑：deliverDemand < MIN_DISPATCH_NUM 则移除
            if (fence.getDeliverDemand() < Constants.MIN_DISPATCH_NUM) {
                iterator.remove();
                lowDemandCount++;
            }
        }

        // 过滤后为空，直接返回
        if (validArcFence.isEmpty()) {
            depot.setValidArcFence(validArcFence);
            return;
        }

        // 3. 第二步：排序 - 按originalFenceValue降序（新增class校验兜底）
        validArcFence.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer fenceId1, Integer fenceId2) {
                Fence fence1 = getFence(fenceId1);
                Fence fence2 = getFence(fenceId2);

                // 处理无效围栏（空值）：放到列表最后
                if (fence1 == null && fence2 == null) return 0;
                if (fence1 == null) return 1;
                if (fence2 == null) return -1;

                // 新增：排序前再次校验class（防止极端情况）
                double class1 = fence1.getFClass();
                double class2 = fence2.getFClass();
                if (Math.abs(class1 - depotFClass) > 1e-6) return 1;
                if (Math.abs(class2 - depotFClass) > 1e-6) return -1;

                // 获取originalFenceValue（空值默认0）
                double value1 = fence1.getFenceValue() == 0.0 ? fence1.getOriginalFenceValue() : fence1.getFenceValue();
                double value2 = fence2.getFenceValue() == 0.0 ? fence2.getOriginalFenceValue() : fence2.getFenceValue();

                value1 = value1 * fence1.getDeliverDemand();
                value2 = value2 * fence2.getDeliverDemand();
                // 降序排序（处理浮点精度）
                if (Math.abs(value2 - value1) < 1e-6) {
                    return 0;
                }
                return value2 < value1 ? -1 : 1; // 大值在前
            }
        });

        // 4. 重新赋值
        depot.setValidArcFence(validArcFence);
    }

    public void SortValidArcFenceByOriginalValue() {
        if (fenceList.isEmpty()) {
            return;
        }
        for (Fence fence : fenceList) {
            if(fence.getIndex() == 999) continue;
            sortValidArcFenceByOriginalValue(fence);
        }
    }

    public List<Fence> getFencesByIndexList(ArrayList<Integer> fenceIndexList) {
        // 1. 入参校验：空列表直接返回空结果
        List<Fence> result = new ArrayList<>();
        if (fenceIndexList == null || fenceIndexList.isEmpty()) {
            System.err.println("输入的围栏编号列表为空");
            return result;
        }

        // 2. 遍历编号列表，匹配对应的Fence对象
        for (Integer fenceIndex : fenceIndexList) {
            // 跳过空编号
            if (fenceIndex == null) {
                System.err.println("检测到空的围栏编号，已跳过");
                continue;
            }
            // 通过getFence方法获取围栏对象
            Fence fence = getFence(fenceIndex);
            if (fence != null) {
                result.add(fence);
            } else {
                // 仅打印警告，不中断流程（保证有效数据仍能返回）
                System.err.println("围栏编号 " + fenceIndex + " 不存在，已跳过");
            }
        }

        return result;
    }
}