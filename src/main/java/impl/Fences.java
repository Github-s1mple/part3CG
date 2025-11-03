package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
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
}
