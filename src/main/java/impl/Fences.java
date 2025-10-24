package impl;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class Fences {
    private ArrayList<Fence> fenceList;
    private ArrayList<Integer> fenceIndexList;
    private int fenceNum;
    public Fence getFence(Integer fenceIndex) {
        for (Fence fence : fenceList) {
            if (fence.getIndex() == fenceIndex) {
                return fence;
            }
        }
        return null;
    }

    public void addFence(Fence fence) {
        this.fenceList.add(fence);
    }

    public int size() {
        return  this.fenceList.size();
    }
}
