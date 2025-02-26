/*
 * Copyright (c) 2014, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.dsl.processor.generator;

import static com.oracle.truffle.dsl.processor.java.ElementUtils.fromTypeMirror;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.TruffleTypes;
import com.oracle.truffle.dsl.processor.java.ElementUtils;
import com.oracle.truffle.dsl.processor.java.model.CodeAnnotationMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeAnnotationValue;
import com.oracle.truffle.dsl.processor.java.model.CodeElement;
import com.oracle.truffle.dsl.processor.java.model.CodeExecutableElement;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeElement;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;
import com.oracle.truffle.dsl.processor.java.model.GeneratedElement;
import com.oracle.truffle.dsl.processor.java.model.GeneratedTypeMirror;
import com.oracle.truffle.dsl.processor.model.Template;
import com.oracle.truffle.dsl.processor.model.TemplateMethod;

public class GeneratorUtils {

    public static void pushEncapsulatingNode(CodeTreeBuilder builder, CodeTree nodeRef) {
        TruffleTypes types = ProcessorContext.getInstance().getTypes();
        builder.startStatement().type(types.EncapsulatingNodeReference).string(" encapsulating_ = ").//
                        startStaticCall(types.EncapsulatingNodeReference, "getCurrent").end().end();

        builder.startStatement().type(types.Node).string(" prev_ = encapsulating_.");
        builder.startCall("set").tree(nodeRef).end();
        builder.end();
    }

    public static void popEncapsulatingNode(CodeTreeBuilder builder) {
        builder.startStatement().string("encapsulating_.set(prev_)").end();
    }

    public static CodeTree createTransferToInterpreterAndInvalidate() {
        ProcessorContext context = ProcessorContext.getInstance();
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.startStatement().startStaticCall(context.getTypes().CompilerDirectives, "transferToInterpreterAndInvalidate").end().end();
        return builder.build();
    }

    public static CodeTree createNeverPartOfCompilation() {
        ProcessorContext context = ProcessorContext.getInstance();
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.startStatement().startStaticCall(context.getTypes().CompilerAsserts, "neverPartOfCompilation").end(2);
        return builder.build();
    }

    public static CodeTree createShouldNotReachHere() {
        ProcessorContext context = ProcessorContext.getInstance();
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.startThrow().startStaticCall(context.getTypes().CompilerDirectives, "shouldNotReachHere").end().end();
        return builder.build();
    }

    public static CodeTree createShouldNotReachHere(String message) {
        ProcessorContext context = ProcessorContext.getInstance();
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.startThrow().startStaticCall(context.getTypes().CompilerDirectives, "shouldNotReachHere").doubleQuote(message).end().end();
        return builder.build();
    }

    public static CodeTree createShouldNotReachHere(CodeTree causeExpression) {
        ProcessorContext context = ProcessorContext.getInstance();
        CodeTreeBuilder builder = CodeTreeBuilder.createBuilder();
        builder.startThrow().startStaticCall(context.getTypes().CompilerDirectives, "shouldNotReachHere").tree(causeExpression).end().end();
        return builder.build();
    }

    public static CodeExecutableElement createConstructorUsingFields(Set<Modifier> modifiers, CodeTypeElement clazz) {
        ExecutableElement superConstructor = null;
        if (clazz.getSuperclass() != null) {
            TypeElement superClass = fromTypeMirror(clazz.getSuperclass());
            superConstructor = findConstructor(superClass);
        }
        return createConstructorUsingFields(modifiers, clazz, superConstructor);
    }

    public static void addBoundaryOrTransferToInterpreter(CodeExecutableElement method, CodeTreeBuilder builder) {
        if (builder != null && method != builder.findMethod()) {
            throw new AssertionError("Expected " + method + " but was " + builder.findMethod());
        }
        TruffleTypes types = ProcessorContext.getInstance().getTypes();
        if (ElementUtils.findAnnotationMirror(method, types.CompilerDirectives_TruffleBoundary) != null) {
            // already a boundary. nothing to do.
            return;
        }
        boolean hasFrame = false;
        for (VariableElement var : method.getParameters()) {
            if (ElementUtils.typeEquals(var.asType(), types.VirtualFrame) || ElementUtils.typeEquals(var.asType(), types.Frame)) {
                hasFrame = true;
                break;
            }
        }
        if (hasFrame) {
            if (builder != null) {
                builder.tree(GeneratorUtils.createTransferToInterpreterAndInvalidate());
            }
        } else {
            method.addAnnotationMirror(new CodeAnnotationMirror(types.CompilerDirectives_TruffleBoundary));
        }
    }

    public static void addOverride(CodeExecutableElement method) {
        DeclaredType override = ProcessorContext.getInstance().getDeclaredType(Override.class);
        if (ElementUtils.findAnnotationMirror(method, override) != null) {
            return;
        }
        method.addAnnotationMirror(new CodeAnnotationMirror(override));
    }

    public static void markUnsafeAccessed(CodeElement<?> element) {
        DeclaredType unsafeAccessed = ProcessorContext.types().InlineSupport_UnsafeAccessedField;
        if (ElementUtils.findAnnotationMirror(element, unsafeAccessed) == null) {
            element.getAnnotationMirrors().add(new CodeAnnotationMirror(unsafeAccessed));
        }
    }

    public static void mergeSuppressWarnings(CodeElement<?> element, String... addWarnings) {
        List<String> mergedWarnings = Arrays.asList(addWarnings);
        AnnotationMirror currentWarnings = ElementUtils.findAnnotationMirror(element, SuppressWarnings.class);
        if (currentWarnings != null) {
            List<String> currentValues = ElementUtils.getAnnotationValueList(String.class, currentWarnings, "value");
            if (currentValues != null && !currentValues.isEmpty()) {
                Set<String> warnings = new LinkedHashSet<>(mergedWarnings);
                warnings.addAll(currentValues);
                mergedWarnings = warnings.stream().collect(Collectors.toList());
            }
        }
        DeclaredType suppressWarnings = ProcessorContext.getInstance().getDeclaredType(SuppressWarnings.class);
        CodeAnnotationMirror mirror = new CodeAnnotationMirror(suppressWarnings);
        List<AnnotationValue> values = new ArrayList<>();
        for (String warning : mergedWarnings) {
            values.add(new CodeAnnotationValue(warning));
        }
        mirror.setElementValue(mirror.findExecutableElement("value"), new CodeAnnotationValue(values));

        if (currentWarnings != null) {
            ((CodeElement<?>) element).getAnnotationMirrors().remove(currentWarnings);
        }
        if (!mergedWarnings.isEmpty()) {
            ((CodeElement<?>) element).getAnnotationMirrors().add(mirror);
        }
    }

    public static CodeExecutableElement createConstructorUsingFields(Set<Modifier> modifiers, CodeTypeElement clazz, ExecutableElement superConstructor) {
        return createConstructorUsingFields(modifiers, clazz, superConstructor, Collections.emptySet());
    }

    public static CodeExecutableElement createConstructorUsingFields(Set<Modifier> modifiers, CodeTypeElement clazz, ExecutableElement superConstructor,
                    Set<String> ignoreFields) {
        CodeExecutableElement method = new CodeExecutableElement(modifiers, null, clazz.getSimpleName().toString());
        CodeTreeBuilder builder = method.createBuilder();
        if (superConstructor != null && superConstructor.getParameters().size() > 0) {
            builder.startStatement();
            builder.startSuperCall();
            for (VariableElement parameter : superConstructor.getParameters()) {
                method.addParameter(CodeVariableElement.clone(parameter));
                builder.string(parameter.getSimpleName().toString());
            }
            builder.end(); // super
            builder.end(); // statement
        }

        for (VariableElement field : clazz.getFields()) {
            if (field.getModifiers().contains(STATIC)) {
                continue;
            }
            if (ignoreFields.contains(field.getSimpleName().toString())) {
                continue;
            }
            String fieldName = field.getSimpleName().toString();
            method.addParameter(new CodeVariableElement(field.asType(), fieldName));
            builder.startStatement();
            builder.string("this.");
            builder.string(fieldName);
            builder.string(" = ");
            builder.string(fieldName);
            builder.end(); // statement
        }

        return method;
    }

    public static CodeExecutableElement createCopyConstructorUsingFields(Set<Modifier> modifiers, CodeTypeElement clazz, Set<String> ignoreFields) {
        TypeElement superClass = fromTypeMirror(clazz.getSuperclass());
        ExecutableElement constructor = findConstructor(superClass);
        return createCopyConstructorUsingFields(modifiers, clazz, constructor, ignoreFields);
    }

    public static CodeExecutableElement createCopyConstructorUsingFields(Set<Modifier> modifiers, CodeTypeElement clazz, ExecutableElement superConstructor,
                    Set<String> ignoreFields) {
        CodeExecutableElement method = new CodeExecutableElement(modifiers, null, clazz.getSimpleName().toString());
        CodeTreeBuilder builder = method.createBuilder();
        if (superConstructor != null && superConstructor.getParameters().size() > 0) {
            builder.startStatement();
            builder.startSuperCall();
            for (VariableElement parameter : superConstructor.getParameters()) {
                method.addParameter(CodeVariableElement.clone(parameter));
                builder.string(parameter.getSimpleName().toString());
            }
            builder.end(); // super
            builder.end(); // statement
        }

        GeneratedTypeMirror typeMirror = new GeneratedTypeMirror("", clazz.getSimpleName().toString(), clazz.asType());
        method.addParameter(new CodeVariableElement(typeMirror, "delegate"));
        for (VariableElement field : clazz.getFields()) {
            if (field.getModifiers().contains(STATIC)) {
                continue;
            }
            if (ignoreFields.contains(field.getSimpleName().toString())) {
                continue;
            }
            String fieldName = field.getSimpleName().toString();
            builder.startStatement();
            builder.string("this.");
            builder.string(fieldName);
            builder.string(" = delegate.");
            builder.string(fieldName);
            builder.end(); // statement
        }

        return method;
    }

    private static ExecutableElement findConstructor(TypeElement clazz) {
        List<ExecutableElement> constructors = ElementFilter.constructorsIn(clazz.getEnclosedElements());
        if (constructors.isEmpty()) {
            return null;
        } else {
            return constructors.get(0);
        }
    }

    public static CodeTypeElement createClass(Template sourceModel, TemplateMethod sourceMethod, Set<Modifier> modifiers, String simpleName, TypeMirror superType) {
        TypeElement templateType = sourceModel.getTemplateType();
        return createClass(templateType, sourceMethod, modifiers, simpleName, superType);
    }

    public static CodeTypeElement createClass(TypeElement templateType, TemplateMethod sourceMethod, Set<Modifier> modifiers, String simpleName, TypeMirror superType) {
        ProcessorContext context = ProcessorContext.getInstance();

        PackageElement pack = ElementUtils.findPackageElement(templateType);
        CodeTypeElement clazz = new CodeTypeElement(modifiers, ElementKind.CLASS, pack, simpleName);
        TypeMirror resolvedSuperType = superType;
        if (resolvedSuperType == null) {
            resolvedSuperType = context.getType(Object.class);
        }
        clazz.setSuperClass(resolvedSuperType);

        CodeAnnotationMirror generatedByAnnotation = new CodeAnnotationMirror(context.getTypes().GeneratedBy);
        Element generatedByElement = templateType;
        while (generatedByElement instanceof GeneratedElement) {
            generatedByElement = generatedByElement.getEnclosingElement();
        }
        if (generatedByElement instanceof TypeElement) {
            generatedByAnnotation.setElementValue(generatedByAnnotation.findExecutableElement("value"), new CodeAnnotationValue(generatedByElement.asType()));
            if (sourceMethod != null) {
                generatedByAnnotation.setElementValue(generatedByAnnotation.findExecutableElement("methodName"), new CodeAnnotationValue(ElementUtils.createReferenceName(sourceMethod.getMethod())));
            }
            clazz.addAnnotationMirror(generatedByAnnotation);
        }
        return clazz;
    }

    public static void addGeneratedBy(ProcessorContext context, CodeTypeElement generatedType, TypeElement generatedByType) {
        DeclaredType generatedBy = context.getTypes().GeneratedBy;
        // only do this if generatedBy is on the classpath.
        if (generatedBy != null) {
            CodeAnnotationMirror generatedByAnnotation = new CodeAnnotationMirror(generatedBy);
            generatedByAnnotation.setElementValue(generatedByAnnotation.findExecutableElement("value"), new CodeAnnotationValue(generatedByType.asType()));
            generatedType.addAnnotationMirror(generatedByAnnotation);
        }
    }

    static List<ExecutableElement> findUserConstructors(TypeMirror nodeType) {
        List<ExecutableElement> constructors = new ArrayList<>();
        for (ExecutableElement constructor : ElementFilter.constructorsIn(ElementUtils.fromTypeMirror(nodeType).getEnclosedElements())) {
            if (constructor.getModifiers().contains(PRIVATE)) {
                continue;
            }
            if (isCopyConstructor(constructor)) {
                continue;
            }
            constructors.add(constructor);
        }

        if (constructors.isEmpty()) {
            constructors.add(new CodeExecutableElement(null, ElementUtils.getSimpleName(nodeType)));
        }

        return constructors;
    }

    static boolean isCopyConstructor(ExecutableElement element) {
        if (element.getParameters().size() != 1) {
            return false;
        }
        VariableElement var = element.getParameters().get(0);
        TypeElement enclosingType = ElementUtils.findNearestEnclosingType(var).orElseThrow(AssertionError::new);
        if (ElementUtils.typeEquals(var.asType(), enclosingType.asType())) {
            return true;
        }
        List<TypeElement> types = ElementUtils.getDirectSuperTypes(enclosingType);
        for (TypeElement type : types) {
            if (!(type instanceof CodeTypeElement)) {
                // no copy constructors which are not generated types
                return false;
            }

            if (ElementUtils.typeEquals(var.asType(), type.asType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generates an override of the given method defined on the given type that takes no parameters.
     */
    public static CodeExecutableElement override(DeclaredType type, String methodName) {
        return override(type, methodName, new String[0]);
    }

    /**
     * Generates an override of the given method defined on the given type. Updates the result's
     * parameters to have the given parameter names.
     */
    public static CodeExecutableElement override(DeclaredType type, String methodName, String[] parameterNames) {
        return override(type, methodName, parameterNames, new TypeMirror[parameterNames.length]);
    }

    /**
     * Generates an override of the given method defined on the given type. Updates the result's
     * parameters to have the given parameter names. Uses the given parameter types (which can be
     * {@code null}) to disambiguate overloads.
     * <p>
     * Callers must specify parameter names because the parent method may change its parameter
     * names. Additionally, if the parent {@code type} is loaded from a class file, the original
     * source parameter names are (usually) not available.
     */
    public static CodeExecutableElement override(DeclaredType type, String methodName, String[] parameterNames, TypeMirror[] parameterTypes) {
        if (parameterNames.length != parameterTypes.length) {
            throw new AssertionError(String.format("number of parameter names (%d) did not match number of parameter types (%d)", parameterNames.length, parameterTypes.length));
        }

        ExecutableElement method = ElementUtils.findInstanceMethod((TypeElement) type.asElement(), methodName, parameterTypes);
        if (method == null) {
            return null;
        }
        CodeExecutableElement result = override(method);
        result.renameArguments(parameterNames);
        return result;
    }

    /**
     * Generates an override of the given method.
     */
    public static CodeExecutableElement override(ExecutableElement method) {
        CodeExecutableElement result = CodeExecutableElement.clone(method);
        result.getModifiers().remove(Modifier.ABSTRACT);
        result.getModifiers().remove(Modifier.DEFAULT);
        addOverride(result);
        return result;
    }

    public static void addThrownExceptions(CodeExecutableElement executable, List<? extends TypeMirror> thrownTypes) {
        outer: for (TypeMirror thrownType : thrownTypes) {
            for (TypeMirror type : executable.getThrownTypes()) {
                if (ElementUtils.typeEquals(type, thrownType)) {
                    continue outer;
                }
            }
            executable.addThrownType(thrownType);
        }
    }

}
