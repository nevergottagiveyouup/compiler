import org.llvm4j.llvm4j.Value; // 确保导入 Value

// 导入常量相关的类
// 导入类型相关的类
import org.llvm4j.llvm4j.Type;
// 导入构建器相关的类
import org.llvm4j.llvm4j.IRBuilder;
// 导入运算相关的枚举
// 导入 Result 相关的类和方法
import org.llvm4j.optional.Some;
import java.util.*;


public interface  AllocateRegister {
    Value allocateRegister();
}

// 分配到栈
class AllocateToStack implements AllocateRegister {
    private IRBuilder builder;

    public AllocateToStack(IRBuilder builder) {
        this.builder = builder;
    }

    @Override
    public Value allocateRegister() {
        return null;
    }

}

// 线性扫描
class LinearScan implements AllocateRegister {
    private Map<String, LiveInterval> liveIntervals;  // 生命周期信息
    private int maxRegisters = 22;  // 默认可用寄存器数

    //寄存器状态
    private List<String> availableRegisters = new ArrayList<>();
    private static final String[] REGISTER_NAMES = {// 临时寄存器
            "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8","a9","a10","a11",  // 参数寄存器(除去a0)
            "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11" // 保存寄存器
    };

    //指令编号-变量位置表
    private Map<Integer, Map<String, Location>> registerAllocationByInst = new HashMap<>();

    // 变量-寄存器映射表（针对当前在寄存器中的变量）
    private Map<String, String> registerAllocation = new HashMap<>();

    // 溢出变量集合（仅记录哪些变量需要溢出）
    public Set<String> spilledVars = new HashSet<>();

    public LinearScan(Map<String, LiveInterval> liveIntervals) {
        this.liveIntervals = liveIntervals;
    }

    @Override
    public Value allocateRegister() {
        if (liveIntervals.isEmpty()) {
            return null;
        }

        // 初始化可用寄存器列表
        availableRegisters = Arrays.asList(REGISTER_NAMES).subList(0, maxRegisters);

        // 按开始位置排序所有区间
        List<LiveInterval> sortedIntervals = new ArrayList<>(liveIntervals.values());
        Collections.sort(sortedIntervals, Comparator.comparingInt(i -> i.start));

        // 初始化寄存器分配状态
        registerAllocation.clear();  // 清空原有分配
        spilledVars.clear();         // 清空溢出变量

        // 初始化每个指令点的映射
        registerAllocationByInst.clear();  // 清空原有映射
        int maxInstIndex = sortedIntervals.stream()
                .mapToInt(i -> i.end)
                .max()
                .orElse(0);

        for (int i = 0; i <= maxInstIndex; i++) {
            registerAllocationByInst.put(i, new HashMap<>());
        }

        // 活跃列表(按结束位置排序)
        List<LiveInterval> active = new ArrayList<>();
        // 可用寄存器列表
        List<String> free = new ArrayList<>(availableRegisters);

        for (LiveInterval interval : sortedIntervals) {
            // 处理每个指令位置（清理不再活跃的变量）
            expireOldIntervals(interval, active, free);

            if (active.size() >= availableRegisters.size()) {
                // 需要溢出
                spillAtInterval(interval, active);
            } else {
                // 分配寄存器
                String reg = free.remove(0);
                registerAllocation.put(interval.varName, reg);

                // 加入活跃列表
                active.add(interval);
                Collections.sort(active, Comparator.comparingInt(i -> i.end));
            }

            // 更新指令级映射
            updateLocationForInterval(interval);
        }

        return null;
    }

    // 更新指令级别的变量位置映射
    private void updateLocationForInterval(LiveInterval interval) {
        String varName = interval.varName;
        Location location;

        if (registerAllocation.containsKey(varName)) {
            // 变量在寄存器中
            location = new Location(registerAllocation.get(varName));
        } else if (spilledVars.contains(varName)) {
            // 变量标记为溢出，创建栈类型的位置标记
            location = new Location(-1); // 使用-1表示栈位置，具体偏移由翻译器决定
        } else {
            // 不应该出现这种情况
            throw new RuntimeException("变量未分配位置: " + varName);
        }

        // 为整个生命周期内的每个指令点更新位置信息
        for (int i = interval.start; i <= interval.end; i++) {
            Map<String, Location> instMap = registerAllocationByInst.get(i);
            if (instMap != null) {
                instMap.put(varName, location);
            }
        }
    }

    // 清理不再活跃的变量并回收寄存器
    private void expireOldIntervals(LiveInterval interval, List<LiveInterval> active, List<String> free) {
        // 确保按结束点排序
        Collections.sort(active, Comparator.comparingInt(i -> i.end));

        Iterator<LiveInterval> it = active.iterator();
        while (it.hasNext()) {
            LiveInterval activeInterval = it.next();
            if (activeInterval.end >= interval.start) {
                break; // 后面的区间都还在活跃
            }

            // 当前区间已结束，释放其寄存器
            String reg = registerAllocation.get(activeInterval.varName);
            if (reg != null) {
                free.add(reg);
                registerAllocation.remove(activeInterval.varName);
            }
            it.remove();
        }

        // 恢复寄存器序列
        Collections.sort(free);
    }


    // 处理溢出情况
    private void spillAtInterval(LiveInterval interval, List<LiveInterval> active) {
        // 找出结束最晚的区间
        LiveInterval last = active.get(active.size() - 1);

        if (last.end > interval.end) {
            // 当前区间结束较早，应该优先分配寄存器
            String reg = registerAllocation.get(last.varName);
            registerAllocation.put(interval.varName, reg);
            registerAllocation.remove(last.varName);

            // 仅标记变量为溢出,如果在翻译器中，这个变量再次被用到，查找时仅知道其已经被溢出了————
            //————然后就可以在翻译器中获得栈状态，安排它在栈和临时寄存器之间的移动
            spilledVars.add(last.varName);
            // 更新被溢出变量的位置信息
            updateLocationForInterval(last);

            // 更新活跃列表
            active.remove(last);
            active.add(interval);
            // 重新按结束点排序
            Collections.sort(active, Comparator.comparingInt(i -> i.end));
        } else {
            // 当前区间结束较晚，直接溢出到栈
            spilledVars.add(interval.varName);
        }
    }

    // 获取指定指令点变量的位置
    public Location getLocation(int instructionIndex, String varName) {
        Map<String, Location> instMap = registerAllocationByInst.get(instructionIndex);
        if (instMap == null) return null;
        return instMap.get(varName);
    }

}


