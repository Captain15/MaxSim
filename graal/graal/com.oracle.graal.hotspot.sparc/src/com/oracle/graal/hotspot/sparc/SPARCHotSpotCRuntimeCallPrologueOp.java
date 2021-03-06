/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.sparc;

import static com.oracle.graal.sparc.SPARC.*;
import static com.oracle.graal.asm.sparc.SPARCMacroAssembler.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.asm.sparc.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.sparc.*;
import com.oracle.graal.lir.asm.*;

@Opcode("CRUNTIME_CALL_PROLOGUE")
final class SPARCHotSpotCRuntimeCallPrologueOp extends SPARCLIRInstruction {

    @Override
    public void emitCode(TargetMethodAssembler tasm, SPARCMacroAssembler masm) {
        HotSpotRuntime runtime = graalRuntime().getRuntime();
        HotSpotVMConfig config = graalRuntime().getConfig();
        Register thread = runtime.threadRegister();
        Register stackPointer = runtime.stackPointerRegister();

        // Save last Java frame.
        new Add(stackPointer, new SPARCAddress(stackPointer, 0).getDisplacement(), g4).emit(masm);
        new Stx(g4, new SPARCAddress(thread, config.threadLastJavaSpOffset)).emit(masm);

        // Save the thread register when calling out to the runtime.
        new Mov(thread, l7).emit(masm);
    }
}
