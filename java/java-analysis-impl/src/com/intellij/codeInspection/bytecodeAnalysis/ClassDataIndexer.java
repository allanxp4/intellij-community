/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.bytecodeAnalysis;

import com.intellij.codeInspection.bytecodeAnalysis.asm.*;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.gist.VirtualFileGist;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.analysis.AnalyzerException;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.intellij.codeInspection.bytecodeAnalysis.Direction.*;
import static com.intellij.codeInspection.bytecodeAnalysis.ProjectBytecodeAnalysis.LOG;

/**
 * Scala code (same algorithm, but easier to read): https://github.com/ilya-klyuchnikov/faba
 *
 * Based on "Nullness Analysis of Java Bytecode via Supercompilation over Abstract Values" by Ilya Klyuchnikov
 *     (http://meta2014.pereslavl.ru/papers/2014_Klyuchnikov__Nullness_Analysis_of_Java_Bytecode_via_Supercompilation_over_Abstract_Values.pdf)
 *
 * @author lambdamix
 */
public class ClassDataIndexer implements VirtualFileGist.GistCalculator<Map<Bytes, HEquations>> {

  public static final Final FINAL_TOP = new Final(Value.Top);
  public static final Final FINAL_BOT = new Final(Value.Bot);
  public static final Final FINAL_NOT_NULL = new Final(Value.NotNull);
  public static final Final FINAL_NULL = new Final(Value.Null);

  @Nullable
  @Override
  public Map<Bytes, HEquations> calcData(@NotNull Project project, @NotNull VirtualFile file) {
    HashMap<Bytes, HEquations> map = new HashMap<>();
    try {
      MessageDigest md = BytecodeAnalysisConverter.getMessageDigest();
      Map<Key, List<Equation>> allEquations = processClass(new ClassReader(file.contentsToByteArray()), file.getPresentableUrl());
      for (Map.Entry<Key, List<Equation>> entry: allEquations.entrySet()) {
        Key methodKey = entry.getKey();
        map.put(compressKey(md, methodKey), convertEquations(md, methodKey, entry.getValue()));
      }
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (Throwable e) {
      // incorrect bytecode may result in Runtime exceptions during analysis
      // so here we suppose that exception is due to incorrect bytecode
      LOG.debug("Unexpected Error during indexing of bytecode", e);
    }
    return map;
  }

  @NotNull
  static Bytes compressKey(MessageDigest md, Key methodKey) {
    return new Bytes(BytecodeAnalysisConverter.asmKey(methodKey, md).key);
  }

  @NotNull
  private static HEquations convertEquations(MessageDigest md, Key methodKey, List<Equation> rawMethodEquations) {
    List<DirectionResultPair> compressedMethodEquations =
      ContainerUtil.map(rawMethodEquations, equation -> BytecodeAnalysisConverter.convert(equation, md));
    return new HEquations(compressedMethodEquations, methodKey.stable);
  }

  public static Map<Key, List<Equation>> processClass(final ClassReader classReader, final String presentableUrl) {

    // It is OK to share pending states, actions and results for analyses.
    // Analyses are designed in such a way that they first write to states/actions/results and then read only those portion
    // of states/actions/results which were written by the current pass of the analysis.
    // Since states/actions/results are quite expensive to create (32K array) for each analysis, we create them once per class analysis.
    final State[] sharedPendingStates = new State[Analysis.STEPS_LIMIT];
    final PendingAction[] sharedPendingActions = new PendingAction[Analysis.STEPS_LIMIT];
    final PResults.PResult[] sharedResults = new PResults.PResult[Analysis.STEPS_LIMIT];
    final Map<Key, List<Equation>> equations = new HashMap<>();

    classReader.accept(new KeyedMethodVisitor() {

      protected MethodVisitor visitMethod(final MethodNode node, final Key key) {
        return new MethodVisitor(Opcodes.API_VERSION, node) {
          private boolean jsr;

          @Override
          public void visitJumpInsn(int opcode, Label label) {
            if (opcode == Opcodes.JSR) {
              jsr = true;
            }
            super.visitJumpInsn(opcode, label);
          }

          @Override
          public void visitEnd() {
            super.visitEnd();
            equations.put(key, processMethod(node, jsr, key.method, key.stable));
          }
        };
      }

      /**
       * Facade for analysis, it invokes specialized analyses for branching/non-branching methods.
       *
       * @param methodNode asm node for method
       * @param jsr whether a method has jsr instruction
       */
      private List<Equation> processMethod(final MethodNode methodNode, boolean jsr, Method method, boolean stable) {
        ProgressManager.checkCanceled();
        final Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
        final Type resultType = Type.getReturnType(methodNode.desc);
        final boolean isReferenceResult = ASMUtils.isReferenceType(resultType);
        final boolean isBooleanResult = ASMUtils.isBooleanType(resultType);
        final boolean isInterestingResult = isReferenceResult || isBooleanResult;

        // 4*n: for each reference parameter: @NotNull IN, @Nullable, null -> ... contract, !null -> contract
        // 3: @NotNull OUT, @Nullable OUT, purity analysis
        List<Equation> equations = new ArrayList<>(argumentTypes.length * 4 + 3);
        equations.add(PurityAnalysis.analyze(method, methodNode, stable));

        if (argumentTypes.length == 0 && !isInterestingResult) {
          // no need to continue analysis
          return equations;
        }

        try {
          final ControlFlowGraph graph = ControlFlowGraph.build(className, methodNode, jsr);
          if (graph.transitions.length > 0) {
            final DFSTree dfs = DFSTree.build(graph.transitions, graph.edgeCount);
            boolean branching = !dfs.back.isEmpty();
            if (!branching) {
              for (int[] transition : graph.transitions) {
                if (transition != null && transition.length > 1) {
                  branching = true;
                  break;
                }
              }
            }
            if (branching) {
              RichControlFlow richControlFlow = new RichControlFlow(graph, dfs);
              if (richControlFlow.reducible()) {
                NegationAnalysis negated = tryNegation(method, argumentTypes, graph, isBooleanResult, dfs, jsr);
                processBranchingMethod(method, methodNode, richControlFlow, argumentTypes, isReferenceResult, isBooleanResult, stable, jsr, equations, negated);
                return equations;
              }
              LOG.debug(method + ": CFG is not reducible");
            }
            // simple
            else {
              processNonBranchingMethod(method, argumentTypes, graph, isReferenceResult, isBooleanResult, stable, equations);
              return equations;
            }
          }
          return topEquations(method, argumentTypes, isReferenceResult, isInterestingResult, stable);
        }
        catch (ProcessCanceledException e) {
          throw e;
        }
        catch (Throwable e) {
          // incorrect bytecode may result in Runtime exceptions during analysis
          // so here we suppose that exception is due to incorrect bytecode
          LOG.debug("Unexpected Error during processing of " + method + " in " + presentableUrl, e);
          return topEquations(method, argumentTypes, isReferenceResult, isInterestingResult, stable);
        }
      }

      private NegationAnalysis tryNegation(final Method method,
                                           final Type[] argumentTypes,
                                           final ControlFlowGraph graph,
                                           final boolean isBooleanResult,
                                           final DFSTree dfs,
                                           final boolean jsr) throws AnalyzerException {

        class Util {
          boolean isMethodCall(int opCode) {
            return opCode == Opcodes.INVOKESTATIC ||
                   opCode == Opcodes.INVOKESPECIAL ||
                   opCode == Opcodes.INVOKEVIRTUAL ||
                   opCode == Opcodes.INVOKEINTERFACE;
          }

          boolean singleIfBranch() {
            int branch = 0;

            for (int i = 0; i < graph.transitions.length; i++) {
              int[] transition = graph.transitions[i];
              if (transition.length == 2) {
                branch++;
                int opCode = graph.methodNode.instructions.get(i).getOpcode();
                boolean isIfInsn = opCode == Opcodes.IFEQ || opCode == Opcodes.IFNE;
                if (!isIfInsn) {
                  return false;
                }
              }
              if (branch > 1)
                return false;
            }
            return branch == 1;
          }

          boolean singleMethodCall() {
            int callCount = 0;
            for (int i = 0; i < graph.transitions.length; i++) {
              if (isMethodCall(graph.methodNode.instructions.get(i).getOpcode())) {
                callCount++;
                if (callCount > 1) {
                  return false;
                }
              }
            }
            return callCount == 1;
          }

          public boolean booleanConstResult() {
            try {
              final boolean[] origins =
                OriginsAnalysis.resultOrigins(
                  leakingParametersAndFrames(method, graph.methodNode, argumentTypes, jsr).frames,
                  graph.methodNode.instructions,
                  graph);

              for (int i = 0; i < origins.length; i++) {
                if (origins[i]) {
                  int opCode = graph.methodNode.instructions.get(i).getOpcode();
                  boolean isBooleanConst = opCode == Opcodes.ICONST_0 || opCode == Opcodes.ICONST_1;
                  if (!isBooleanConst) {
                    return false;
                  }
                }
              }

              return true;
            }
            catch (AnalyzerException ignore) {
            }
            return false;
          }
        }

        if (graph.methodNode.instructions.size() < 20 && isBooleanResult && dfs.back.isEmpty() && !jsr) {
          Util util = new Util();
          if (util.singleIfBranch() && util.singleMethodCall() && util.booleanConstResult()) {
            NegationAnalysis analyzer = new NegationAnalysis(method, graph);
            try {
              analyzer.analyze();
              return analyzer;
            }
            catch (NegationAnalysisFailure ignore) {
              return null;
            }
          }
        }

        return null;
      }

      private void processBranchingMethod(final Method method,
                                          final MethodNode methodNode,
                                          final RichControlFlow richControlFlow,
                                          Type[] argumentTypes,
                                          boolean isReferenceResult,
                                          boolean isBooleanResult,
                                          final boolean stable,
                                          boolean jsr,
                                          List<Equation> result,
                                          NegationAnalysis negatedAnalysis) throws AnalyzerException {
        boolean isInterestingResult = isBooleanResult || isReferenceResult;
        boolean maybeLeakingParameter = isInterestingResult;
        for (Type argType : argumentTypes) {
          if (ASMUtils.isReferenceType(argType)) {
            maybeLeakingParameter = true;
            break;
          }
        }

        final LeakingParameters leakingParametersAndFrames =
          maybeLeakingParameter ? leakingParametersAndFrames(method, methodNode, argumentTypes, jsr) : null;

        boolean[] leakingParameters =
          leakingParametersAndFrames != null ? leakingParametersAndFrames.parameters : null;
        boolean[] leakingNullableParameters =
          leakingParametersAndFrames != null ? leakingParametersAndFrames.nullableParameters : null;

        final boolean[] origins =
          isInterestingResult ?
          OriginsAnalysis.resultOrigins(leakingParametersAndFrames.frames, methodNode.instructions, richControlFlow.controlFlow) :
          null;

        Equation outEquation =
          isInterestingResult ?
          new InOutAnalysis(richControlFlow, Out, origins, stable, sharedPendingStates).analyze() :
          null;

        if (isReferenceResult) {
          result.add(outEquation);
          result.add(new Equation(new Key(method, NullableOut, stable), NullableMethodAnalysis.analyze(methodNode, origins, jsr)));
        }

        boolean withCycle = !richControlFlow.dfsTree.back.isEmpty();
        if (argumentTypes.length > 50 && withCycle) {
          // IDEA-137443 - do not analyze very complex methods
          return;
        }

        // arguments and contract clauses
        for (int i = 0; i < argumentTypes.length; i++) {
          boolean notNullParam = false;

          if (ASMUtils.isReferenceType(argumentTypes[i])) {
            boolean possibleNPE = false;
            if (leakingParameters[i]) {
              NonNullInAnalysis notNullInAnalysis =
                new NonNullInAnalysis(richControlFlow, new In(i, In.NOT_NULL_MASK), stable, sharedPendingActions, sharedResults);
              Equation notNullParamEquation = notNullInAnalysis.analyze();
              possibleNPE = notNullInAnalysis.possibleNPE;
              notNullParam = notNullParamEquation.rhs.equals(FINAL_NOT_NULL);
              result.add(notNullParamEquation);
            }
            else {
              // parameter is not leaking, so it is definitely NOT @NotNull
              result.add(new Equation(new Key(method, new In(i, In.NOT_NULL_MASK), stable), FINAL_TOP));
            }

            if (leakingNullableParameters[i]) {
              if (notNullParam || possibleNPE) {
                result.add(new Equation(new Key(method, new In(i, In.NULLABLE_MASK), stable), FINAL_TOP));
              }
              else {
                result.add(new NullableInAnalysis(richControlFlow, new In(i, In.NULLABLE_MASK), stable, sharedPendingStates).analyze());
              }
            }
            else {
              result.add(new Equation(new Key(method, new In(i, In.NULLABLE_MASK), stable), FINAL_NULL));
            }

            if (isInterestingResult) {
              if (leakingParameters[i]) {
                if (notNullParam) {
                  // @NotNull, so "null->fail"
                  result.add(new Equation(new Key(method, new InOut(i, Value.Null), stable), FINAL_BOT));
                }
                else {
                  // may be null on some branch, running "null->..." analysis
                  if (isBooleanResult && negatedAnalysis != null) {
                      result.add(negatedAnalysis.contractEquation(i, Value.Null, stable));
                  }
                  else {
                    result.add(new InOutAnalysis(richControlFlow, new InOut(i, Value.Null), origins, stable, sharedPendingStates).analyze());
                  }
                }
                if (isBooleanResult && negatedAnalysis != null) {
                  result.add(negatedAnalysis.contractEquation(i, Value.NotNull, stable));
                }
                else {
                  result.add(new InOutAnalysis(richControlFlow, new InOut(i, Value.NotNull), origins, stable, sharedPendingStates).analyze());
                }
              }
              else {
                // parameter is not leaking, so a contract is the same as for the whole method
                result.add(new Equation(new Key(method, new InOut(i, Value.Null), stable), outEquation.rhs));
                result.add(new Equation(new Key(method, new InOut(i, Value.NotNull), stable), outEquation.rhs));
              }
            }
          }
        }
      }

      private void processNonBranchingMethod(Method method,
                                             Type[] argumentTypes,
                                             ControlFlowGraph graph,
                                             boolean isReferenceResult,
                                             boolean isBooleanResult,
                                             boolean stable,
                                             List<Equation> result) throws AnalyzerException {
        CombinedAnalysis analyzer = new CombinedAnalysis(method, graph);
        analyzer.analyze();
        if (isReferenceResult) {
          result.add(analyzer.outContractEquation(stable));
          result.add(analyzer.nullableResultEquation(stable));
        }
        for (int i = 0; i < argumentTypes.length; i++) {
          Type argType = argumentTypes[i];
          if (ASMUtils.isReferenceType(argType)) {
            result.add(analyzer.notNullParamEquation(i, stable));
            result.add(analyzer.nullableParamEquation(i, stable));
            if (isReferenceResult || isBooleanResult) {
              result.add(analyzer.contractEquation(i, Value.Null, stable));
              result.add(analyzer.contractEquation(i, Value.NotNull, stable));
            }
          }
        }
      }

      private List<Equation> topEquations(Method method,
                                                      Type[] argumentTypes,
                                                      boolean isReferenceResult,
                                                      boolean isInterestingResult,
                                                      boolean stable) {
        // 4 = @NotNull parameter, @Nullable parameter, null -> ..., !null -> ...
        List<Equation> result = new ArrayList<>(argumentTypes.length * 4 + 2);
        if (isReferenceResult) {
          result.add(new Equation(new Key(method, Out, stable), FINAL_TOP));
          result.add(new Equation(new Key(method, NullableOut, stable), FINAL_BOT));
        }
        for (int i = 0; i < argumentTypes.length; i++) {
          if (ASMUtils.isReferenceType(argumentTypes[i])) {
            result.add(new Equation(new Key(method, new In(i, In.NOT_NULL_MASK), stable), FINAL_TOP));
            result.add(new Equation(new Key(method, new In(i, In.NULLABLE_MASK), stable), FINAL_TOP));
            if (isInterestingResult) {
              result.add(new Equation(new Key(method, new InOut(i, Value.Null), stable), FINAL_TOP));
              result.add(new Equation(new Key(method, new InOut(i, Value.NotNull), stable), FINAL_TOP));
            }
          }
        }
        return result;
      }

      @NotNull
      private LeakingParameters leakingParametersAndFrames(Method method, MethodNode methodNode, Type[] argumentTypes, boolean jsr)
        throws AnalyzerException {
        return argumentTypes.length < 32 ?
                LeakingParameters.buildFast(method.internalClassName, methodNode, jsr) :
                LeakingParameters.build(method.internalClassName, methodNode, jsr);
      }
    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    return equations;
  }
}
