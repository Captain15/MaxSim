/*
 * Copyright 1997-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *
 */

# include "incls/_precompiled.incl"
# include "incls/_stackValue.cpp.incl"

StackValue* StackValue::create_stack_value(const frame* fr, const RegisterMap* reg_map, ScopeValue* sv) {
  if (sv->is_location()) {
    // Stack or register value
    Location loc = ((LocationValue *)sv)->location();

#ifdef SPARC
    // %%%%% Callee-save floats will NOT be working on a Sparc until we
    // handle the case of a 2 floats in a single double register.
    assert( !(loc.is_register() && loc.type() == Location::float_in_dbl), "Sparc does not handle callee-save floats yet" );
#endif // SPARC

    // First find address of value

    address value_addr = loc.is_register()
      // Value was in a callee-save register
      ? reg_map->location(VMRegImpl::as_VMReg(loc.register_number()))
      // Else value was directly saved on the stack. The frame's original stack pointer,
      // before any extension by its callee (due to Compiler1 linkage on SPARC), must be used.
      : ((address)fr->unextended_sp()) + loc.stack_offset();

    // Then package it right depending on type
    // Note: the transfer of the data is thru a union that contains
    // an intptr_t. This is because an interpreter stack slot is
    // really an intptr_t. The use of a union containing an intptr_t
    // ensures that on a 64 bit platform we have proper alignment
    // and that we store the value where the interpreter will expect
    // to find it (i.e. proper endian). Similarly on a 32bit platform
    // using the intptr_t ensures that when a value is larger than
    // a stack slot (jlong/jdouble) that we capture the proper part
    // of the value for the stack slot in question.
    //
    switch( loc.type() ) {
    case Location::float_in_dbl: { // Holds a float in a double register?
      // The callee has no clue whether the register holds a float,
      // double or is unused.  He always saves a double.  Here we know
      // a double was saved, but we only want a float back.  Narrow the
      // saved double to the float that the JVM wants.
      assert( loc.is_register(), "floats always saved to stack in 1 word" );
      union { intptr_t p; jfloat jf; } value;
      value.p = (intptr_t) CONST64(0xDEADDEAFDEADDEAF);
      value.jf = (jfloat) *(jdouble*) value_addr;
      return new StackValue(value.p); // 64-bit high half is stack junk
    }
    case Location::int_in_long: { // Holds an int in a long register?
      // The callee has no clue whether the register holds an int,
      // long or is unused.  He always saves a long.  Here we know
      // a long was saved, but we only want an int back.  Narrow the
      // saved long to the int that the JVM wants.
      assert( loc.is_register(), "ints always saved to stack in 1 word" );
      union { intptr_t p; jint ji;} value;
      value.p = (intptr_t) CONST64(0xDEADDEAFDEADDEAF);
      value.ji = (jint) *(jlong*) value_addr;
      return new StackValue(value.p); // 64-bit high half is stack junk
    }
#ifdef _LP64
    case Location::dbl:
      // Double value in an aligned adjacent pair
      return new StackValue(*(intptr_t*)value_addr);
    case Location::lng:
      // Long   value in an aligned adjacent pair
      return new StackValue(*(intptr_t*)value_addr);
#endif
    case Location::oop: {
      Handle h(*(oop *)value_addr); // Wrap a handle around the oop
      return new StackValue(h);
    }
    case Location::addr: {
      ShouldNotReachHere(); // both C1 and C2 now inline jsrs
    }
    case Location::normal: {
      // Just copy all other bits straight through
      union { intptr_t p; jint ji;} value;
      value.p = (intptr_t) CONST64(0xDEADDEAFDEADDEAF);
      value.ji = *(jint*)value_addr;
      return new StackValue(value.p);
    }
    case Location::invalid:
      return new StackValue();
    default:
      ShouldNotReachHere();
    }

  } else if (sv->is_constant_int()) {
    // Constant int: treat same as register int.
    union { intptr_t p; jint ji;} value;
    value.p = (intptr_t) CONST64(0xDEADDEAFDEADDEAF);
    value.ji = (jint)((ConstantIntValue*)sv)->value();
    return new StackValue(value.p);
  } else if (sv->is_constant_oop()) {
    // constant oop
    return new StackValue(((ConstantOopReadValue *)sv)->value());
#ifdef _LP64
  } else if (sv->is_constant_double()) {
    // Constant double in a single stack slot
    union { intptr_t p; double d; } value;
    value.p = (intptr_t) CONST64(0xDEADDEAFDEADDEAF);
    value.d = ((ConstantDoubleValue *)sv)->value();
    return new StackValue(value.p);
  } else if (sv->is_constant_long()) {
    // Constant long in a single stack slot
    union { intptr_t p; jlong jl; } value;
    value.p = (intptr_t) CONST64(0xDEADDEAFDEADDEAF);
    value.jl = ((ConstantLongValue *)sv)->value();
    return new StackValue(value.p);
#endif
  } else if (sv->is_object()) {
    return new StackValue(((ObjectValue *)sv)->value());
  }

  // Unknown ScopeValue type
  ShouldNotReachHere();
  return new StackValue((intptr_t) 0);   // dummy
}


BasicLock* StackValue::resolve_monitor_lock(const frame* fr, Location location) {
  assert(location.is_stack(), "for now we only look at the stack");
  int word_offset = location.stack_offset() / wordSize;
  // (stack picture)
  // high: [     ]  word_offset + 1
  // low   [     ]  word_offset
  //
  // sp->  [     ]  0
  // the word_offset is the distance from the stack pointer to the lowest address
  // The frame's original stack pointer, before any extension by its callee
  // (due to Compiler1 linkage on SPARC), must be used.
  return (BasicLock*) (fr->unextended_sp() + word_offset);
}


#ifndef PRODUCT

void StackValue::print_on(outputStream* st) const {
  switch(_type) {
    case T_INT:
      st->print("%d (int) %f (float) %x (hex)",  *(int *)&_i, *(float *)&_i,  *(int *)&_i);
      break;

    case T_OBJECT:
     _o()->print_value_on(st);
      st->print(" <" INTPTR_FORMAT ">", (address)_o());
     break;

    case T_CONFLICT:
     st->print("conflict");
     break;

    default:
     ShouldNotReachHere();
  }
}

#endif
