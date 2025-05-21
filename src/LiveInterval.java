
public class LiveInterval {
    public String varName;
    public int start;
    public int end;
    public LiveInterval(String varName, int start, int end) {
        this.varName = varName;
        this.start = start;
        this.end = end;
    }
}