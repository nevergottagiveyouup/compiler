import org.bytedeco.llvm.LLVM.*;

import java.util.*;
// 导入常量相关的类
// 导入类型相关的类
// 导入构建器相关的类
// 导入运算相关的枚举
// 导入 Result 相关的类和方法

import static org.bytedeco.llvm.global.LLVM.*;

public class IrTranslater {
    private final IrVisitor visitor;
    private final LLVMModuleRef moduleRef;

    // 活跃区间表
    private final Map<String, LiveInterval> liveIntervals;
    // 标签表：块名 -> 指令编号
    private final Map<String, Integer> labelTable;
    // 全局符号表
    private final Map<String, GlobalVariableInfo> globalSymbols ;


    private LinearScan registerAllocator;
    private AsmBuilder builder = new AsmBuilder();

    // 管理临时寄存器
    private List<String> tempRegisters = Arrays.asList("t0", "t1", "t2", "t3", "t4");
    //将临时寄存器的其中两个专门用于存放常量
    private List<String> constRegisters = Arrays.asList("t5","t6");
    private Map<String, String> tempRegisterUse = new HashMap<>(); // 记录临时寄存器当前保存的变量
    private Map<String, Boolean> tempRegisterDirty = new HashMap<>(); // 记录临时寄存器是否被修改过
    private Map<String,Boolean> tempRegisterLock = new HashMap<>(); // 记录临时寄存器是否被锁定

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
        boolean isMainFunction = functionName.equals("main"); // 标识是否为 main 函数

        if (LLVMCountBasicBlocks(function) == 0) return;

        // 重置栈分配状态
        nextStackOffset = isMainFunction ? 0 : 4; // main 不需要预留 ra 空间
        varStackOffsets.clear();
        tempRegisterUse.clear();
        tempRegisterDirty.clear();

        // 预扫描所有基本块，收集 alloca 和溢出变量
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(function); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                if (LLVMGetInstructionOpcode(inst) == LLVMAlloca) {
                    String varName = LLVMGetValueName(inst).getString();
                    if (!varStackOffsets.containsKey(varName)) {
                        varStackOffsets.put(varName, nextStackOffset);
                        nextStackOffset += 4;
                    }
                }
            }
        }

        // 为溢出变量预留栈空间
        for (String varName : registerAllocator.spilledVars) {
            if (!varStackOffsets.containsKey(varName)) {
                varStackOffsets.put(varName, nextStackOffset);
                nextStackOffset += 4;
            }
        }

        // 计算总帧大小
        int frameSize = nextStackOffset;
        currentFrameSize = frameSize;

        // 函数序言
        builder.emptyLine();
        builder.comment("函数: " + functionName);
        builder.directive("globl", functionName);
        builder.label(functionName);

        if (frameSize > 0 && !isMainFunction) {
            // 仅为非 main 函数保存 ra 和分配栈空间
            builder.op2("addi", "sp", "sp", "-" + frameSize);
            builder.store("ra", "sp", 0);
        } else if (frameSize > 0) {
            // 为 main 函数仅分配局部变量空间
            builder.op2("addi", "sp", "sp", "-" + frameSize);
        }

        // 遍历基本块
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(function); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            translateBasicBlock(bb);
        }

        // 移除原有的尾声生成代码（已注释）
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

        Map<String, String> spillInfo = lookupSpillInfo(instructionId);
        if (spillInfo != null) {
            // 负责溢出变量的回栈
            for (Map.Entry<String, String> entry : spillInfo.entrySet()) {
                String varName = entry.getKey();
                String regName = entry.getValue();
                if(regName != null){
                    builder.comment("变量 " + varName + " 溢出到栈 " );
                    int offset= varStackOffsets.get(varName);
                    builder.store(regName,"sp",offset);
                }else{//说明这个溢出的变量是新定义的

                }
            }
        }

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
            /*case LLVMCall:  // 函数调用
                translateCall(inst);
                break;*/
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

    /**
     * 这是一个查询方法，如果变量仍然被安排在寄存器里，就会返回安排的位置，否则会告诉你它在栈上或者是全局变量
     * @return 存放变量的寄存器名称/global/stack/zero
     */
    private String lookupRegisterAllocation(String varName, int instId) {
        // 处理全局变量，直接返回变量名，由其他方法处理
        if (isGlobalVariable(varName)) {
            return "global";
        }

        // 查询寄存器分配器获取位置
        Location loc = registerAllocator.getLocation(instId, varName);

        // 变量在寄存器中，就返回对应的寄存器位置
        if (loc != null && loc.type == Location.LocationType.REGISTER) {
            return loc.register;
        }

        // 变量溢出到栈上
        if (loc != null && loc.type == Location.LocationType.STACK) {
            return "spill";
        }

        // 没有位置信息
        builder.comment("警告: 变量 " + varName + " 没有位置信息");
        return "zero";
    }

    private boolean isGlobalVariable(String varName) {
        // 检查变量是否在全局符号表中
        return globalSymbols.containsKey(varName);
    }

    private Map<String,String> lookupSpillInfo(int instId) {
        // 获取当前指令的溢出信息
        Map<String, String> spillInfo = registerAllocator.getFirstSpillInfo(instId);
        return spillInfo;
    }

    /**
     * 为变量分配一个临时寄存器，如有必要会将旧变量写回栈
     * @param varName 需要分配寄存器的变量名
     * @return 分配的寄存器名称
     */
    private String allocateTempRegister(String varName) {
        // 1. 首先尝试找一个空闲的临时寄存器
        for (String reg : tempRegisters) {
            if (!tempRegisterUse.containsKey(reg)) {
                tempRegisterUse.put(reg, varName);
                tempRegisterDirty.put(reg, false);
                return reg;
            }
        }

        // 2. 如果没有空闲寄存器，查找可以替换的寄存器
        for (String reg : tempRegisters) {
            // 检查寄存器是否被锁定
            if (tempRegisterLock.getOrDefault(reg, false)) {
                continue; // 跳过锁定的寄存器
            }

            String oldVarName = tempRegisterUse.get(reg);

            // 如果寄存器被修改过，需要将旧值写回栈
            if (tempRegisterDirty.getOrDefault(reg, false)) {
                // 确保旧变量在栈上有空间
                if (!varStackOffsets.containsKey(oldVarName)) {
                    varStackOffsets.put(oldVarName, nextStackOffset);
                    nextStackOffset += 4;
                }

                int offset = varStackOffsets.get(oldVarName);
                builder.comment("写回栈变量 " + oldVarName);
                builder.store(reg, "sp", offset);
            }

            // 分配寄存器给新变量
            tempRegisterUse.put(reg, varName);
            tempRegisterDirty.put(reg, false);
            return reg;
        }

        // 3. 所有临时寄存器都被锁定，尝试使用常量寄存器作为备选
        for (String reg : constRegisters) {
            if (!tempRegisterUse.containsKey(reg) || !tempRegisterLock.getOrDefault(reg, false)) {
                tempRegisterUse.put(reg, varName);
                tempRegisterDirty.put(reg, false);
                return reg;
            }
        }

        // 4. 如果实在没有可用寄存器，报错
        builder.comment("错误：无法分配临时寄存器给 " + varName);
        return "zero"; // 应急措施
    }

    /**
     * 锁定寄存器，防止被重新分配
     * @param register 要锁定的寄存器名称
     */
    private void lockRegister(String register) {
        tempRegisterLock.put(register, true);
    }

    /**
     * 解锁寄存器，允许它被重新分配
     * @param register 要解锁的寄存器名称
     */
    private void unlockRegister(String register) {
        tempRegisterLock.put(register, false);
    }

    /**
     * 标记寄存器为脏，表示后续需要写回栈
     * @param register 要标记的寄存器名称
     */
    private void markRegisterDirty(String register) {
        tempRegisterDirty.put(register, true);
    }

    private void flushDirtyRegisters() {
        // 遍历所有临时寄存器，将脏寄存器的值写回栈
        for (String reg : tempRegisters) {
            if (tempRegisterUse.containsKey(reg) && tempRegisterDirty.getOrDefault(reg, false)) {
                String varName = tempRegisterUse.get(reg);

                // 如果是栈上变量，写回栈
                if (varStackOffsets.containsKey(varName)) {
                    int offset = varStackOffsets.get(varName);
                    builder.store(reg, "sp", offset);
                    tempRegisterDirty.put(reg, false);
                }
                // 如果是全局变量，写回内存
                else if (isGlobalVariable(varName)) {
                    String addrReg = allocateTempRegister("addr_" + varName);
                    builder.la(addrReg, varName);
                    builder.store(reg, addrReg, 0);
                    tempRegisterDirty.put(reg, false);
                    unlockRegister(addrReg);
                }
            }
        }

        // 常量寄存器也需要检查
        for (String reg : constRegisters) {
            if (tempRegisterUse.containsKey(reg) && tempRegisterDirty.getOrDefault(reg, false)) {
                String varName = tempRegisterUse.get(reg);

                // 处理同上
                if (varStackOffsets.containsKey(varName)) {
                    int offset = varStackOffsets.get(varName);
                    builder.store(reg, "sp", offset);
                    tempRegisterDirty.put(reg, false);
                } else if (isGlobalVariable(varName)) {
                    String addrReg = allocateTempRegister("addr_" + varName);
                    builder.la(addrReg, varName);
                    builder.store(reg, addrReg, 0);
                    tempRegisterDirty.put(reg, false);
                    unlockRegister(addrReg);
                }
            }
        }
    }

    /**********************************************************************************/


    private void translateRet(LLVMValueRef inst) {
        builder.comment("返回指令");

        // 获取当前函数名
        LLVMValueRef function = LLVMGetBasicBlockParent(LLVMGetInstructionParent(inst));
        String functionName = LLVMGetValueName(function).getString();
        boolean isMainFunction = functionName.equals("main");

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
                String retReg = lookupRegisterAllocation(retVarName, instructionId);
                if (retReg.equals("spill")) {
                    boolean isInTempReg = false;
                    for (String reg : tempRegisters) {
                        if (tempRegisterUse.containsKey(reg) &&
                                tempRegisterUse.get(reg).equals(retVarName)) {
                            builder.move("a0", reg);
                            isInTempReg = true;
                            break;
                        }
                    }
                    if (!isInTempReg) {
                        int offset = varStackOffsets.get(retVarName);
                        builder.load("a0", "sp", offset);
                    }
                } else if (retReg.equals("global")) {
                    String addrReg = allocateTempRegister("addr_" + retVarName);
                    builder.la(addrReg, retVarName);
                    builder.load("a0", addrReg, 0);
                    unlockRegister(addrReg);
                } else {
                    builder.move("a0", retReg);
                }
            }
        } else {
            // 无返回值，默认为 0
            builder.loadImm("a0", 0);
        }

        // 确保脏寄存器写回栈
        flushDirtyRegisters();

        if (isMainFunction) {
            // 为 main 函数生成 exit 系统调用
            if (currentFrameSize > 0) {
                builder.op2("addi", "sp", "sp", String.valueOf(currentFrameSize)); // 恢复栈指针
            }
            builder.loadImm("a7", 93); // exit 系统调用号
            builder.ecall();
        } else {
            // 为其他函数生成普通返回
            if (currentFrameSize > 0) {
                builder.load("ra", "sp", 0); // 恢复返回地址
                builder.op2("addi", "sp", "sp", String.valueOf(currentFrameSize)); // 恢复栈指针
            }
            builder.ret();
        }
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
            // 如果是常量，加载到常量
            long constValue = LLVMConstIntGetSExtValue(op1);
            op1Reg = "t5";
            builder.loadImm(op1Reg, constValue);
        } else {
            String op1Name = LLVMGetValueName(op1).getString();
            op1Reg = lookupRegisterAllocation(op1Name, instructionId);
            if(op1Reg == "spill") {//被溢出，可能在栈/临时寄存器
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(op1Name)) {
                        op1Reg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(isInTempReg==false){
                   op1Reg = allocateTempRegister(op1Name);
                }
            } else if (op1Reg == "global") { // 如果操作数是全局变量，需要先分配一个临时寄存器
                //这里为了避免两次分配临时寄存器互相覆盖，直接指定寄存器
                op1Reg = allocateTempRegister(op1Name);
                lockRegister(op1Reg);
                String addrReg = allocateTempRegister("addr_" + op1Name);
                builder.la(addrReg, op1Name);   // 加载全局变量地址到临时寄存器
                builder.load(op1Reg, addrReg, 0);  // 从地址加载值到目标寄存器
            }else{//查询到操作数在寄存器上，操作数一定已经定义过，这里的寄存器位置一定不是表示这条指令完成后的位置
                lockRegister(op1Reg);
            }
        }
        markRegisterDirty(op1Reg);
        lockRegister(op1Reg);


        // 处理第二个操作数
        String op2Reg;
        if (LLVMIsAConstant(op2) != null) {
            // 如果是常量，加载到临时寄存器
            long constValue = LLVMConstIntGetSExtValue(op2);
            op2Reg = "t6";
            builder.loadImm(op2Reg, constValue);
        } else {
            String op2Name = LLVMGetValueName(op2).getString();
            op2Reg = lookupRegisterAllocation(op2Name, instructionId);
            if(op2Reg == "spill") {//被溢出，可能在栈/临时寄存器
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(op2Name)) {
                        op2Reg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(isInTempReg==false){
                    op2Reg = allocateTempRegister(op2Name);
                }
            } else if (op2Reg == "global") { // 如果操作数是全局变量，需要先分配一个临时寄存器
                //这里为了避免两次分配临时寄存器互相覆盖，直接指定寄存器
                op2Reg = allocateTempRegister(op2Name);
                lockRegister(op2Reg);
                String addrReg = allocateTempRegister("addr_" + op2Name);
                builder.la(addrReg, op2Name);   // 加载全局变量地址到临时寄存器
                builder.load(op2Reg, addrReg, 0);  // 从地址加载值到目标寄存器
                //此时t3解放了，但是t2还有用，在这个指令期间不能改变
            }else{//查询到操作数在寄存器上，操作数一定已经定义过，这里的寄存器位置一定不是表示这条指令完成后的位置
                //什么也不做
            }
        }
        markRegisterDirty(op2Reg);
        lockRegister(op2Reg);


        String destReg = lookupRegisterAllocation(destVar, instructionId);
        if(destReg.equals("spill")) {
            // 结果需要溢出到栈上，分配临时寄存器
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            // 结果写入全局变量，需要临时寄存器存结果
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else  {
            // 结果在非临时寄存器中，什么也不做
        }
        lockRegister(destReg);

        // 生成计算指令
        builder.op3(operation, destReg, op1Reg, op2Reg);
        unlockRegister(op1Reg);
        unlockRegister(op2Reg);

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(destVar, instructionId).equals("global")) {
            // 分配临时寄存器用于加载地址
            String addrReg = allocateTempRegister("addr_" + destVar);
            builder.la(addrReg, destVar);  // 加载全局变量地址到临时寄存器
            builder.store(destReg, addrReg, 0);  // 将结果存储到全局变量内存位置
            tempRegisterDirty.put(destReg, false);  // 结果已写回，取消脏标记
        }
        unlockRegister(destReg);
    }

    private void translateLoad(LLVMValueRef inst) {
        String destVar = LLVMGetValueName(inst).getString();
        LLVMValueRef pointer = LLVMGetOperand(inst, 0);
        String pointerName = LLVMGetValueName(pointer).getString();

        builder.comment("加载 " + destVar + " 从 " + pointerName);

        // 处理指针
        String ptrReg;
        if (isGlobalVariable(pointerName)) {
            // 指针是全局变量
            ptrReg = allocateTempRegister("addr_" + pointerName);
            builder.la(ptrReg, pointerName);
        } else {
            // 指针是局部变量
            ptrReg = lookupRegisterAllocation(pointerName, instructionId);
            if(ptrReg == "spill") {
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(pointerName)) {
                        ptrReg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(!isInTempReg) {
                    ptrReg = allocateTempRegister(pointerName);
                    int offset = varStackOffsets.get(pointerName);
                    builder.load(ptrReg, "sp", offset);
                }
            }
        }
        lockRegister(ptrReg);

        // 获取目标寄存器
        String destReg = lookupRegisterAllocation(destVar, instructionId);
        if(destReg.equals("spill")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        }
        lockRegister(destReg);

        // 执行加载操作
        builder.load(destReg, ptrReg, 0);

        // 解锁指针寄存器
        unlockRegister(ptrReg);

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(destVar, instructionId).equals("global")) {
            String addrReg = allocateTempRegister("addr_" + destVar);
            builder.la(addrReg, destVar);
            builder.store(destReg, addrReg, 0);
            tempRegisterDirty.put(destReg, false);
        }

        unlockRegister(destReg);
    }

    private void translateStore(LLVMValueRef inst) {
        LLVMValueRef valueRef = LLVMGetOperand(inst, 0);  // 要存储的值
        LLVMValueRef pointerRef = LLVMGetOperand(inst, 1); // 指向存储位置的指针
        String pointerName = LLVMGetValueName(pointerRef).getString();

        builder.comment("存储到 " + pointerName);

        // 处理源值
        String valueReg;
        if (LLVMIsAConstant(valueRef) != null) {
            // 值是常量
            long constValue = LLVMConstIntGetSExtValue(valueRef);
            valueReg = allocateTempRegister("const_temp");
            builder.loadImm(valueReg, constValue);
        } else {
            // 值是变量
            String valueName = LLVMGetValueName(valueRef).getString();
            valueReg = lookupRegisterAllocation(valueName, instructionId);
            if(valueReg == "spill") {
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(valueName)) {
                        valueReg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(!isInTempReg) {
                    valueReg = allocateTempRegister(valueName);
                    int offset = varStackOffsets.get(valueName);
                    builder.load(valueReg, "sp", offset);
                }
            } else if (valueReg == "global") {
                valueReg = allocateTempRegister(valueName);
                lockRegister(valueReg);
                String addrReg = allocateTempRegister("addr_" + valueName);
                builder.la(addrReg, valueName);
                builder.load(valueReg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        lockRegister(valueReg);

        // 处理指针
        String ptrReg;
        if (isGlobalVariable(pointerName)) {
            // 指针指向全局变量
            ptrReg = allocateTempRegister("addr_" + pointerName);
            builder.la(ptrReg, pointerName);
        } else {
            // 指针是局部变量
            ptrReg = lookupRegisterAllocation(pointerName, instructionId);
            if(ptrReg == "spill") {
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(pointerName)) {
                        ptrReg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(!isInTempReg) {
                    ptrReg = allocateTempRegister(pointerName);
                    int offset = varStackOffsets.get(pointerName);
                    builder.load(ptrReg, "sp", offset);
                }
            } else if (ptrReg == "global") {
                ptrReg = allocateTempRegister(pointerName);
                lockRegister(ptrReg);
                String addrReg = allocateTempRegister("addr_" + pointerName);
                builder.la(addrReg, pointerName);
                builder.load(ptrReg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        lockRegister(ptrReg);

        // 执行存储操作
        builder.store(valueReg, ptrReg, 0);

        // 解锁寄存器
        unlockRegister(valueReg);
        unlockRegister(ptrReg);
    }

    private void translateBr(LLVMValueRef inst) {
        int operandCount = LLVMGetNumOperands(inst);

        if (operandCount == 1) {
            // 无条件分支
            LLVMValueRef dest = LLVMGetOperand(inst, 0);
            String label = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(dest)).getString();
            builder.comment("无条件跳转到 " + label);

            // 在跳转前确保所有脏寄存器的值都写回了栈
            for (String reg : tempRegisters) {
                if (tempRegisterUse.containsKey(reg) && tempRegisterDirty.getOrDefault(reg, false)) {
                    String varName = tempRegisterUse.get(reg);
                    if (varStackOffsets.containsKey(varName)) {
                        int offset = varStackOffsets.get(varName);
                        builder.store(reg, "sp", offset);
                        tempRegisterDirty.put(reg, false);
                    }
                }
            }

            builder.jump(label);
        } else if (operandCount == 3) {
            // 条件分支
            LLVMValueRef condValue = LLVMGetOperand(inst, 0);
            LLVMValueRef trueDest = LLVMGetOperand(inst, 1);
            LLVMValueRef falseDest = LLVMGetOperand(inst, 2);

            String trueLabel = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(trueDest)).getString();
            String falseLabel = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(falseDest)).getString();

            builder.comment("条件跳转: 如果为真则去 " + trueLabel + " 否则去 " + falseLabel);

            // 获取条件值的寄存器
            String condReg;
            if (LLVMIsAConstant(condValue) != null) {
                // 条件是常量
                long constValue = LLVMConstIntGetSExtValue(condValue);
                condReg = allocateTempRegister("cond_temp");
                builder.loadImm(condReg, constValue);
            } else {
                // 条件是变量
                String condName = LLVMGetValueName(condValue).getString();
                condReg = lookupRegisterAllocation(condName, instructionId);
                if(condReg == "spill") {
                    boolean isInTempReg = false;
                    for (String reg : tempRegisters) {
                        if (tempRegisterUse.containsKey(reg) &&
                                tempRegisterUse.get(reg).equals(condName)) {
                            condReg = reg;
                            isInTempReg = true;
                            break;
                        }
                    }
                    if(!isInTempReg) {
                        condReg = allocateTempRegister(condName);
                        int offset = varStackOffsets.get(condName);
                        builder.load(condReg, "sp", offset);
                    }
                } else if (condReg == "global") {
                    condReg = allocateTempRegister(condName);
                    lockRegister(condReg);
                    String addrReg = allocateTempRegister("addr_" + condName);
                    builder.la(addrReg, condName);
                    builder.load(condReg, addrReg, 0);
                    unlockRegister(addrReg);
                }
            }
            lockRegister(condReg);

            // 在跳转前确保所有脏寄存器的值都写回了栈
            for (String reg : tempRegisters) {
                if (tempRegisterUse.containsKey(reg) && tempRegisterDirty.getOrDefault(reg, false)) {
                    String varName = tempRegisterUse.get(reg);
                    if (varStackOffsets.containsKey(varName)) {
                        int offset = varStackOffsets.get(varName);
                        builder.store(reg, "sp", offset);
                        tempRegisterDirty.put(reg, false);
                    }
                }
            }

            // 条件分支指令
            builder.branch("bnez", condReg, trueLabel);
            builder.jump(falseLabel); // 如果条件为假，跳转到假分支

            unlockRegister(condReg);
        }
    }

    private void translateICmp(LLVMValueRef inst) {
        // 获取操作数和结果变量名
        LLVMValueRef op1 = LLVMGetOperand(inst, 0);
        LLVMValueRef op2 = LLVMGetOperand(inst, 1);
        String destVar = LLVMGetValueName(inst).getString();
        int predicate = LLVMGetICmpPredicate(inst);

        // 处理第一个操作数
        String op1Reg;
        if (LLVMIsAConstant(op1) != null) {
            long constValue = LLVMConstIntGetSExtValue(op1);
            op1Reg = "t5";
            builder.loadImm(op1Reg, constValue);
        } else {
            String op1Name = LLVMGetValueName(op1).getString();
            op1Reg = lookupRegisterAllocation(op1Name, instructionId);
            if(op1Reg == "spill") {
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(op1Name)) {
                        op1Reg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(!isInTempReg) {
                    op1Reg = allocateTempRegister(op1Name);
                    int offset = varStackOffsets.get(op1Name);
                    builder.load(op1Reg, "sp", offset);
                }
            } else if (op1Reg == "global") {
                op1Reg = allocateTempRegister(op1Name);
                lockRegister(op1Reg);
                String addrReg = allocateTempRegister("addr_" + op1Name);
                builder.la(addrReg, op1Name);
                builder.load(op1Reg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        lockRegister(op1Reg);

        // 处理第二个操作数
        String op2Reg;
        if (LLVMIsAConstant(op2) != null) {
            long constValue = LLVMConstIntGetSExtValue(op2);
            op2Reg = "t6";
            builder.loadImm(op2Reg, constValue);
        } else {
            String op2Name = LLVMGetValueName(op2).getString();
            op2Reg = lookupRegisterAllocation(op2Name, instructionId);
            if(op2Reg == "spill") {
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(op2Name)) {
                        op2Reg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(!isInTempReg) {
                    op2Reg = allocateTempRegister(op2Name);
                    int offset = varStackOffsets.get(op2Name);
                    builder.load(op2Reg, "sp", offset);
                }
            } else if (op2Reg == "global") {
                op2Reg = allocateTempRegister(op2Name);
                lockRegister(op2Reg);
                String addrReg = allocateTempRegister("addr_" + op2Name);
                builder.la(addrReg, op2Name);
                builder.load(op2Reg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        lockRegister(op2Reg);

        // 获取结果寄存器
        String destReg = lookupRegisterAllocation(destVar, instructionId);
        if(destReg.equals("spill")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        }
        lockRegister(destReg);

        // 根据比较类型生成指令
        switch (predicate) {
            case LLVMIntEQ:  // 等于
                builder.op3("xor", destReg, op1Reg, op2Reg);
                builder.op3("seqz", destReg, destReg, "");
                break;
            case LLVMIntNE:  // 不等于
                builder.op3("xor", destReg, op1Reg, op2Reg);
                builder.op3("snez", destReg, destReg, "");
                break;
            case LLVMIntSGT:  // 有符号大于
                builder.op3("sgt", destReg, op1Reg, op2Reg);
                break;
            case LLVMIntSGE:  // 有符号大于等于
                builder.op3("slt", destReg, op1Reg, op2Reg);
                builder.op3("xori", destReg, destReg, "1");
                break;
            case LLVMIntSLT:  // 有符号小于
                builder.op3("slt", destReg, op1Reg, op2Reg);
                break;
            case LLVMIntSLE:  // 有符号小于等于
                builder.op3("sgt", destReg, op1Reg, op2Reg);
                builder.op3("xori", destReg, destReg, "1");
                break;
            default:
                builder.comment("不支持的比较类型: " + predicate);
                break;
        }

        // 解锁操作数寄存器
        unlockRegister(op1Reg);
        unlockRegister(op2Reg);

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(destVar, instructionId).equals("global")) {
            String addrReg = allocateTempRegister("addr_" + destVar);
            builder.la(addrReg, destVar);
            builder.store(destReg, addrReg, 0);
            tempRegisterDirty.put(destReg, false);
        }

        unlockRegister(destReg);
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

    private void translateAlloca(LLVMValueRef inst) {
        String varName = LLVMGetValueName(inst).getString();
        builder.comment("栈分配 " + varName);

        // 为变量分配栈空间
        if (!varStackOffsets.containsKey(varName)) {
            varStackOffsets.put(varName, nextStackOffset);
            nextStackOffset += 4; // 每个变量占4字节
        }

        // 获取目标寄存器位置
        String destReg = lookupRegisterAllocation(varName, instructionId);
        if(destReg.equals("spill")) {
            destReg = allocateTempRegister(varName);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            destReg = allocateTempRegister(varName);
            markRegisterDirty(destReg);
        }
        lockRegister(destReg);

        // 计算栈上地址并存入目标寄存器
        int offset = varStackOffsets.get(varName);
        builder.op2("addi", destReg, "sp", String.valueOf(offset));

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(varName, instructionId).equals("global")) {
            String addrReg = allocateTempRegister("addr_" + varName);
            builder.la(addrReg, varName);
            builder.store(destReg, addrReg, 0);
            tempRegisterDirty.put(destReg, false);
        }

        unlockRegister(destReg);
    }

    private void translateGetElementPtr(LLVMValueRef inst) {
        String destVar = LLVMGetValueName(inst).getString();
        LLVMValueRef basePtr = LLVMGetOperand(inst, 0);
        String baseName = LLVMGetValueName(basePtr).getString();

        builder.comment("计算指针 " + destVar);

        // 获取基地址
        String baseReg;
        if (isGlobalVariable(baseName)) {
            baseReg = allocateTempRegister("addr_" + baseName);
            builder.la(baseReg, baseName);
        } else {
            baseReg = lookupRegisterAllocation(baseName, instructionId);
            if(baseReg == "spill") {
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(baseName)) {
                        baseReg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(!isInTempReg) {
                    baseReg = allocateTempRegister(baseName);
                    int offset = varStackOffsets.get(baseName);
                    builder.load(baseReg, "sp", offset);
                }
            } else if (baseReg == "global") {
                baseReg = allocateTempRegister(baseName);
                lockRegister(baseReg);
                String addrReg = allocateTempRegister("addr_" + baseName);
                builder.la(addrReg, baseName);
                builder.load(baseReg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        lockRegister(baseReg);

        // 获取目标寄存器
        String destReg = lookupRegisterAllocation(destVar, instructionId);
        if(destReg.equals("spill")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        }
        lockRegister(destReg);

        // 处理索引和偏移计算
        if (LLVMGetNumOperands(inst) == 2) {
            // 只有一个索引，可能是全局变量直接索引
            builder.move(destReg, baseReg);
        } else if (LLVMGetNumOperands(inst) == 3) {
            // 有两个索引，通常第一个是0，第二个是实际索引
            LLVMValueRef indexValue = LLVMGetOperand(inst, 2);
            if (LLVMIsAConstant(indexValue) != null) {
                long index = LLVMConstIntGetSExtValue(indexValue);
                long offset = index * 4; // 假设元素大小为4字节

                if (offset == 0) {
                    builder.move(destReg, baseReg);
                } else {
                    builder.op2("addi", destReg, baseReg, String.valueOf(offset));
                }
            } else {
                // 如果索引是变量，需要先加载，然后计算偏移
                String indexName = LLVMGetValueName(indexValue).getString();
                String indexReg = lookupRegisterAllocation(indexName, instructionId);
                if(indexReg == "spill") {
                    boolean isInTempReg = false;
                    for (String reg : tempRegisters) {
                        if (tempRegisterUse.containsKey(reg) &&
                                tempRegisterUse.get(reg).equals(indexName)) {
                            indexReg = reg;
                            isInTempReg = true;
                            break;
                        }
                    }
                    if(!isInTempReg) {
                        indexReg = allocateTempRegister(indexName);
                        int offset = varStackOffsets.get(indexName);
                        builder.load(indexReg, "sp", offset);
                    }
                } else if (indexReg == "global") {
                    indexReg = allocateTempRegister(indexName);
                    lockRegister(indexReg);
                    String addrReg = allocateTempRegister("addr_" + indexName);
                    builder.la(addrReg, indexName);
                    builder.load(indexReg, addrReg, 0);
                    unlockRegister(addrReg);
                }
                lockRegister(indexReg);

                // 计算偏移地址：基地址 + 索引*4
                String tempReg = allocateTempRegister("temp_index_mult");
                builder.op2("slli", tempReg, indexReg, "2"); // 乘以4
                builder.op3("add", destReg, baseReg, tempReg); // 基地址+偏移

                unlockRegister(indexReg);
                unlockRegister(tempReg);
            }
        }

        // 解锁基地址寄存器
        unlockRegister(baseReg);

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(destVar, instructionId).equals("global")) {
            String addrReg = allocateTempRegister("addr_" + destVar);
            builder.la(addrReg, destVar);
            builder.store(destReg, addrReg, 0);
            tempRegisterDirty.put(destReg, false);
        }

        unlockRegister(destReg);
    }

    private void translateSwitch(LLVMValueRef inst) {
        builder.comment("switch指令");

        // 获取条件值
        LLVMValueRef condition = LLVMGetOperand(inst, 0);

        // 处理条件寄存器
        String condReg;
        if (LLVMIsAConstant(condition) != null) {
            // 条件是常量
            long constValue = LLVMConstIntGetSExtValue(condition);
            condReg = allocateTempRegister("cond_temp");
            builder.loadImm(condReg, constValue);
        } else {
            // 条件是变量
            String condName = LLVMGetValueName(condition).getString();
            condReg = lookupRegisterAllocation(condName, instructionId);
            if(condReg.equals("spill")) {
                // 条件在栈上，需要加载到寄存器
                condReg = allocateTempRegister(condName);
                int offset = varStackOffsets.get(condName);
                builder.load(condReg, "sp", offset);
            } else if(condReg.equals("global")) {
                // 条件是全局变量，需要加载到寄存器
                condReg = allocateTempRegister(condName);
                String addrReg = allocateTempRegister("addr_" + condName);
                builder.la(addrReg, condName);
                builder.load(condReg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        lockRegister(condReg);

        // 获取默认分支
        LLVMValueRef defaultDest = LLVMGetOperand(inst, 1);
        String defaultLabel = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(defaultDest)).getString();

        // 保存所有脏寄存器
        flushDirtyRegisters();

        // 获取case数量
        int operandCount = LLVMGetNumOperands(inst);
        int caseCount = (operandCount - 2) / 2;

        if (caseCount == 0) {
            // 如果没有case分支，直接跳到默认分支
            builder.jump(defaultLabel);
            unlockRegister(condReg);
            return;
        }

        // 遍历所有case
        for (int i = 0; i < caseCount; i++) {
            // 获取case值和目标
            LLVMValueRef caseValue = LLVMGetOperand(inst, 2 + i*2);
            LLVMValueRef caseDest = LLVMGetOperand(inst, 3 + i*2);

            // 确保我们获取到了整数常量
            long caseConstant = 0;
            if (LLVMIsAConstantInt(caseValue) != null) {
                caseConstant = LLVMConstIntGetSExtValue(caseValue);
            }

            String caseLabel = LLVMGetBasicBlockName(LLVMValueAsBasicBlock(caseDest)).getString();

            // 比较条件值与case值
            String tempReg = allocateTempRegister("switch_temp");
            lockRegister(tempReg);

            // 立即数比较
            if (caseConstant >= -2048 && caseConstant <= 2047) {
                // 小的立即数可以直接在RISC-V中比较
                builder.op2("addi", tempReg, condReg, String.valueOf(-caseConstant));
            } else {
                // 大的立即数需要先加载到寄存器
                String constReg = allocateTempRegister("case_const");
                lockRegister(constReg);
                builder.loadImm(constReg, caseConstant);
                builder.op3("sub", tempReg, condReg, constReg);
                unlockRegister(constReg);
            }

            // 如果相等（差值为0），跳转到对应分支
            builder.op2("beq", tempReg, "zero", caseLabel);
            unlockRegister(tempReg);
        }

        // 所有case都不匹配，跳转到默认分支
        builder.jump(defaultLabel);
        unlockRegister(condReg);
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
            // 如果是常量，加载到常量寄存器
            long constValue = LLVMConstIntGetSExtValue(op1);
            op1Reg = "t5";
            builder.loadImm(op1Reg, constValue);
        } else {
            String op1Name = LLVMGetValueName(op1).getString();
            op1Reg = lookupRegisterAllocation(op1Name, instructionId);
            if(op1Reg == "spill") {
                // 被溢出，可能在栈/临时寄存器
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(op1Name)) {
                        op1Reg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(!isInTempReg) {
                    op1Reg = allocateTempRegister(op1Name);
                    int offset = varStackOffsets.get(op1Name);
                    builder.load(op1Reg, "sp", offset);
                }
            } else if (op1Reg == "global") {
                op1Reg = allocateTempRegister(op1Name);
                lockRegister(op1Reg);
                String addrReg = allocateTempRegister("addr_" + op1Name);
                builder.la(addrReg, op1Name);
                builder.load(op1Reg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        markRegisterDirty(op1Reg);
        lockRegister(op1Reg);

        // 处理第二个操作数
        String op2Reg;
        if (LLVMIsAConstant(op2) != null) {
            long constValue = LLVMConstIntGetSExtValue(op2);
            op2Reg = "t6";
            builder.loadImm(op2Reg, constValue);
        } else {
            String op2Name = LLVMGetValueName(op2).getString();
            op2Reg = lookupRegisterAllocation(op2Name, instructionId);
            if(op2Reg == "spill") {
                boolean isInTempReg = false;
                for (String reg : tempRegisters) {
                    if (tempRegisterUse.containsKey(reg) &&
                            tempRegisterUse.get(reg).equals(op2Name)) {
                        op2Reg = reg;
                        isInTempReg = true;
                        break;
                    }
                }
                if(!isInTempReg) {
                    op2Reg = allocateTempRegister(op2Name);
                    int offset = varStackOffsets.get(op2Name);
                    builder.load(op2Reg, "sp", offset);
                }
            } else if (op2Reg == "global") {
                op2Reg = allocateTempRegister(op2Name);
                lockRegister(op2Reg);
                String addrReg = allocateTempRegister("addr_" + op2Name);
                builder.la(addrReg, op2Name);
                builder.load(op2Reg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        markRegisterDirty(op2Reg);
        lockRegister(op2Reg);

        // 处理目标寄存器
        String destReg = lookupRegisterAllocation(destVar, instructionId);
        if(destReg.equals("spill")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        }
        lockRegister(destReg);

        // 生成位运算指令
        builder.op3(operation, destReg, op1Reg, op2Reg);

        // 解锁操作数寄存器
        unlockRegister(op1Reg);
        unlockRegister(op2Reg);

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(destVar, instructionId).equals("global")) {
            String addrReg = allocateTempRegister("addr_" + destVar);
            builder.la(addrReg, destVar);
            builder.store(destReg, addrReg, 0);
            tempRegisterDirty.put(destReg, false);
        }

        unlockRegister(destReg);
    }

    private void translateShiftOp(LLVMValueRef inst, int opcode) {
        // 获取操作数和结果变量名
        LLVMValueRef op1 = LLVMGetOperand(inst, 0); // 被移位的值
        LLVMValueRef op2 = LLVMGetOperand(inst, 1); // 移位量
        String destVar = LLVMGetValueName(inst).getString();

        // 获取移位操作类型
        String operation;
        switch (opcode) {
            case LLVMShl: operation = "sll"; break;  // 逻辑左移
            case LLVMLShr: operation = "srl"; break; // 逻辑右移
            case LLVMAShr: operation = "sra"; break; // 算术右移
            default: operation = "sll"; // 默认为左移
        }

        // 处理第一个操作数（被移位的值）
        String op1Reg;
        if (LLVMIsAConstant(op1) != null) {
            long constValue = LLVMConstIntGetSExtValue(op1);
            op1Reg = allocateTempRegister("shiftop_const1");
            builder.loadImm(op1Reg, constValue);
        } else {
            String op1Name = LLVMGetValueName(op1).getString();
            op1Reg = lookupRegisterAllocation(op1Name, instructionId);
            if(op1Reg.equals("spill")) {
                // 从栈上加载
                int offset = varStackOffsets.get(op1Name);
                op1Reg = allocateTempRegister(op1Name);
                builder.load(op1Reg, "sp", offset);
            } else if (op1Reg.equals("global")) {
                // 从全局变量加载
                op1Reg = allocateTempRegister(op1Name);
                String addrReg = allocateTempRegister("addr_" + op1Name);
                builder.la(addrReg, op1Name);
                builder.load(op1Reg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        markRegisterDirty(op1Reg);
        lockRegister(op1Reg);

        // 处理第二个操作数（移位量）
        String op2Reg;
        if (LLVMIsAConstant(op2) != null) {
            // 处理常量移位量
            long constValue = LLVMConstIntGetSExtValue(op2);
            op2Reg = allocateTempRegister("shiftop_const2");
            builder.loadImm(op2Reg, constValue);
        } else {
            String op2Name = LLVMGetValueName(op2).getString();
            op2Reg = lookupRegisterAllocation(op2Name, instructionId);
            if(op2Reg.equals("spill")) {
                // 从栈上加载
                int offset = varStackOffsets.get(op2Name);
                op2Reg = allocateTempRegister(op2Name);
                builder.load(op2Reg, "sp", offset);
            } else if (op2Reg.equals("global")) {
                // 从全局变量加载
                op2Reg = allocateTempRegister(op2Name);
                String addrReg = allocateTempRegister("addr_" + op2Name);
                builder.la(addrReg, op2Name);
                builder.load(op2Reg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        markRegisterDirty(op2Reg);
        lockRegister(op2Reg);

        // 获取目标寄存器
        String destReg = lookupRegisterAllocation(destVar, instructionId);
        if(destReg.equals("spill")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        }
        lockRegister(destReg);

        // 生成移位指令 - 统一使用寄存器版本
        builder.op3(operation, destReg, op1Reg, op2Reg);

        // 解锁操作数寄存器
        unlockRegister(op1Reg);
        unlockRegister(op2Reg);

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(destVar, instructionId).equals("global")) {
            String addrReg = allocateTempRegister("addr_" + destVar);
            builder.la(addrReg, destVar);
            builder.store(destReg, addrReg, 0);
            tempRegisterDirty.put(destReg, false);
        }

        unlockRegister(destReg);
    }

    private void translateCast(LLVMValueRef inst, int opcode) {
        LLVMValueRef srcValue = LLVMGetOperand(inst, 0);
        String destVar = LLVMGetValueName(inst).getString();

        builder.comment("类型转换 " + destVar);

        // 处理源操作数
        String srcReg;
        if (LLVMIsAConstant(srcValue) != null) {
            long constValue = LLVMConstIntGetSExtValue(srcValue);
            srcReg = allocateTempRegister("cast_const");
            builder.loadImm(srcReg, constValue);
        } else {
            String srcName = LLVMGetValueName(srcValue).getString();
            srcReg = lookupRegisterAllocation(srcName, instructionId);
            if(srcReg.equals("spill")) {
                int offset = varStackOffsets.get(srcName);
                srcReg = allocateTempRegister(srcName);
                builder.load(srcReg, "sp", offset);
            } else if(srcReg.equals("global")) {
                srcReg = allocateTempRegister(srcName);
                String addrReg = allocateTempRegister("addr_" + srcName);
                builder.la(addrReg, srcName);
                builder.load(srcReg, addrReg, 0);
                unlockRegister(addrReg);
            }
        }
        lockRegister(srcReg);

        // 获取目标寄存器
        String destReg = lookupRegisterAllocation(destVar, instructionId);
        if(destReg.equals("spill")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        }
        lockRegister(destReg);

        // 根据转换类型生成指令
        switch (opcode) {
            case LLVMTrunc:
            case LLVMZExt:
            case LLVMSExt:
                // 在RISC-V中，整数类型转换可能只需要简单地将值从一个寄存器复制到另一个
                builder.move(destReg, srcReg);
                break;
            default:
                builder.comment("不支持的类型转换: " + opcode);
                break;
        }

        unlockRegister(srcReg);

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(destVar, instructionId).equals("global")) {
            String addrReg = allocateTempRegister("addr_" + destVar);
            builder.la(addrReg, destVar);
            builder.store(destReg, addrReg, 0);
            tempRegisterDirty.put(destReg, false);
        }

        unlockRegister(destReg);
    }

    private void translatePhi(LLVMValueRef inst) {
        String destVar = LLVMGetValueName(inst).getString();
        builder.comment("处理phi指令 " + destVar);

        // 获取当前基本块的前一个基本块
        LLVMBasicBlockRef currentBB = LLVMGetInstructionParent(inst);
        String currentBBName = LLVMGetBasicBlockName(currentBB).getString();

        // 获取目标寄存器
        String destReg = lookupRegisterAllocation(destVar, instructionId);
        if(destReg.equals("spill")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        } else if(destReg.equals("global")) {
            destReg = allocateTempRegister(destVar);
            markRegisterDirty(destReg);
        }
        lockRegister(destReg);

        // phi指令的处理会在前导块的结尾完成
        // 这里我们只标记destReg为phi变量的目标寄存器
        // phi指令的实际处理将由前导块在跳转前完成

        // 如果目标是全局变量，需要将结果写回
        if(lookupRegisterAllocation(destVar, instructionId).equals("global")) {
            String addrReg = allocateTempRegister("addr_" + destVar);
            builder.la(addrReg, destVar);
            builder.store(destReg, addrReg, 0);
            tempRegisterDirty.put(destReg, false);
        }

        unlockRegister(destReg);
    }


}
