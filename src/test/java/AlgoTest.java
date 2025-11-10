import algo.CGSolve;
import impl.Instance;

public class AlgoTest {
    public static void main(String[] args) {
        Instance instance = new Instance();
        new CGSolve().solve(instance);
    }
}
