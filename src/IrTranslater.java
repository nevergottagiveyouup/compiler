import kotlin.Pair;
import kotlin.Unit;
import org.antlr.v4.runtime.tree.*;
import org.bytedeco.llvm.LLVM.*;
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

public class IrTranslater {
    private final IrVisitor visitor;
    private final LLVMModuleRef moduleRef;

    // 活跃区间表
    private Map<String, LiveInterval> liveIntervals;
    // 标签表：块名 -> 指令编号
    private Map<String, Integer> labelTable;
    // 全局符号表
    private Map<String, GlobalVariableInfo> globalSymbols ;


    private LinearScan registerAllocator;
    private AsmBuilder builder = new AsmBuilder();

    // 管理临时寄存器
    private List<String> tempRegisters = Arrays.asList("t0", "t1", "t2", "t3", "t4", "t5", "t6");
    private Map<String, String> tempRegisterUse = new HashMap<>(); // 记录临时寄存器当前保存的变量
    private Map<String, Boolean> tempRegisterDirty = new HashMap<>(); // 记录临时寄存器是否被修改过

    // 栈偏移管理
    private int currentFrameSize = 0;
    private Map<String, Integer> varStackOffsets = new HashMap<>(); // 变量到栈偏移的映射
    private int nextStackOffset = 4; // 从4开始，因为0位置存放返回地址

    private int instructionId = 1; // 当前指令编号
    private Set<String> processedPhiOperands = new HashSet<>();
    private static final int LLVMPhiOpcode = 53; // PHI指令操作码

    public IrTranslater(IrVisitor visitor) {
        //获得第一趟信息
        this.visitor = visitor;
        this.moduleRef = visitor.getModule().getRef();

        this.liveIntervals = visitor.getLiveIntervals();
        this.labelTable = visitor.getLabelTable();
        this.globalSymbols = visitor.getGlobalSymbols();

        //生成寄存器分配方案
        this.registerAllocator = new LinearScan(liveIntervals);
        this.registerAllocator.allocateRegister();

        //启动翻译
        translateModule();
    }

    public AsmBuilder getBuilder() {
        return builder;
    }

    public void translateModule() {
        // 添加汇编头部
        builder.section("text");

        builder.emptyLine();
        builder.comment("全局变量定义");
        builder.section("data");

        // 遍历全局变量
        for (LLVMValueRef value = LLVMGetFirstGlobal(moduleRef); value != null; value = LLVMGetNextGlobal(value)) {
            translateGlobalVariable(value);
        }

        // 返回代码段
        builder.section("text");

        // 遍历函数
        for (LLVMValueRef func = LLVMGetFirstFunction(moduleRef); func != null; func = LLVMGetNextFunction(func)) {
            translateFunction(func);
        }
    }

    private void translateGlobalVariable(LLVMValueRef global) {
        //获取全局变量名称
        String name = LLVMGetValueName(global).getString();
        if (name == null || name.isEmpty()) return;

        // 获取初始值（如果有）
        LLVMValueRef initializer = LLVMGetInitializer(global);

        //生成全局变量声明
        builder.directive("globl", name);
        builder.label(name);

        if (initializer != null) {
            if (LLVMIsAConstantInt(initializer) != null) {
                // 整数常量
                long value = LLVMConstIntGetSExtValue(initializer);
                builder.directive("word", String.valueOf(value));
            } else {
                // 其他类型（默认为0）
                builder.directive("word", "0");
            }
        } else {
            // 无初始化值，分配空间
            LLVMTypeRef type = LLVMGetElementType(LLVMTypeOf(global));

            // 获取模块的数据布局
            String dataLayoutStr = String.valueOf(LLVMGetDataLayout(moduleRef));
            LLVMTargetDataRef dataLayout = LLVMCreateTargetData(dataLayoutStr);

            // 计算类型大小
            long size = LLVMSizeOfTypeInBits(dataLayout, type) / 8;

            // 释放数据布局
            LLVMDisposeTargetData(dataLayout);

            builder.directive("space", String.valueOf(Math.max((int)size, 4)));
        }
    }

    private void translateFunction(LLVMValueRef function) {
        String functionName = LLVMGetValueName(function).getString();

        if (LLVMCountBasicBlocks(function) == 0) return;

        // 重置栈分配状态
        nextStackOffset = 4; // 重置栈偏移计数器（0位置用于返回地址）
        varStackOffsets.clear(); // 清空变量栈偏移表
        tempRegisterUse.clear(); // 清空临时寄存器使用情况
        tempRegisterDirty.clear(); // 清空临时寄存器脏状态

        // 函数开始
        builder.emptyLine();
        builder.comment("函数: " + functionName);
        builder.directive("globl", functionName);
        builder.label(functionName);

        // 预分配栈空间 - 为所有局部变量分配栈空间
        Set<String> allLocalVars = new HashSet<>();

        // 获取所有需要栈空间的变量
        // 1. 溢出的变量
        allLocalVars.addAll(registerAllocator.spilledVars);

        // 2. 所有有名字的局部变量
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(function); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                String instName = LLVMGetValueName(inst).getString();
                if (instName != null && !instName.isEmpty() && !isGlobalVariable(instName)) {
                    allLocalVars.add(instName);
                }
            }
        }

        // 为所有局部变量分配栈空间
        for (String varName : allLocalVars) {
            allocateStackSpace(varName);
        }

        // 计算总帧大小，确保至少有4字节（返回地址）
        int frameSize = Math.max(nextStackOffset, 4);
        currentFrameSize = frameSize;

        // 函数序言：保存寄存器、分配栈空间
        if (frameSize > 0) {
            // 只有当需要栈空间时才生成序言
            builder.op2("addi", "sp", "sp", "-" + frameSize);
            builder.store("ra", "sp", 0);  // 保存返回地址
            // 不需要设置帧指针，直接用sp+偏移访问局部变量
        }

        // 遍历函数的基本块
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(function); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            translateBasicBlock(bb);
        }

        // 函数尾声：确保所有路径都有返回指令
        // 注意：这是一个安全措施，理论上每个基本块都应该有自己的终止指令
        builder.emptyLine();
        builder.label(functionName + "_end");
        if (frameSize > 0) {
            builder.load("ra", "sp", 0);
            builder.op2("addi", "sp", "sp", String.valueOf(frameSize));
        }
        builder.ret();
    }

    private void translateBasicBlock(LLVMBasicBlockRef block) {
        // 生成标签
        String blockName = LLVMGetBasicBlockName(block).getString();
        if (blockName != null && !blockName.isEmpty()) {
            builder.label(blockName);
        }

        // 翻译基本块中的指令
        for (LLVMValueRef inst = LLVMGetFirstInstruction(block); inst != null; inst = LLVMGetNextInstruction(inst)) {
            translateInstruction(inst);
        }
    }

    private void translateInstruction(LLVMValueRef inst) {
        int opcode = LLVMGetInstructionOpcode(inst);
        String instName = LLVMGetValueName(inst).getString();

        switch (opcode) {
            case LLVMRet:  // 返回指令
                translateRet(inst);
                break;
            case LLVMBr:   // 分支指令
                translateBr(inst);
                break;
            case LLVMSwitch: // Switch指令
                translateSwitch(inst);
                break;
            case LLVMCall:  // 函数调用
                translateCall(inst);
                break;
            case LLVMAdd:   // 加法
            case LLVMSub:   // 减法
            case LLVMMul:   // 乘法
            case LLVMSDiv:  // 有符号除法
            case LLVMUDiv:  // 无符号除法
            case LLVMSRem:  // 有符号取余
            case LLVMURem:  // 无符号取余
                translateBinaryOp(inst, opcode);
                break;
            case LLVMAnd:   // 按位与
            case LLVMOr:    // 按位或
            case LLVMXor:   // 按位异或
                translateBitwiseOp(inst, opcode);
                break;
            case LLVMShl:   // 左移
            case LLVMLShr:  // 逻辑右移
            case LLVMAShr:  // 算术右移
                translateShiftOp(inst, opcode);
                break;
            case LLVMICmp:  // 整数比较
                translateICmp(inst);
                break;
            case LLVMAlloca: // 栈分配
                translateAlloca(inst);
                break;
            case LLVMLoad:   // 加载
                translateLoad(inst);
                break;
            case LLVMStore:  // 存储
                translateStore(inst);
                break;
            case LLVMGetElementPtr: // 获取指针
                translateGetElementPtr(inst);
                break;
            case LLVMZExt:   // 零扩展
            case LLVMSExt:   // 符号扩展
            case LLVMTrunc:  // 截断
                translateCast(inst, opcode);
                break;
            case LLVMPHI:    // Phi指令
                translatePhi(inst);
                break;
            default:
                builder.comment("不支持的指令类型: " + opcode);
                break;
        }

        instructionId++;
    }

    /*********************************************************************************/
    // 获取变量的位置，如果在栈上就加载到临时寄存器，也就是说一定返回一个临时寄存器位置
    private String getVariableLocation(String varName, int instId) {
        // 检查是否是全局变量
        if (isGlobalVariable(varName)) {
            String tempReg = allocateTempRegister(varName);
            // 加载全局变量地址 - 修正为使用双参数la指令
            builder.la(tempReg, varName);
            return tempReg;
        }

        //局部变量逻辑如下
        Location loc = registerAllocator.getLocation(instId, varName);

        if (loc == null) {
            builder.comment("警告: 变量 " + varName + " 在当前位置没有位置信息");
            return "zero"; // 使用零寄存器作为默认值
        }

        if (loc.type == Location.LocationType.REGISTER) {
            return loc.register;
        } else {
            return loadFromStack(varName);
        }
    }

    // 添加一个方法来判断变量是否是全局变量
    private boolean isGlobalVariable(String varName) {
        // 检查模块中是否有这个名称的全局变量
        LLVMValueRef global = LLVMGetNamedGlobal(moduleRef, varName);
        return global != null;
    }

    // 为溢出变量分配栈空间，该方法仅在变量未被分配位置时起效，否则没有任何操作
    private void allocateStackSpace(String varName) {
        if (!varStackOffsets.containsKey(varName)) {
            // 分配新的栈空间并记录偏移
            varStackOffsets.put(varName, nextStackOffset);
            nextStackOffset += 4; // 每个变量占4字节

            // 更新当前帧大小
            currentFrameSize = nextStackOffset;
        }
    }

    // 从栈加载变量到临时寄存器，并一定返回变量最终的临时寄存器位置
    private String loadFromStack(String varName) {
        // 确保变量有栈空间
        allocateStackSpace(varName);
        int offset = varStackOffsets.get(varName);

        // 查找是否已经加载到某个临时寄存器
        for (String reg : tempRegisterUse.keySet()) {
            if (varName.equals(tempRegisterUse.get(reg))) {
                return reg; // 已经在寄存器中
            }
        }

        // 需要加载到新的临时寄存器
        String tempReg = allocateTempRegister(varName);
        builder.load(tempReg, "sp", offset);
        tempRegisterDirty.put(tempReg, false); // 加载后未修改

        return tempReg;
    }

    // 分配临时寄存器，一定返回一个临时寄存器位置
    private String allocateTempRegister(String varName) {
        // 首先检查该变量是否已经在寄存器中
        for (Map.Entry<String, String> entry : tempRegisterUse.entrySet()) {
            if (varName.equals(entry.getValue())) {
                return entry.getKey(); // 变量已在寄存器中，直接返回
            }
        }

        // 查找空闲的寄存器
        for (String reg : tempRegisters) {
            if (!tempRegisterUse.containsKey(reg)) {
                tempRegisterUse.put(reg, varName);
                tempRegisterDirty.put(reg, false); // 初始状态为非脏
                return reg;
            }
        }

        // 没有空闲寄存器，需要溢出一个
        String victimReg = selectVictimRegister();

        // 重新分配寄存器
        tempRegisterUse.put(victimReg, varName);
        tempRegisterDirty.put(victimReg, false); // 重置脏状态

        return victimReg;
    }

    // 选择要牺牲的寄存器(可以实现更复杂的策略)，一定返回一个临时寄存器位置
    private String selectVictimRegister() {
        String victimReg = null;
        int latestEnd = -1;

        // 遍历所有已分配的临时寄存器
        for (String reg : tempRegisterUse.keySet()) {
            String varName = tempRegisterUse.get(reg);

            // 查找变量的生命周期
            LiveInterval interval = liveIntervals.get(varName);

            // 如果找不到生命周期信息，可能是临时变量，默认为当前指令结束
            int endPoint = (interval != null) ? interval.end : instructionId;

            // 选择结束最晚的变量对应的寄存器
            if (endPoint > latestEnd) {
                latestEnd = endPoint;
                victimReg = reg;
            }
        }

        // 如果没有找到合适的寄存器(不应该发生)，回退到使用第一个寄存器
        if (victimReg == null && !tempRegisters.isEmpty()) {
            victimReg = tempRegisters.get(0);
        }

        // 处理被牺牲的寄存器中的变量
        String victimVar = tempRegisterUse.get(victimReg);
        if (victimVar != null) {
            if (tempRegisterDirty.getOrDefault(victimReg, false)) {
                if (isGlobalVariable(victimVar)) {
                    // 全局变量，需要写回全局变量区
                    String addrReg = allocateTempRegister("addr_" + victimVar);
                    builder.la(addrReg, victimVar);
                    builder.store(victimReg, addrReg, 0);
                } else if (varStackOffsets.containsKey(victimVar)) {
                    // 栈上变量，写回栈
                    int victimOffset = varStackOffsets.get(victimVar);
                    builder.store(victimReg, "sp", victimOffset);
                }
                // 重置脏标记
                tempRegisterDirty.put(victimReg, false);
            }
        }

        return victimReg;
    }

    // 标记寄存器为已修改
    private void markRegisterDirty(String reg) {
        if (tempRegisterUse.containsKey(reg)) {
            tempRegisterDirty.put(reg, true);
        }
    }

    // 函数结束前将所有脏寄存器写回
    private void flushDirtyRegisters() {
        for (String reg : new ArrayList<>(tempRegisterUse.keySet())) {
            if (tempRegisterDirty.getOrDefault(reg, false)) {
                String var = tempRegisterUse.get(reg);

                // 区分全局变量和局部变量
                if (isGlobalVariable(var)) {
                    // 全局变量，需要写回全局变量区
                    String addrReg = allocateTempRegister("addr_" + var);
                    builder.la(addrReg, var);
                    builder.store(reg, addrReg, 0);
                } else if (varStackOffsets.containsKey(var)) {
                    // 局部变量，写回栈
                    int offset = varStackOffsets.get(var);
                    builder.store(reg, "sp", offset);
                }

                // 重置脏标记
                tempRegisterDirty.put(reg, false);
            }
        }
    }


    /**********************************************************************************/


    private void translateRet(LLVMValueRef inst) {
        builder.comment("返回指令");

        // 首先保存所有脏寄存器到栈
        flushDirtyRegisters();

        // 检查是否有返回值
        int operandCount = LLVMGetNumOperands(inst);
        if (operandCount > 0) {
            LLVMValueRef retValue = LLVMGetOperand(inst, 0);

            // 如果返回值是常量
            if (LLVMIsAConstant(retValue) != null) {
                long constValue = LLVMConstIntGetSExtValue(retValue);
                builder.loadImm("a0", constValue);
            } else {
                String retVarName = LLVMGetValueName(retValue).getString();
                String retReg = getVariableLocation(retVarName, instructionId);

                // 如果返回值不在a0寄存器，需要移动
                if (!retReg.equals("a0")) {
                    builder.move("a0", retReg);
                }
            }
        }

        // 使用函数开始时计算的帧大小
        if (currentFrameSize > 0) {
            builder.load("ra", "sp", 0);  // 恢复返回地址
            builder.op2("addi", "sp", "sp", String.valueOf(currentFrameSize));  // 恢复栈指针
        }

        builder.ret();
    }

    private void translateBinaryOp(LLVMValueRef inst, int opcode) {
        // 获取操作数和结果变量名
        LLVMValueRef op1 = LLVMGetOperand(inst, 0);
        LLVMValueRef op2 = LLVMGetOperand(inst, 1);
        String destVar = LLVMGetValueName(inst).getString();

        // 获取操作指令
        String operation;
        switch (opcode) {
            case LLVMAdd: operation = "add"; break;
            case LLVMSub: operation = "sub"; break;
            case LLVMMul: operation = "mul"; break;
            case LLVMSDiv: operation = "div"; break;
            case LLVMSRem: operation = "rem"; break;
            default: operation = "add"; // 默认为加法
        }

        // 处理第一个操作数
        String op1Reg;
        if (LLVMIsAConstant(op1) != null) {
            // 如果是常量，加载到临时寄存器
            long constValue = LLVMConstIntGetSExtValue(op1);
            op1Reg = allocateTempRegister("const_temp");
            builder.loadImm(op1Reg, constValue);
        } else {
            String op1Name = LLVMGetValueName(op1).getString();
            op1Reg = getVariableLocation(op1Name, instructionId);
        }

        // 处理第二个操作数
        String op2Reg;
        if (LLVMIsAConstant(op2) != null) {
            long constValue = LLVMConstIntGetSExtValue(op2);
            // 对于简单指令，可以直接使用立即数
            if ((opcode == LLVMAdd || opcode == LLVMSub) && constValue >= -2048 && constValue <= 2047) {
                op2Reg = String.valueOf(constValue);
            } else {
                op2Reg = allocateTempRegister("const_temp2");
                builder.loadImm(op2Reg, constValue);
            }
        } else {
            String op2Name = LLVMGetValueName(op2).getString();
            op2Reg = getVariableLocation(op2Name, instructionId);
        }

        // 获取结果存储位置
        Location destLoc = registerAllocator.getLocation(instructionId, destVar);
        String destReg;

        if (destLoc == null || destLoc.type == Location.LocationType.STACK) {
            // 分配临时寄存器用于结果
            destReg = allocateTempRegister(destVar);
            // 标记为脏寄存器
            allocateStackSpace(destVar);
            markRegisterDirty(destReg);
        } else {
            // 使用分配的寄存器
            destReg = destLoc.register;
        }

        // 生成计算指令
        if (opcode == LLVMAdd && op2Reg.matches("-?\\d+")) {
            // 对于加法，可以使用带立即数的addi指令
            builder.op2("addi", destReg, op1Reg, op2Reg);
        } else if (opcode == LLVMSub && op2Reg.matches("-?\\d+")) {
            // 对于减法，取反立即数，使用addi
            int imm = -Integer.parseInt(op2Reg);
            builder.op2("addi", destReg, op1Reg, String.valueOf(imm));
        } else {
            // 其他情况使用三操作数指令
            builder.op3(operation, destReg, op1Reg, op2Reg);
        }
    }

    private void translateLoad(LLVMValueRef inst) {
        String destVar = LLVMGetValueName(inst).getString();
        LLVMValueRef pointer = LLVMGetOperand(inst, 0);
        String pointerName = LLVMGetValueName(pointer).getString();

        builder.comment("加载 " + destVar + " 从 " + pointerName);

        // 获取目标寄存器
        String destReg;
        Location destLoc = registerAllocator.getLocation(instructionId, destVar);

        if (destLoc == null) {
            // destLoc为null，说明是全局变量
            String tempReg = allocateTempRegister(destVar);
            destReg = tempReg;
            // 全局变量不需要管理栈空间
        } else if (destLoc.type == Location.LocationType.STACK) {
            // 目标在栈上，需要临时寄存器
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else {
            // 目标在寄存器中
            destReg = destLoc.register;
        }

        // 处理指针，它可能指向全局变量或局部变量
        if (isGlobalVariable(pointerName)) {
            // 指针指向全局变量，需要两步：加载地址，然后加载值
            String addrReg = allocateTempRegister("addr_" + pointerName);
            builder.la(addrReg, pointerName);   // 加载全局变量地址到临时寄存器
            builder.load(destReg, addrReg, 0);  // 从地址加载值到目标寄存器
        } else {
            // 指针是局部变量，获取指针的寄存器位置
            String pointerReg = getVariableLocation(pointerName, instructionId);
            builder.load(destReg, pointerReg, 0); // 通过指针加载值到目标寄存器
        }
    }

    private void translateStore(LLVMValueRef inst) {
        LLVMValueRef valueRef = LLVMGetOperand(inst, 0);  // 要存储的值
        LLVMValueRef pointerRef = LLVMGetOperand(inst, 1); // 指向存储位置的指针
        String pointerName = LLVMGetValueName(pointerRef).getString();

        builder.comment("存储到 " + pointerName);

        // 获取源值
        String valueReg;
        if (LLVMIsAConstant(valueRef) != null) {
            // 值是常量
            long constValue = LLVMConstIntGetSExtValue(valueRef);
            valueReg = allocateTempRegister("const_temp");
            builder.loadImm(valueReg, constValue);
        } else {
            // 值是变量
            String valueName = LLVMGetValueName(valueRef).getString();
            valueReg = getVariableLocation(valueName, instructionId);
        }

        // 处理指针，它可能指向全局变量或局部变量
        if (isGlobalVariable(pointerName)) {
            // 指针指向全局变量，需要两步：加载地址，然后存储值
            String addrReg = allocateTempRegister("addr_" + pointerName);
            builder.la(addrReg, pointerName);   // 加载全局变量地址到临时寄存器
            builder.store(valueReg, addrReg, 0); // 将值存储到地址
        } else {
            // 指针是局部变量，获取指针的寄存器位置
            Location pointerLoc = registerAllocator.getLocation(instructionId, pointerName);

            if (pointerLoc == null) {
                // 这种情况不应该发生，因为局部变量应该有位置分配
                builder.comment("警告：指针 " + pointerName + " 没有位置信息");
                return;
            }

            if (pointerLoc.type == Location.LocationType.REGISTER) {
                // 指针在寄存器中
                String pointerReg = pointerLoc.register;
                builder.store(valueReg, pointerReg, 0); // 通过指针存储值
            } else {
                // 指针在栈上，需要先加载到寄存器
                String pointerReg = loadFromStack(pointerName);
                builder.store(valueReg, pointerReg, 0); // 通过指针存储值
            }
        }
    }

    private void translateBr(LLVMValueRef inst) {
        int operandCount = LLVMGetNumOperands(inst);

        if (operandCount == 1) {
            // 无条件跳转
            LLVMValueRef targetBlock = LLVMGetOperand(inst, 0);
            String targetName = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(targetBlock)).getString();

            // 首先保存所有脏寄存器
            flushDirtyRegisters();

            builder.jump(targetName);
        } else if (operandCount == 3) {
            // 条件跳转
            LLVMValueRef condition = LLVMGetOperand(inst, 0);
            LLVMValueRef trueBlock = LLVMGetOperand(inst, 1);
            LLVMValueRef falseBlock = LLVMGetOperand(inst, 2);

            String condName = LLVMGetValueName(condition).getString();
            String trueLabel = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(trueBlock)).getString();
            String falseLabel = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(falseBlock)).getString();

            // 获取条件寄存器
            String condReg = getVariableLocation(condName, instructionId);

            // 首先保存所有脏寄存器
            flushDirtyRegisters();

            // 条件跳转：如果条件非零，跳转到true分支
            builder.op2("beq", condReg, "zero", falseLabel);
            builder.jump(trueLabel);
        }
    }

    private void translateICmp(LLVMValueRef inst) {
        // 获取操作数
        LLVMValueRef op1 = LLVMGetOperand(inst, 0);
        LLVMValueRef op2 = LLVMGetOperand(inst, 1);
        String destVar = LLVMGetValueName(inst).getString();

        // 获取比较谓词
        int predicate = LLVMGetICmpPredicate(inst);
        String op = getComparisonOp(predicate);

        // 处理操作数
        String op1Reg;
        if (LLVMIsAConstant(op1) != null) {
            long constValue = LLVMConstIntGetSExtValue(op1);
            if (constValue == 0 && (predicate == LLVMIntEQ || predicate == LLVMIntNE)) {
                // 与零比较的特殊情况
                op1Reg = "zero";
            } else {
                op1Reg = allocateTempRegister("const_temp");
                builder.loadImm(op1Reg,constValue);
            }
        } else {
            String op1Name = LLVMGetValueName(op1).getString();
            op1Reg = getVariableLocation(op1Name, instructionId);
        }

        String op2Reg;
        if (LLVMIsAConstant(op2) != null) {
            long constValue = LLVMConstIntGetSExtValue(op2);
            if (constValue == 0 && (predicate == LLVMIntEQ || predicate == LLVMIntNE)) {
                // 与零比较的特殊情况
                op2Reg = "zero";
            } else {
                op2Reg = allocateTempRegister("const_temp2");
                builder.loadImm(op2Reg,constValue);
            }
        } else {
            String op2Name = LLVMGetValueName(op2).getString();
            op2Reg = getVariableLocation(op2Name, instructionId);
        }

        // 获取结果存储位置
        Location destLoc = registerAllocator.getLocation(instructionId, destVar);
        String destReg;

        if (destLoc == null || destLoc.type == Location.LocationType.STACK) {
            // 分配临时寄存器用于结果
            destReg = allocateTempRegister(destVar);
            // 标记为脏寄存器
            allocateStackSpace(destVar);
            markRegisterDirty(destReg);
        } else {
            // 使用分配的寄存器
            destReg = destLoc.register;
        }

        // 根据比较类型生成指令
        switch (predicate) {
            case LLVMIntEQ:  // 等于
                builder.op3("xor", destReg, op1Reg, op2Reg);
                builder.op3("seqz", destReg, destReg, ""); // 如果为0则设为1
                break;
            case LLVMIntNE:  // 不等于
                builder.op3("xor", destReg, op1Reg, op2Reg);
                builder.op3("snez", destReg, destReg, ""); // 如果非0则设为1
                break;
            case LLVMIntSGT:  // 有符号大于
                builder.op3("sgt", destReg, op1Reg, op2Reg);
                break;
            case LLVMIntSGE:  // 有符号大于等于
                builder.op3("slt", destReg, op1Reg, op2Reg);
                builder.op3("xori", destReg, destReg, "1"); // 取反
                break;
            case LLVMIntSLT:  // 有符号小于
                builder.op3("slt", destReg, op1Reg, op2Reg);
                break;
            case LLVMIntSLE:  // 有符号小于等于
                builder.op3("sgt", destReg, op1Reg, op2Reg);
                builder.op3("xori", destReg, destReg, "1"); // 取反
                break;
            default:
                builder.comment("不支持的比较类型: " + predicate);
                break;
        }
    }

    private String getComparisonOp(int predicate) {
        switch (predicate) {
            case LLVMIntEQ: return "eq";
            case LLVMIntNE: return "ne";
            case LLVMIntSGT: return "gt";
            case LLVMIntSGE: return "ge";
            case LLVMIntSLT: return "lt";
            case LLVMIntSLE: return "le";
            case LLVMIntUGT: return "gtu";
            case LLVMIntUGE: return "geu";
            case LLVMIntULT: return "ltu";
            case LLVMIntULE: return "leu";
            default: return "eq";
        }
    }

    private void translateCall(LLVMValueRef inst) {
        LLVMValueRef calledFunction = LLVMGetCalledValue(inst);
        String funcName = LLVMGetValueName(calledFunction).getString();

        builder.comment("调用函数 " + funcName);

        // 保存所有脏寄存器
        flushDirtyRegisters();

        // 处理参数传递
        int argCount = LLVMGetNumArgOperands(inst);
        for (int i = 0; i < argCount; i++) {
            LLVMValueRef arg = LLVMGetArgOperand(inst, i);
            String argReg;

            if (LLVMIsAConstant(arg) != null) {
                // 常量参数
                long constValue = LLVMConstIntGetSExtValue(arg);
                argReg = allocateTempRegister("arg_temp");
                builder.loadImm(argReg,constValue);
            } else {
                // 变量参数
                String argName = LLVMGetValueName(arg).getString();
                argReg = getVariableLocation(argName, instructionId);
            }

            // 将参数复制到参数寄存器
            String paramReg = (i < 8) ? "a" + i : "t" + (i - 8);
            if (!argReg.equals(paramReg)) {
                builder.move(paramReg, argReg);
            }
        }

        // 执行函数调用
        builder.call(funcName);

        // 处理返回值（如果有）
        LLVMTypeRef returnType = LLVMGetReturnType(LLVMTypeOf(calledFunction));
        if (LLVMGetTypeKind(returnType) != LLVMVoidTypeKind) {
            String destVar = LLVMGetValueName(inst).getString();
            if (!destVar.isEmpty()) {
                Location destLoc = registerAllocator.getLocation(instructionId, destVar);

                if (destLoc == null || destLoc.type == Location.LocationType.STACK) {
                    // 如果结果需要放在栈上，将a0移动到临时寄存器然后标记为脏
                    String tempReg = allocateTempRegister(destVar);
                    allocateStackSpace(destVar);
                    builder.move(tempReg, "a0");
                    markRegisterDirty(tempReg);
                } else if (!destLoc.register.equals("a0")) {
                    // 如果结果需要放在其他寄存器，从a0移动
                    builder.move(destLoc.register, "a0");
                }
                // 如果目标就是a0，无需额外操作
            }
        }
    }

    private void translateAlloca(LLVMValueRef inst) {
        String varName = LLVMGetValueName(inst).getString();

        builder.comment("栈分配 " + varName);

        // 为变量分配栈空间
        allocateStackSpace(varName + "_ptr");

        // 计算变量的地址（sp + 偏移量）
        int offset = varStackOffsets.get(varName + "_ptr");
        String addrReg;

        Location varLoc = registerAllocator.getLocation(instructionId, varName);
        if (varLoc == null || varLoc.type == Location.LocationType.STACK) {
            addrReg = allocateTempRegister(varName);
            allocateStackSpace(varName);
            markRegisterDirty(addrReg);
        } else {
            addrReg = varLoc.register;
        }

        // 计算地址
        builder.op2("addi", addrReg, "sp", String.valueOf(offset));
    }

    private void translateGetElementPtr(LLVMValueRef inst) {
        String destVar = LLVMGetValueName(inst).getString();
        LLVMValueRef basePtr = LLVMGetOperand(inst, 0);
        String baseName = LLVMGetValueName(basePtr).getString();

        builder.comment("计算指针 " + destVar);

        // 获取基地址
        String baseReg = getVariableLocation(baseName, instructionId);

        // 获取目标寄存器
        String destReg;
        Location destLoc = registerAllocator.getLocation(instructionId, destVar);

        if (destLoc == null || destLoc.type == Location.LocationType.STACK) {
            destReg = allocateTempRegister(destVar);
            allocateStackSpace(destVar);
            markRegisterDirty(destReg);
        } else {
            destReg = destLoc.register;
        }

        // 如果只有一个索引，可能是数组元素访问
        if (LLVMGetNumOperands(inst) == 2) {
            builder.move(destReg, baseReg);
        } else if (LLVMGetNumOperands(inst) == 3) {
            // 有两个索引，计算偏移量
            LLVMValueRef indexValue = LLVMGetOperand(inst, 2);

            if (LLVMIsAConstant(indexValue) != null) {
                // 常量索引
                long index = LLVMConstIntGetSExtValue(indexValue);
                int offset = (int) (index * 4); // 假设每个元素4字节

                if (offset == 0) {
                    // 无需偏移，直接使用基地址
                    builder.move(destReg, baseReg);
                } else {
                    // 计算地址 = 基地址 + 偏移量
                    builder.op2("addi", destReg, baseReg, String.valueOf(offset));
                }
            } else {
                // 变量索引，需要计算
                String indexName = LLVMGetValueName(indexValue).getString();
                String indexReg = getVariableLocation(indexName, instructionId);
                String tempReg = allocateTempRegister("index_temp");

                // 计算偏移量 = 索引 * 4
                builder.op2("slli", tempReg, indexReg, "2"); // 左移2位相当于乘4

                // 计算最终地址
                builder.op3("add", destReg, baseReg, tempReg);
            }
        }
    }

    private void translateSwitch(LLVMValueRef inst) {
        builder.comment("switch指令");

        // 获取条件值
        LLVMValueRef condition = LLVMGetOperand(inst, 0);
        String condName = LLVMGetValueName(condition).getString();
        String condReg = getVariableLocation(condName, instructionId);

        // 获取默认分支（第1个操作数）
        LLVMValueRef defaultDest = LLVMGetOperand(inst, 1);
        String defaultLabel = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(defaultDest)).getString();

        // 保存所有脏寄存器
        flushDirtyRegisters();

        // 获取case数量（(操作数数量-2)/2，因为每个case有一个值和一个目标）
        int operandCount = LLVMGetNumOperands(inst);
        int caseCount = (operandCount - 2) / 2;

        if (caseCount == 0) {
            // 如果没有case分支，直接跳到默认分支
            builder.jump(defaultLabel);
            return;
        }

        // 遍历所有case
        for (int i = 0; i < caseCount; i++) {
            // case值在操作数列表中的偶数位置（从2开始）
            LLVMValueRef caseValue = LLVMGetOperand(inst, 2 + i*2);
            // case目标在操作数列表中的奇数位置（从3开始）
            LLVMValueRef caseDest = LLVMGetOperand(inst, 3 + i*2);

            // 确保我们获取到了整数常量
            long caseConstant = 0;
            if (LLVMIsAConstantInt(caseValue) != null) {
                caseConstant = LLVMConstIntGetSExtValue(caseValue);
            } else {
                builder.comment("警告：非常量case值");
                continue;
            }

            String caseLabel = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(caseDest)).getString();

            // 比较条件值与case值
            String tempReg = allocateTempRegister("switch_temp");

            // 立即数比较
            if (caseConstant >= -2048 && caseConstant <= 2047) {
                builder.op2("addi", tempReg, condReg, "-" + caseConstant);
            } else {
                // 大常量需要先加载
                String constReg = allocateTempRegister("const_temp");
                builder.loadImm(constReg, caseConstant);
                builder.op3("sub", tempReg, condReg, constReg);
            }

            // 如果相等（差值为0），跳转到对应分支
            builder.op2("beq", tempReg, "zero", caseLabel);
        }

        // 所有case都不匹配，跳转到默认分支
        builder.jump(defaultLabel);
    }

    private void translateBitwiseOp(LLVMValueRef inst, int opcode) {
        // 获取操作数和结果变量名
        LLVMValueRef op1 = LLVMGetOperand(inst, 0);
        LLVMValueRef op2 = LLVMGetOperand(inst, 1);
        String destVar = LLVMGetValueName(inst).getString();

        // 获取操作指令
        String operation;
        switch (opcode) {
            case LLVMAnd: operation = "and"; break;
            case LLVMOr: operation = "or"; break;
            case LLVMXor: operation = "xor"; break;
            default: operation = "and"; // 默认为与操作
        }

        // 处理第一个操作数
        String op1Reg;
        if (LLVMIsAConstant(op1) != null) {
            // 如果是常量，加载到临时寄存器
            long constValue = LLVMConstIntGetSExtValue(op1);
            op1Reg = allocateTempRegister("const_temp");
            builder.loadImm(op1Reg, constValue);
        } else {
            String op1Name = LLVMGetValueName(op1).getString();
            op1Reg = getVariableLocation(op1Name, instructionId);
        }

        // 处理第二个操作数
        String op2Reg;
        if (LLVMIsAConstant(op2) != null) {
            long constValue = LLVMConstIntGetSExtValue(op2);
            // 对于位运算，可以直接使用立即数
            if (constValue >= -2048 && constValue <= 2047) {
                op2Reg = String.valueOf(constValue);
                operation += "i"; // 使用立即数版本的指令
            } else {
                op2Reg = allocateTempRegister("const_temp2");
                builder.loadImm(op2Reg, constValue);
            }
        } else {
            String op2Name = LLVMGetValueName(op2).getString();
            op2Reg = getVariableLocation(op2Name, instructionId);
        }

        // 获取结果存储位置
        Location destLoc = registerAllocator.getLocation(instructionId, destVar);
        String destReg;

        if (destLoc == null || destLoc.type == Location.LocationType.STACK) {
            // 分配临时寄存器用于结果
            destReg = allocateTempRegister(destVar);
            // 标记为脏寄存器
            allocateStackSpace(destVar);
            markRegisterDirty(destReg);
        } else {
            // 使用分配的寄存器
            destReg = destLoc.register;
        }

        // 生成位运算指令
        builder.op3(operation, destReg, op1Reg, op2Reg);
    }

    private void translateShiftOp(LLVMValueRef inst, int opcode) {
        // 获取操作数和结果变量名
        LLVMValueRef op1 = LLVMGetOperand(inst, 0);
        LLVMValueRef op2 = LLVMGetOperand(inst, 1);
        String destVar = LLVMGetValueName(inst).getString();

        // 获取操作指令
        String operation;
        switch (opcode) {
            case LLVMShl: operation = "sll"; break;  // 逻辑左移
            case LLVMLShr: operation = "srl"; break; // 逻辑右移
            case LLVMAShr: operation = "sra"; break; // 算术右移
            default: operation = "sll"; // 默认为左移
        }

        // 处理第一个操作数（被移位的数）
        String op1Reg;
        if (LLVMIsAConstant(op1) != null) {
            long constValue = LLVMConstIntGetSExtValue(op1);
            op1Reg = allocateTempRegister("const_temp");
            builder.loadImm(op1Reg, constValue);
        } else {
            String op1Name = LLVMGetValueName(op1).getString();
            op1Reg = getVariableLocation(op1Name, instructionId);
        }

        // 处理第二个操作数（移位数量）
        String op2Reg;
        if (LLVMIsAConstant(op2) != null) {
            long constValue = LLVMConstIntGetSExtValue(op2);
            if (constValue >= 0 && constValue <= 31) {
                op2Reg = String.valueOf(constValue);
                operation += "i"; // 使用立即数版本的指令
            } else {
                // 移位超出范围，需要特殊处理
                op2Reg = allocateTempRegister("const_temp2");
                builder.loadImm(op2Reg, constValue % 32); // 移位值需要模32
            }
        } else {
            String op2Name = LLVMGetValueName(op2).getString();
            op2Reg = getVariableLocation(op2Name, instructionId);

            // 确保移位值在合理范围内
            String tempReg = allocateTempRegister("shift_temp");
            builder.op2("andi", tempReg, op2Reg, "31"); // 移位值与31相与
            op2Reg = tempReg;
        }

        // 获取结果存储位置
        Location destLoc = registerAllocator.getLocation(instructionId, destVar);
        String destReg;

        if (destLoc == null || destLoc.type == Location.LocationType.STACK) {
            destReg = allocateTempRegister(destVar);
            allocateStackSpace(destVar);
            markRegisterDirty(destReg);
        } else {
            destReg = destLoc.register;
        }

        // 生成移位指令
        builder.op3(operation, destReg, op1Reg, op2Reg);
    }

    private void translateCast(LLVMValueRef inst, int opcode) {
        LLVMValueRef src = LLVMGetOperand(inst, 0);
        String srcName = LLVMGetValueName(src).getString();
        String destName = LLVMGetValueName(inst).getString();

        builder.comment("类型转换 " + opcode + "：" + srcName + " -> " + destName);

        // 获取源操作数位置
        String srcReg;
        if (LLVMIsAConstant(src) != null) {
            long constValue = LLVMConstIntGetSExtValue(src);
            srcReg = allocateTempRegister("const_temp");
            builder.loadImm(srcReg, constValue);
        } else {
            srcReg = getVariableLocation(srcName, instructionId);
        }

        // 获取目标位置
        Location destLoc = registerAllocator.getLocation(instructionId, destName);
        String destReg;

        if (destLoc == null || destLoc.type == Location.LocationType.STACK) {
            destReg = allocateTempRegister(destName);
            allocateStackSpace(destName);
            markRegisterDirty(destReg);
        } else {
            destReg = destLoc.register;
        }

        // 根据操作码处理不同类型的转换
        switch (opcode) {
            case LLVMZExt:  // 零扩展
                // 获取源类型和目标类型大小
                LLVMTypeRef srcType = LLVMTypeOf(src);
                LLVMTypeRef destType = LLVMTypeOf(inst);
                int srcBits = LLVMGetIntTypeWidth(srcType);

                if (srcBits == 1) {
                    // 布尔值转整数，不需要特殊处理，值已经是0或1
                    builder.move(destReg, srcReg);
                } else {
                    // 一般零扩展可以通过与掩码相与实现
                    int mask = (1 << srcBits) - 1;
                    builder.op2("andi", destReg, srcReg, String.valueOf(mask));
                }
                break;

            case LLVMSExt:  // 符号扩展
                // 获取源类型大小
                srcType = LLVMTypeOf(src);
                destType = LLVMTypeOf(inst);
                srcBits = LLVMGetIntTypeWidth(srcType);
                int destBits = LLVMGetIntTypeWidth(destType);

                if (srcBits == 1) {
                    // 布尔值符号扩展，仍然是0或1
                    builder.move(destReg, srcReg);
                } else if (srcBits == 8) {
                    // 8位符号扩展到32位
                    builder.op2("slli", destReg, srcReg, "24");
                    builder.op2("srai", destReg, destReg, "24");
                } else if (srcBits == 16) {
                    // 16位符号扩展到32位
                    builder.op2("slli", destReg, srcReg, "16");
                    builder.op2("srai", destReg, destReg, "16");
                } else {
                    // 其他情况，默认处理
                    builder.move(destReg, srcReg);
                }
                break;

            case LLVMTrunc:  // 截断
                // 截断只需要保留低位，RISC-V中寄存器操作隐含32位，不需要特殊指令
                builder.move(destReg, srcReg);

                // 如果需要明确截断位数
                srcType = LLVMTypeOf(src);
                destType = LLVMTypeOf(inst);
                destBits = LLVMGetIntTypeWidth(destType);

                if (destBits < 32) {
                    // 生成掩码以保留截断后的位
                    int mask = (1 << destBits) - 1;
                    builder.op2("andi", destReg, destReg, String.valueOf(mask));
                }
                break;

            default:
                builder.comment("不支持的类型转换: " + opcode);
                builder.move(destReg, srcReg);  // 默认行为
                break;
        }
    }

    private void translatePhi(LLVMValueRef inst) {
        builder.comment("phi指令");
        // Phi指令本身不生成代码，其实现由前驱基本块的跳转处理
        // 这里只需要为目标变量分配寄存器

        String destVar = LLVMGetValueName(inst).getString();
        Location destLoc = registerAllocator.getLocation(instructionId, destVar);

        if (destLoc == null || destLoc.type == Location.LocationType.STACK) {
            // 确保为变量分配了栈空间
            allocateStackSpace(destVar);
        }

        // 在实际编译过程中，需要修改前驱基本块的代码，使其在跳转前将正确的值放入phi的目标位置
        // 这需要额外的分析和处理，这里只是一个简单的占位实现
    }


}
