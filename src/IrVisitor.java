import kotlin.Pair;
import kotlin.Unit;
import org.antlr.v4.runtime.tree.*;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.*;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.llvm4j.Value; // 确保导入 Value
import org.llvm4j.optional.Option; // 导入 Option

import java.io.File; // 导入 File
import java.util.*;
import java.util.stream.Collectors;
// 导入常量相关的类
import org.llvm4j.llvm4j.Constant;
import org.llvm4j.llvm4j.ConstantInt;
// 导入类型相关的类
import org.llvm4j.llvm4j.IntegerType;
import org.llvm4j.llvm4j.Type;
import org.llvm4j.llvm4j.FunctionType;
// 导入构建器相关的类
import org.llvm4j.llvm4j.IRBuilder;
import org.llvm4j.llvm4j.BasicBlock;
// 导入运算相关的枚举
import org.llvm4j.llvm4j.IntPredicate;
import org.llvm4j.llvm4j.WrapSemantics;
// 导入 Result 相关的类和方法
import org.llvm4j.optional.Result;
import org.llvm4j.optional.Err; // Optional: if needed for specific error handling
import org.llvm4j.optional.Some;

import static org.bytedeco.llvm.global.LLVM.*;



public class IrVisitor {
    private final Module module;
    private LLVMModuleRef moduleRef;
    private AllocateRegister registerStrategy;

    private int instructionId = 1;

    // 活跃区间表
    private Map<String, LiveInterval> liveIntervals = new HashMap<>();
    // 标签表：块名 -> 指令编号
    private Map<String, Integer> labelTable = new HashMap<>();
    // 全局符号表
    private Map<String, GlobalVariableInfo> globalSymbols = new HashMap<>();

    private static final int LLVMPhiOpcode = 53;//合并分支指令的操作码

    public IrVisitor(Module module) {
        this.module = module;
        this.moduleRef = module.getRef();

        visitModule(moduleRef);
    }

    // 或者更通用的设置方法
    public void setRegisterStrategy(AllocateRegister strategy) {
        this.registerStrategy = strategy;
    }

    public void visitModule(LLVMModuleRef module) {
        // 遍历全局变量
        for (LLVMValueRef value = LLVMGetFirstGlobal(module); value != null; value = LLVMGetNextGlobal(value)) {
            String name = LLVMGetValueName(value).getString();
            if (name != null && !name.isEmpty()) {
                // 记录全局变量信息，但不加入生命周期表
                globalSymbols.put(name, new GlobalVariableInfo(name));
            }
        }

        // 遍历函数
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            visitFunction(func);
        }
    }

    public void visitFunction(LLVMValueRef function) {
        // 遍历函数的基本块
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(function); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            visitBasicBlock(bb);
        }
    }

    public void  visitBasicBlock(LLVMBasicBlockRef block) {
        //获得基本块的名称
        String blockName = LLVMGetBasicBlockName(block).getString();

        // 记录基本块的起始指令编号
        labelTable.put(blockName, instructionId);

        // 遍历基本块中的指令
        for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(inst)) {
            visitInstruction(inst);
        }
    }

    public void visitInstruction(LLVMValueRef instruction) {
        int operandNum = LLVMGetNumOperands(instruction);
        int opcode = LLVMGetInstructionOpcode(instruction);

        // 创建已处理PHI操作数集合
        Set<String> processedPhiOperands = new HashSet<>();

        // 处理PHI指令
        if (opcode == LLVMPhiOpcode) {
            int incomingCount = LLVMCountIncoming(instruction);
            for (int i = 0; i < incomingCount; i++) {
                LLVMValueRef value = LLVMGetIncomingValue(instruction, i);
                LLVMBasicBlockRef block = LLVMGetIncomingBlock(instruction, i);

                if (!isConstant(value)) {
                    String varName = LLVMGetValueName(value).getString();
                    if (varName != null && !varName.isEmpty() && !globalSymbols.containsKey(varName)) {
                        // 记录已处理的PHI操作数
                        processedPhiOperands.add(varName);

                        // 找到前驱块的结束位置
                        String blockName = LLVMGetBasicBlockName(block).getString();
                        Integer blockEndId = getBlockEndId(blockName);
                        if (blockEndId != null) {
                            // 将PHI操作数的生命周期延伸至前驱块的结束位置
                            updateOperandLiveInterval(varName, blockEndId);
                        } else {
                            // 如果找不到前驱块结束位置，至少更新到当前PHI指令位置
                            updateOperandLiveInterval(varName, instructionId);
                        }
                    }
                }
            }
        }

        // 1. 处理所有操作数
        for (int i = 0; i < operandNum; ++i) {
            LLVMValueRef operand = LLVMGetOperand(instruction, i);

            //处理变量操作数
            if (!isConstant(operand)) {
                String varName = LLVMGetValueName(operand).getString();
                if (varName != null && !varName.isEmpty()&& !processedPhiOperands.contains(varName)) {
                    updateOperandLiveInterval(varName, instructionId);
                }
            }
        }

        // 2. 处理可能定义的新变量
        if (instructionDefinesValue(instruction)) {
            String defined = LLVMGetValueName(instruction).getString();
            if (defined != null && !defined.isEmpty()) {
                updateDefinedVarInterval(defined, instructionId);
            }
        }

        // 3. 指令编号递增
        instructionId++;
    }

    //辅助方法：// 判断是否为常量（整数、浮点等），只登记变量
    private boolean isConstant(LLVMValueRef value) {
        return LLVMIsAConstantInt(value) != null
                || LLVMIsAConstantFP(value) != null
                || LLVMIsAConstant(value) != null;
    }

    //辅助方法，更新生命周期
    //操作数变量：只更新结束点
    private void updateOperandLiveInterval(String var, int id) {
        // 如果是全局变量，不纳入寄存器分配考虑
        if (globalSymbols.containsKey(var)) {
            return;
        }

        // 更新操作数变量的生命周期
        LiveInterval interval = liveIntervals.get(var);
        if (interval == null) {
            // 首次遇到，可能是参数或全局变量
            interval = new LiveInterval(var, 0, id);
            liveIntervals.put(var, interval);
        } else {
            // 仅更新结束点
            interval.end = Math.max(interval.end, id);
        }
    }

    // 新定义变量：设置起始点
    private void updateDefinedVarInterval(String var, int id) {
        LiveInterval interval = liveIntervals.get(var);
        if (interval == null) {
            // 新变量，创建新的生命周期
            interval = new LiveInterval(var, id, id);
            liveIntervals.put(var, interval);
        } else {
            // 在SSA形式中不应出现这种情况
            // 但如果出现，更新起始点
            interval.start = id;
        }
    }

    //辅助方法：指令是否有返回类型且不是void
    private boolean instructionDefinesValue(LLVMValueRef instruction) {
        // 检查指令是否有返回类型且不是 void
        LLVMTypeRef type = LLVMTypeOf(instruction);
        return LLVMGetTypeKind(type) != LLVMVoidTypeKind;
    }

    //辅助方法：获取基本块结束位置
    private Integer getBlockEndId(String blockName) {
        Integer blockStartId = labelTable.get(blockName);
        if (blockStartId == null) return null;

        // 查找该块后的下一个块开始位置
        Integer nextBlockStart = null;
        for (Map.Entry<String, Integer> entry : labelTable.entrySet()) {
            if (entry.getValue() > blockStartId && (nextBlockStart == null || entry.getValue() < nextBlockStart)) {
                nextBlockStart = entry.getValue();
            }
        }

        // 如果找到下一个块，则结束位置是它的前一条指令
        // 否则使用当前指令ID（更保守的方法）
        return nextBlockStart != null ? nextBlockStart - 1 : instructionId;
    }

    //获取成员变量的方法
    public Module getModule() {
        return module;
    }

    public Map<String, LiveInterval> getLiveIntervals() {
        return liveIntervals;
    }

    public Map<String, GlobalVariableInfo> getGlobalSymbols() {
        return globalSymbols;
    }

    public Map<String, Integer> getLabelTable() {
        return labelTable;
    }

}
