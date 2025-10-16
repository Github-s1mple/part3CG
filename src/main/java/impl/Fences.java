package impl;

import java.util.ArrayList;

public class Fences {
    private ArrayList<Fence> fenceList;
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
}
