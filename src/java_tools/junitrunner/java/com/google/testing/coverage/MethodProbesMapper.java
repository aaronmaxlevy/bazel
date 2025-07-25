// Copyright 2016 The Bazel Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.testing.coverage;

import static java.util.Comparator.comparing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jacoco.core.internal.analysis.Instruction;
import org.jacoco.core.internal.analysis.filter.IFilter;
import org.jacoco.core.internal.analysis.filter.IFilterContext;
import org.jacoco.core.internal.analysis.filter.IFilterOutput;
import org.jacoco.core.internal.flow.IFrame;
import org.jacoco.core.internal.flow.LabelInfo;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The mapper is a probes visitor that will cache control flow information as well as keeping track
 * of the probes as the main driver generates the probe ids. Upon finishing the method it uses the
 * information collected to generate the mapping information between probes and the instructions.
 */
public class MethodProbesMapper extends MethodProbesVisitor implements IFilterOutput {
  /*
   * The implementation roughly follows the same pattern of the Analyzer class of Jacoco.
   *
   * The mapper has a few states:
   *
   * - lineMappings: a mapping between line number and labels
   *
   * - a sequence of "instructions", where each instruction has one or more predecessors. The
   * predecessor field has a sole purpose of propagating probe id. The 'merge' nodes in the CFG has
   * no predecessors, since the branch stops at theses points.
   *
   * - The instructions each has states that keep track of the probes that are associated with the
   * instruction.
   *
   * Initially the probe ids are assigned to the instructions that immediately precede the probe. At
   * the end of visiting the methods, the probe ids are propagated through the predecessor chains.
   */

  // States
  //
  // These are state variables that needs to be updated in the visitor methods.
  // The values usually changes as we traverse the byte code.
  private Instruction lastInstruction = null;
  private int currentLine = -1;
  private List<Label> currentLabels = new ArrayList<>();
  private AbstractInsnNode currentInstructionNode = null;
  private Map<AbstractInsnNode, Instruction> instructionMap = new HashMap<>();
  private int instructionNodeIndex = 0;
  private Map<AbstractInsnNode, Integer> instructionNodeIndexMap = new HashMap<>();

  // Filtering
  private IFilter filter;
  private IFilterContext filterContext;
  private HashSet<AbstractInsnNode> ignored = new HashSet<>();
  private Map<AbstractInsnNode, AbstractInsnNode> unioned = new HashMap<>();
  private Map<AbstractInsnNode, Set<AbstractInsnNode>> branchReplacements = new HashMap<>();

  // Result
  private Map<Integer, BranchExp> lineToBranchExp = new TreeMap();
  public Map<Integer, BranchExp> result() {
    return lineToBranchExp;
  }

  // Intermediate results
  //
  // These values are built up during the visitor methods. They will be used to compute
  // the final results.
  private List<Instruction> instructions = new ArrayList<Instruction>();
  private List<Jump> jumps = new ArrayList<>();
  private final Map<Integer, Instruction> probeToInsn = new TreeMap<>();

  // The branch index a probe is associated with.
  private final Map<Integer, Integer> probeToBranchIdx = new HashMap<>();

  // A map which associates instructions with their coverage expressions.
  private final Map<Instruction, CovExp> insnToCovExp = new HashMap<>();

  // Associates a target to the branch index of the source instruction
  private final Map<Instruction, Integer> targetToBranchIndex = new HashMap<>();

  // Local cache
  //
  // These are maps corresponding to data structures available in JaCoCo in other form.
  // We use local data structure to avoid need to change the JaCoCo internal code.
  private Map<Instruction, Instruction> predecessors = new HashMap<>();
  private Map<Label, Instruction> labelToInsn = new HashMap<>();

  public MethodProbesMapper(IFilterContext filterContext, IFilter filter) {
    this.filterContext = filterContext;
    this.filter = filter;
  }

  @Override
  public void accept(MethodNode methodNode, MethodVisitor methodVisitor) {
    methodVisitor.visitCode();
    for (AbstractInsnNode i : methodNode.instructions) {
      currentInstructionNode = i;
      i.accept(methodVisitor);
    }
    filter.filter(methodNode, filterContext, this);
    methodVisitor.visitEnd();
  }

  /** Visitor method to append a new Instruction */
  private void visitInsn() {
    Instruction instruction = new Instruction(currentLine);
    instructions.add(instruction);
    if (lastInstruction != null) {
      lastInstruction.addBranch(instruction, 0); // the first branch from last instruction
      predecessors.put(instruction, lastInstruction); // Update local cache
      targetToBranchIndex.put(instruction, 0);
    }

    for (Label label : currentLabels) {
      labelToInsn.put(label, instruction);
    }
    currentLabels.clear(); // Update states
    lastInstruction = instruction;
    instructionMap.put(currentInstructionNode, instruction);
    instructionNodeIndexMap.put(currentInstructionNode, instructionNodeIndex);
    instructionNodeIndex++;
  }

  // Plain visitors: called from adapter when no probe is needed
  @Override
  public void visitInsn(int opcode) {
    visitInsn();
  }

  @Override
  public void visitIntInsn(int opcode, int operand) {
    visitInsn();
  }

  @Override
  public void visitVarInsn(int opcode, int variable) {
    visitInsn();
  }

  @Override
  public void visitTypeInsn(int opcode, String type) {
    visitInsn();
  }

  @Override
  public void visitFieldInsn(int opcode, String owner, String name, String desc) {
    visitInsn();
  }

  @Override
  public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
    visitInsn();
  }

  @Override
  public void visitInvokeDynamicInsn(String name, String desc, Handle handle, Object... args) {
    visitInsn();
  }

  @Override
  public void visitLdcInsn(Object cst) {
    visitInsn();
  }

  @Override
  public void visitIincInsn(int var, int inc) {
    visitInsn();
  }

  @Override
  public void visitMultiANewArrayInsn(String desc, int dims) {
    visitInsn();
  }

  // Methods that need to update the states
  @Override
  public void visitJumpInsn(int opcode, Label label) {
    visitInsn();
    jumps.add(new Jump(lastInstruction, label, 1));
  }

  @Override
  public void visitLabel(Label label) {
    currentLabels.add(label);
    if (!LabelInfo.isSuccessor(label)) {
      lastInstruction = null;
    }
  }

  @Override
  public void visitLineNumber(int line, Label start) {
    currentLine = line;
  }

  /** Visit a switch instruction with no probes */
  private void visitSwitchInsn(Label dflt, Label[] labels) {
    visitInsn();

    // Handle default transition
    LabelInfo.resetDone(dflt);
    int branch = 0;
    jumps.add(new Jump(lastInstruction, dflt, branch));
    LabelInfo.setDone(dflt);

    // Handle other transitions
    LabelInfo.resetDone(labels);
    for (Label label : labels) {
      if (!LabelInfo.isDone(label)) {
        branch++;
        jumps.add(new Jump(lastInstruction, label, branch));
        LabelInfo.setDone(label);
      }
    }
  }

  @Override
  public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
    visitSwitchInsn(dflt, labels);
  }

  @Override
  public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
    visitSwitchInsn(dflt, labels);
  }

  private void addProbe(int probeId, int branchIdx) {
    // We do not add probes to the flow graph, but we need to update
    // the branch count of the predecessor of the probe
    lastInstruction.addBranch(false, branchIdx);
    probeToInsn.put(probeId, lastInstruction);
    probeToBranchIdx.put(probeId, branchIdx);
  }

  // Probe visit methods
  @Override
  public void visitProbe(int probeId) {
    // This function is only called when visiting a merge node which
    // is a successor.
    // It adds a probe point to the last instruction
    assert (lastInstruction != null);

    addProbe(probeId, 0);
    lastInstruction = null; // Merge point should have no predecessor.
  }

  @Override
  public void visitJumpInsnWithProbe(int opcode, Label label, int probeId, IFrame frame) {
    visitInsn();
    addProbe(probeId, 1);
  }

  @Override
  public void visitInsnWithProbe(int opcode, int probeId) {
    visitInsn();
    addProbe(probeId, 0);
  }

  @Override
  public void visitTableSwitchInsnWithProbes(
      int min, int max, Label dflt, Label[] labels, IFrame frame) {
    visitSwitchInsnWithProbes(dflt, labels);
  }

  @Override
  public void visitLookupSwitchInsnWithProbes(
      Label dflt, int[] keys, Label[] labels, IFrame frame) {
    visitSwitchInsnWithProbes(dflt, labels);
  }

  private void visitSwitchInsnWithProbes(Label dflt, Label[] labels) {
    visitInsn();
    LabelInfo.resetDone(dflt);
    LabelInfo.resetDone(labels);
    int branch = 0;
    visitTargetWithProbe(dflt, branch);
    for (Label l : labels) {
      branch++;
      visitTargetWithProbe(l, branch);
    }
  }

  private void visitTargetWithProbe(Label label, int branch) {
    if (!LabelInfo.isDone(label)) {
      int id = LabelInfo.getProbeId(label);
      if (id == LabelInfo.NO_PROBE) {
        jumps.add(new Jump(lastInstruction, label, branch));
      } else {
        // Note, in this case the instrumenter should insert intermediate labels
        // for the probes. These probes will be added for the switch instruction.
        //
        // There is no direct jump between lastInstruction and the label either.
        addProbe(id, branch);
      }
      LabelInfo.setDone(label);
    }
  }

  /** Finishing the method */
  @Override
  public void visitEnd() {
    for (Jump jump : jumps) {
      Instruction insn = labelToInsn.get(jump.target);
      jump.source.addBranch(insn, jump.branch);
      predecessors.put(insn, jump.source);
      targetToBranchIndex.put(insn, jump.branch);
    }

    for (Map.Entry<Integer, Instruction> entry : probeToInsn.entrySet()) {
      int probeId = entry.getKey();
      Instruction insn = entry.getValue();

      int branches = insn.getBranchCounter().getTotalCount();
      if (branches <= 1) {
        // If the instruction already has a cov-exp associated with it then the internal data is in
        // an inconsistent state: the instruction is a predecessor of another and has a probe, but
        // is not a branch.
        // Point is, it should be sound to just blindly write this to the coverage expression map.
        insnToCovExp.put(insn, new ProbeExp(probeId));
      } else {
        // we only ever add BranchExp to the map for Instructions with more than one branch
        BranchExp branchExp = (BranchExp) insnToCovExp.get(insn);
        if (branchExp == null) {
          branchExp = BranchExp.initializeEmptyBranches(branches);
          insnToCovExp.put(insn, branchExp);
        }
        branchExp.setBranchAtIndex(probeToBranchIdx.get(probeId), new ProbeExp(probeId));
      }

      CovExp exp = insnToCovExp.get(insn);
      Instruction predecessor = predecessors.get(insn);
      while (predecessor != null) {
        int predBranches = predecessor.getBranchCounter().getTotalCount();
        if (predBranches > 1) {
          // does the predecessor have a branch expression already?
          BranchExp branchExp = (BranchExp) insnToCovExp.get(predecessor);
          int branchIdx = targetToBranchIndex.get(insn);
          boolean foundBranch = branchExp != null;
          if (foundBranch) {
            // If we have a branch here that means the predecessor chain has already been set up.
            // Just update the index at the current branch and end the walk
            branchExp.setBranchAtIndex(branchIdx, exp);
            break;
          } else {
            branchExp = BranchExp.initializeEmptyBranches(predBranches);
            branchExp.setBranchAtIndex(branchIdx, exp);
            insnToCovExp.put(predecessor, branchExp);
          }
        } else {
          insnToCovExp.put(predecessor, exp);
        }
        exp = insnToCovExp.get(predecessor);
        insn = predecessor;
        predecessor = predecessors.get(insn);
      }
    }

    // Handle merged instructions
    for (AbstractInsnNode node : unioned.keySet()) {
      AbstractInsnNode rep = findRepresentative(node);
      Instruction insn = instructionMap.get(node);
      Instruction repInsn = instructionMap.get(rep);
      BranchExp branch = BranchExp.ensureIsBranchExp(insnToCovExp.get(insn));
      BranchExp repBranch = BranchExp.ensureIsBranchExp(insnToCovExp.get(repInsn));
      insnToCovExp.put(repInsn, BranchExp.zip(repBranch, branch));
      ignored.add(node);
    }

    // Handle branch replacements
    for (Map.Entry<AbstractInsnNode, Set<AbstractInsnNode>> entry : branchReplacements.entrySet()) {
      // The replacement set is not ordered deterministically and we require it to be so to be able
      // to merge multiple coverage reports later on. We use the order in which we encountered
      // nodes to determine the order of branches for the new BranchExp.
      ArrayList<AbstractInsnNode> replacements = new ArrayList<>(entry.getValue());
      replacements.sort(comparing(instructionNodeIndexMap::get));
      BranchExp newBranch = new BranchExp(new ArrayList<>());
      for (AbstractInsnNode replacement : replacements) {
        newBranch.add(insnToCovExp.get(instructionMap.get(replacement)));
      }
      insnToCovExp.put(instructionMap.get(entry.getKey()), newBranch);
    }

    HashSet<Instruction> ignoredInstructions = new HashSet<>();
    for (Map.Entry<AbstractInsnNode, Instruction> entry : instructionMap.entrySet()) {
      if (ignored.contains(entry.getKey())) {
        ignoredInstructions.add(entry.getValue());
      }
    }

    // Merge branches in the instructions on the same line
    for (Instruction insn : instructions) {
      if (ignoredInstructions.contains(insn)) {
        continue;
      }
      if (insn.getBranchCounter().getTotalCount() > 1) {
        CovExp insnExp = insnToCovExp.get(insn);
        if (insnExp != null && (insnExp instanceof BranchExp)) {
          BranchExp exp = (BranchExp) insnExp;
          BranchExp lineExp = lineToBranchExp.get(insn.getLine());
          if (lineExp == null) {
            lineToBranchExp.put(insn.getLine(), exp);
          } else {
            lineToBranchExp.put(insn.getLine(), BranchExp.concatenate(lineExp, exp));
          }
        } else {
          // If we reach here, the internal data of the mapping is inconsistent, either
          // 1) An instruction has branches but we do not create BranchExp for it.
          // 2) An instruction has branches but it does not have an associated CovExp.
        }
      }
    }
  }

  /** IFilterOutput */
  // Handle only ignore for now; most filters only use this.
  @Override
  public void ignore(AbstractInsnNode fromInclusive, AbstractInsnNode toInclusive) {
    for (AbstractInsnNode n = fromInclusive; n != toInclusive; n = n.getNext()) {
      ignored.add(n);
    }
    ignored.add(toInclusive);
  }

  @Override
  public void merge(AbstractInsnNode i1, AbstractInsnNode i2) {
    // Track nodes to be merged using a union-find algorithm.
    i1 = findRepresentative(i1);
    i2 = findRepresentative(i2);
    if (i1 != i2) {
      unioned.put(i1, i2);
    }
  }

  @Override
  public void replaceBranches(AbstractInsnNode source, Set<AbstractInsnNode> newTargets) {
    branchReplacements.put(source, newTargets);
  }

  private AbstractInsnNode findRepresentative(AbstractInsnNode node) {
    // The "find" part of union-find. Walk the chain of nodes to find the representative node
    // (at the root), flattening the tree a little as we go.
    AbstractInsnNode parent;
    AbstractInsnNode grandParent;
    while ((parent = unioned.get(node)) != null) {
      if ((grandParent = unioned.get(parent)) != null) {
        unioned.put(node, grandParent);
      }
      node = parent;
    }
    return node;
  }

  /** Jumps between instructions and labels */
  class Jump {
    public final Instruction source;
    public final Label target;
    public final int branch;

    public Jump(Instruction i, Label l, int b) {
      source = i;
      target = l;
      branch = b;
    }
  }
}
