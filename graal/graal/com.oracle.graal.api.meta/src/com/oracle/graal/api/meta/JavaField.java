/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.api.meta;

/**
 * Represents a reference to a Java field, either resolved or unresolved fields. Fields, like
 * methods and types, are resolved through {@link ConstantPool constant pools}.
 */
public interface JavaField {

    /**
     * Returns the name of this field.
     */
    String getName();

    /**
     * Returns a {@link JavaType} object that identifies the declared type for this field.
     */
    JavaType getType();

    /**
     * Returns the kind of this field. This is the same as calling {@link #getType}.
     * {@link JavaType#getKind getKind}.
     */
    Kind getKind();

    /**
     * Returns the {@link JavaType} object representing the class or interface that declares this
     * field.
     */
    JavaType getDeclaringClass();
}
