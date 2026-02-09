package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

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
            System.err.println("目标围栏不能为空！");
            return;
        }
        ArrayList<Integer> validArcFence = targetFence.getValidArcFence();
        if (validArcFence == null || validArcFence.isEmpty()) {
            System.err.println("围栏" + targetFence.getIndex() + "的validArcFence为空，无需排序");
            return;
        }
        if (fenceList.isEmpty()) {
            System.err.println("围栏集合为空，无法排序！");
            return;
        }

        // 2. 核心排序逻辑：按originalFenceValue降序
        validArcFence.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer fenceId1, Integer fenceId2) {
                // 从Fences集合中获取围栏对象
                Fence fence1 = getFence(fenceId1);
                Fence fence2 = getFence(fenceId2);

                // 处理无效围栏（空值）：放到列表最后
                if (fence1 == null && fence2 == null) return 0;
                if (fence1 == null) return 1;
                if (fence2 == null) return -1;

                // 获取originalFenceValue（空值默认0）
                double value1 = fence1.getOriginalFenceValue() == null ? 0.0 : fence1.getOriginalFenceValue();
                double value2 = fence2.getOriginalFenceValue() == null ? 0.0 : fence2.getOriginalFenceValue();

                // 降序排序（处理浮点精度）
                if (Math.abs(value2 - value1) < 1e-6) {
                    return 0;
                }
                return value2 > value1 ? -1 : 1; // 大值在前
            }
        });

        // 3. 重新赋值（确保引用生效）+ 日志输出
        targetFence.setValidArcFence(validArcFence);
    }

    public void sortValidArcFenceByOriginalValue(Depot depot) {

        ArrayList<Integer> validArcFence = depot.getValidArcFence();
        if (validArcFence == null || validArcFence.isEmpty()) {
            System.err.println("围栏" + depot.getIndex() + "的validArcFence为空，无需排序");
            return;
        }
        if (fenceList.isEmpty()) {
            System.err.println("围栏集合为空，无法排序！");
            return;
        }

        // 2. 核心排序逻辑：按originalFenceValue降序
        validArcFence.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer fenceId1, Integer fenceId2) {
                // 从Fences集合中获取围栏对象
                Fence fence1 = getFence(fenceId1);
                Fence fence2 = getFence(fenceId2);

                // 处理无效围栏（空值）：放到列表最后
                if (fence1 == null && fence2 == null) return 0;
                if (fence1 == null) return 1;
                if (fence2 == null) return -1;

                // 获取originalFenceValue（空值默认0）
                double value1 = fence1.getOriginalFenceValue() == null ? 0.0 : fence1.getOriginalFenceValue();
                double value2 = fence2.getOriginalFenceValue() == null ? 0.0 : fence2.getOriginalFenceValue();

                // 降序排序（处理浮点精度）
                if (Math.abs(value2 - value1) < 1e-6) {
                    return 0;
                }
                return value2 > value1 ? -1 : 1; // 大值在前
            }
        });

        // 3. 重新赋值（确保引用生效）+ 日志输出
        depot.setValidArcFence(validArcFence);
    }

    public void SortValidArcFenceByOriginalValue() {
        if (fenceList.isEmpty()) {
            System.err.println("围栏集合为空，无法批量排序！");
            return;
        }
        for (Fence fence : fenceList) {
            sortValidArcFenceByOriginalValue(fence);
        }
    }
}