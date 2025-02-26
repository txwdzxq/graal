/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.svm.core.graal.nodes;

import com.oracle.svm.core.hub.DynamicHub;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The {@link SubstrateNewDynamicHubNode} represents the allocation of a new
 * {@link com.oracle.svm.core.hub.DynamicHub} at execution time.
 */
@NodeInfo(nameTemplate = "SubstrateNewDynamicHubNode")
public final class SubstrateNewDynamicHubNode extends AbstractNewObjectNode {
    public static final NodeClass<SubstrateNewDynamicHubNode> TYPE = NodeClass.create(SubstrateNewDynamicHubNode.class);

    @Input ValueNode vTableEntries;

    public SubstrateNewDynamicHubNode(ResolvedJavaType dynamicHubType, ValueNode vTableEntries) {
        super(TYPE, StampFactory.objectNonNull(TypeReference.createExactTrusted(dynamicHubType)), true, null);
        this.vTableEntries = vTableEntries;
    }

    public ValueNode getVTableEntries() {
        return vTableEntries;
    }

    @NodeIntrinsic
    public static native DynamicHub allocate(@ConstantNodeParameter Class<?> dynamicHubType, int vTableEntries);
}
