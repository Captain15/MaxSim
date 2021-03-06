/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.nodes.util;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;

public class GraphUtil {

    private static final NodePredicate FLOATING = new NodePredicate() {

        @Override
        public final boolean apply(Node n) {
            // isA(FloatingNode.class).or(VirtualState.class).or(CallTargetNode.class)
            return n instanceof FloatingNode || n instanceof VirtualState || n instanceof CallTargetNode;
        }
    };

    public static void killCFG(FixedNode node) {
        assert node.isAlive();
        if (node instanceof AbstractEndNode) {
            // We reached a control flow end.
            AbstractEndNode end = (AbstractEndNode) node;
            killEnd(end);
        } else {
            // Normal control flow node.
            /*
             * We do not take a successor snapshot because this iterator supports concurrent
             * modifications as long as they do not change the size of the successor list. Not
             * taking a snapshot allows us to see modifications to other branches that may happen
             * while processing one branch.
             */
            for (Node successor : node.successors()) {
                killCFG((FixedNode) successor);
            }
        }
        propagateKill(node);
    }

    private static void killEnd(AbstractEndNode end) {
        MergeNode merge = end.merge();
        if (merge != null) {
            merge.removeEnd(end);
            StructuredGraph graph = end.graph();
            if (merge instanceof LoopBeginNode && merge.forwardEndCount() == 0) { // dead loop
                for (PhiNode phi : merge.phis().snapshot()) {
                    propagateKill(phi);
                }
                LoopBeginNode begin = (LoopBeginNode) merge;
                // disconnect and delete loop ends & loop exits
                for (LoopEndNode loopend : begin.loopEnds().snapshot()) {
                    loopend.predecessor().replaceFirstSuccessor(loopend, null);
                    loopend.safeDelete();
                }
                begin.removeExits();
                FixedNode loopBody = begin.next();
                if (loopBody != null) { // for small infinite loops, the body may be killed while
                                        // killing the loop ends
                    killCFG(loopBody);
                }
                begin.safeDelete();
            } else if (merge instanceof LoopBeginNode && ((LoopBeginNode) merge).loopEnds().isEmpty()) { // not
                                                                                                         // a
                                                                                                         // loop
                                                                                                         // anymore
                graph.reduceDegenerateLoopBegin((LoopBeginNode) merge);
            } else if (merge.phiPredecessorCount() == 1) { // not a merge anymore
                graph.reduceTrivialMerge(merge);
            }
        }
    }

    public static NodePredicate isFloatingNode() {
        return FLOATING;
    }

    public static void propagateKill(Node node) {
        if (node != null && node.isAlive()) {
            List<Node> usagesSnapshot = node.usages().filter(isFloatingNode()).snapshot();

            // null out remaining usages
            node.replaceAtUsages(null);
            node.replaceAtPredecessor(null);
            killWithUnusedFloatingInputs(node);

            for (Node usage : usagesSnapshot) {
                if (!usage.isDeleted()) {
                    if (usage instanceof PhiNode) {
                        usage.replaceFirstInput(node, null);
                    } else {
                        propagateKill(usage);
                    }
                }
            }
        }
    }

    public static void killWithUnusedFloatingInputs(Node node) {
        List<Node> floatingInputs = node.inputs().filter(isFloatingNode()).snapshot();
        node.safeDelete();

        for (Node in : floatingInputs) {
            if (in.isAlive() && in.usages().isEmpty()) {
                killWithUnusedFloatingInputs(in);
            }
        }
    }

    public static void checkRedundantPhi(PhiNode phiNode) {
        if (phiNode.isDeleted() || phiNode.valueCount() == 1) {
            return;
        }

        ValueNode singleValue = phiNode.singleValue();
        if (singleValue != null) {
            Collection<PhiNode> phiUsages = phiNode.usages().filter(PhiNode.class).snapshot();
            Collection<ProxyNode> proxyUsages = phiNode.usages().filter(ProxyNode.class).snapshot();
            phiNode.graph().replaceFloating(phiNode, singleValue);
            for (PhiNode phi : phiUsages) {
                checkRedundantPhi(phi);
            }
            for (ProxyNode proxy : proxyUsages) {
                checkRedundantProxy(proxy);
            }
        }
    }

    public static void checkRedundantProxy(ProxyNode vpn) {
        AbstractBeginNode proxyPoint = vpn.proxyPoint();
        if (proxyPoint instanceof LoopExitNode) {
            LoopExitNode exit = (LoopExitNode) proxyPoint;
            LoopBeginNode loopBegin = exit.loopBegin();
            ValueNode vpnValue = vpn.value();
            for (ValueNode v : loopBegin.stateAfter().values()) {
                ValueNode v2 = v;
                if (loopBegin.isPhiAtMerge(v2)) {
                    v2 = ((PhiNode) v2).valueAt(loopBegin.forwardEnd());
                }
                if (vpnValue == v2) {
                    Collection<PhiNode> phiUsages = vpn.usages().filter(PhiNode.class).snapshot();
                    Collection<ProxyNode> proxyUsages = vpn.usages().filter(ProxyNode.class).snapshot();
                    vpn.graph().replaceFloating(vpn, vpnValue);
                    for (PhiNode phi : phiUsages) {
                        checkRedundantPhi(phi);
                    }
                    for (ProxyNode proxy : proxyUsages) {
                        checkRedundantProxy(proxy);
                    }
                    return;
                }
            }
        }
    }

    public static void normalizeLoopBegin(LoopBeginNode begin) {
        // Delete unnecessary loop phi functions, i.e., phi functions where all inputs are either
        // the same or the phi itself.
        for (PhiNode phi : begin.phis().snapshot()) {
            GraphUtil.checkRedundantPhi(phi);
        }
        for (LoopExitNode exit : begin.loopExits()) {
            for (ProxyNode vpn : exit.proxies().snapshot()) {
                GraphUtil.checkRedundantProxy(vpn);
            }
        }
    }

    /**
     * Gets an approximate source code location for a node if possible.
     * 
     * @return the StackTraceElements if an approximate source location is found, null otherwise
     */
    public static StackTraceElement[] approxSourceStackTraceElement(Node node) {
        ArrayList<StackTraceElement> elements = new ArrayList<>();
        Node n = node;
        while (n != null) {
            if (n instanceof MethodCallTargetNode) {
                elements.add(((MethodCallTargetNode) n).targetMethod().asStackTraceElement(-1));
                n = ((MethodCallTargetNode) n).invoke().asNode();
            }

            if (n instanceof StateSplit) {
                FrameState state = ((StateSplit) n).stateAfter();
                while (state != null) {
                    ResolvedJavaMethod method = state.method();
                    if (method != null) {
                        elements.add(method.asStackTraceElement(state.bci - 1));
                    }
                    state = state.outerFrameState();
                }
                break;
            }
            n = n.predecessor();
        }
        return elements.toArray(new StackTraceElement[elements.size()]);
    }

    /**
     * Gets an approximate source code location for a node, encoded as an exception, if possible.
     * 
     * @return the exception with the location
     */
    public static RuntimeException approxSourceException(Node node, Throwable cause) {
        final StackTraceElement[] elements = approxSourceStackTraceElement(node);
        @SuppressWarnings("serial")
        RuntimeException exception = new RuntimeException((cause == null) ? null : cause.getMessage(), cause) {

            @Override
            public final synchronized Throwable fillInStackTrace() {
                setStackTrace(elements);
                return this;
            }
        };
        return exception;
    }

    /**
     * Gets an approximate source code location for a node if possible.
     * 
     * @return a file name and source line number in stack trace format (e.g. "String.java:32") if
     *         an approximate source location is found, null otherwise
     */
    public static String approxSourceLocation(Node node) {
        StackTraceElement[] stackTraceElements = approxSourceStackTraceElement(node);
        if (stackTraceElements != null && stackTraceElements.length > 0) {
            StackTraceElement top = stackTraceElements[0];
            if (top.getFileName() != null && top.getLineNumber() >= 0) {
                return top.getFileName() + ":" + top.getLineNumber();
            }
        }
        return null;
    }

    /**
     * Returns a string representation of the given collection of objects.
     * 
     * @param objects The {@link Iterable} that will be used to iterate over the objects.
     * @return A string of the format "[a, b, ...]".
     */
    public static String toString(Iterable<?> objects) {
        StringBuilder str = new StringBuilder();
        str.append("[");
        for (Object o : objects) {
            str.append(o).append(", ");
        }
        if (str.length() > 1) {
            str.setLength(str.length() - 2);
        }
        str.append("]");
        return str.toString();
    }

    /**
     * Gets the original value by iterating through all {@link ValueProxy ValueProxies}.
     * 
     * @param value The start value.
     * @return The first non-proxy value encountered.
     */
    public static ValueNode unproxify(ValueNode value) {
        ValueNode result = value;
        while (result instanceof ValueProxy) {
            result = ((ValueProxy) result).getOriginalValue();
        }
        return result;
    }

    /**
     * Tries to find an original value of the given node by traversing through proxies and
     * unambiguous phis. Note that this method will perform an exhaustive search through phis. It is
     * intended to be used during graph building, when phi nodes aren't yet canonicalized.
     * 
     * @param proxy The node whose original value should be determined.
     */
    public static ValueNode originalValue(ValueNode proxy) {
        ValueNode v = proxy;
        do {
            if (v instanceof ValueProxy) {
                v = ((ValueProxy) v).getOriginalValue();
            } else if (v instanceof PhiNode) {
                v = ((PhiNode) v).singleValue();
            } else {
                break;
            }
        } while (v != null);

        // if the simple check fails (this can happen for complicated phi/proxy/phi constructs), we
        // do an exhaustive search
        if (v == null) {
            NodeWorkList worklist = proxy.graph().createNodeWorkList();
            worklist.add(proxy);
            for (Node node : worklist) {
                if (node instanceof ValueProxy) {
                    worklist.add(((ValueProxy) node).getOriginalValue());
                } else if (node instanceof PhiNode) {
                    worklist.addAll(((PhiNode) node).values());
                } else {
                    if (v == null) {
                        v = (ValueNode) node;
                    } else {
                        return null;
                    }
                }
            }
        }
        return v;
    }

}
