/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.lookup;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;

class MethodVerifier15 extends MethodVerifier {

MethodVerifier15(LookupEnvironment environment) {
	super(environment);
}
boolean areTypesEqual(TypeBinding one, TypeBinding two) {
	return one == two || super.areTypesEqual(one.erasure(), two.erasure());
}
boolean areTypesEquivalent(TypeBinding one, TypeBinding two) {
	if (one == two) return true;
	if (!super.areTypesEqual(one.erasure(), two.erasure())) return false;

	if (one.isTypeVariable() && two.isTypeVariable()) {
		TypeVariableBinding temp1 = (TypeVariableBinding) one;
		TypeVariableBinding temp2 = (TypeVariableBinding) two;
		if (temp1.rank != temp2.rank) return false;
		if (!areTypesEquivalent(temp1.superclass, temp2.superclass)) return false;
		ReferenceBinding[] superInterfaces1 = temp1.superInterfaces;
		ReferenceBinding[] superInterfaces2 = temp2.superInterfaces;
		if (superInterfaces1 != superInterfaces2) {
			if (superInterfaces1 == null || superInterfaces2 == null) return false;
			if (superInterfaces1.length != superInterfaces2.length) return false;
			for (int i = 0, l = superInterfaces1.length; i < l; i++)
				if (!areTypesEquivalent(superInterfaces1[i], superInterfaces2[i])) return false;
		}
	}   
	return true;
}
void checkAgainstInheritedMethods(MethodBinding currentMethod, MethodBinding[] methods, int length) {
	// methods includes the inherited methods that the currentMethod must comply with
	// likely only 1 but could be more if mutiple declared supertypes define the method (1 superclass & 1 to many declared interfaces)
	nextMethod : for (int i = length; --i >= 0;) {
		MethodBinding inheritedMethod = methods[i];
		if (currentMethod.isStatic() != inheritedMethod.isStatic()) {  // Cannot override a static method or hide an instance method
			this.problemReporter(currentMethod).staticAndInstanceConflict(currentMethod, inheritedMethod);
			continue nextMethod;
		}

		// curentMethod is always resolved as its defined by the source type BUT the inheritedMethod may not be
		// so now with generics, the inheritedMethod should be resolved since we don't want to waste time dealing
		// with Unresolved types over & over
		if (inheritedMethod.declaringClass instanceof BinaryTypeBinding)
			((BinaryTypeBinding) inheritedMethod.declaringClass).resolveTypesFor(inheritedMethod);

		if (checkForNameClash(currentMethod, inheritedMethod)) {
			this.problemReporter(currentMethod).methodNameClash(currentMethod, inheritedMethod);
			continue nextMethod;
		}

		if (!currentMethod.isAbstract() && inheritedMethod.isAbstract()) {
			if ((currentMethod.modifiers & CompilerModifiers.AccOverriding) == 0)
				currentMethod.modifiers |= CompilerModifiers.AccImplementing;
		} else {
			currentMethod.modifiers |= CompilerModifiers.AccOverriding;
		}

		boolean addBridgeMethod = inheritedMethod.hasSubstitutedReturnType();
		if (currentMethod.returnType != inheritedMethod.returnType) {
			// can be [] of Class#RAW vs. Class<T>
			if (!isReturnTypeSubstituable(currentMethod, inheritedMethod)) {
				this.problemReporter(currentMethod).incompatibleReturnType(currentMethod, inheritedMethod);
				continue nextMethod;
			}

			TypeBinding inheritedReturnType = inheritedMethod.returnType.leafComponentType();
			TypeBinding returnType = currentMethod.returnType.leafComponentType();
			if (inheritedReturnType.isRawType()) {
				if (returnType.isParameterizedType() && hasBoundedParameters((ParameterizedTypeBinding) returnType)) {
					this.problemReporter(currentMethod).methodNameClash(currentMethod, inheritedMethod);
					continue nextMethod;
				}
			} else if (inheritedReturnType.isParameterizedType()) {
				if (!returnType.isParameterizedType()) 
					this.problemReporter(currentMethod).unsafeReturnTypeOverride(currentMethod, inheritedMethod, ((MethodDeclaration)currentMethod.sourceMethod()).returnType);
			} else if (inheritedReturnType.isTypeVariable()) {
				this.problemReporter(currentMethod).unsafeReturnTypeOverride(currentMethod, inheritedMethod,  ((MethodDeclaration)currentMethod.sourceMethod()).returnType);
			}
			addBridgeMethod = true;
		}

		if (addBridgeMethod || inheritedMethod.hasSubstitutedParameters()) {
		    MethodBinding original = inheritedMethod.original();
		    if (!areReturnTypesEqual(original, currentMethod) || !areParametersEqual(original, currentMethod))
				this.type.addSyntheticBridgeMethod(original, currentMethod);
		}

		if (currentMethod.thrownExceptions != NoExceptions)
			this.checkExceptions(currentMethod, inheritedMethod);
		if (inheritedMethod.isFinal())
			this.problemReporter(currentMethod).finalMethodCannotBeOverridden(currentMethod, inheritedMethod);
		if (!this.isAsVisible(currentMethod, inheritedMethod))
			this.problemReporter(currentMethod).visibilityConflict(currentMethod, inheritedMethod);
		if (environment.options.reportDeprecationWhenOverridingDeprecatedMethod && inheritedMethod.isViewedAsDeprecated()) {
			if (!currentMethod.isViewedAsDeprecated() || environment.options.reportDeprecationInsideDeprecatedCode) {
				// check against the other inherited methods to see if they hide this inheritedMethod
				ReferenceBinding declaringClass = inheritedMethod.declaringClass;
				if (declaringClass.isInterface())
					for (int j = length; --j >= 0;)
						if (i != j && methods[j].declaringClass.implementsInterface(declaringClass, false))
							continue nextMethod;

				this.problemReporter(currentMethod).overridesDeprecatedMethod(currentMethod, inheritedMethod);
			}
		}
	}
}
boolean checkForNameClash(MethodBinding currentMethod, MethodBinding inheritedMethod) {
	TypeBinding[] currentVars = currentMethod.typeVariables;
	TypeBinding[] inheritedVars = inheritedMethod.typeVariables;
	if (currentVars.length != inheritedVars.length && currentVars.length > 0) return true; // must match unless all are replaced

	// must check each parameter pair to see if parameterized types are compatible
	// already know that each erasure() is equal
	TypeBinding[] currentArgs = currentMethod.parameters;
	TypeBinding[] inheritedArgs = inheritedMethod.parameters;
	if (currentArgs != inheritedArgs) {
		for (int i = 0, l = currentArgs.length; i < l; i++) {
			TypeBinding currentArg = currentArgs[i].leafComponentType();
			TypeBinding inheritedArg = inheritedArgs[i].leafComponentType();
			if (currentArg != inheritedArg && currentArg.isParameterizedType()) {
				if (!inheritedArg.isParameterizedType()) return true;
				TypeBinding[] args1 = ((ParameterizedTypeBinding) currentArg).arguments;
				TypeBinding[] args2 = ((ParameterizedTypeBinding) inheritedArg).arguments;
				for (int j = 0, k = args1.length; j < k; j++)
				    if (!areTypesEquivalent(args1[j], args2[j])) return true;
			}
		}
	}
	return false;
}
boolean doesMethodOverride(MethodBinding method, MethodBinding inheritedMethod) {
	return areParametersEqual(method, inheritedMethod) && isReturnTypeSubstituable(method, inheritedMethod);
}
boolean hasBoundedParameters(ParameterizedTypeBinding parameterizedType) {
	TypeBinding[] arguments = parameterizedType.arguments;
	if (arguments == null) return false;

	nextArg : for (int i = 0, l = arguments.length; i < l; i++) {
		if (arguments[i].isWildcard())
			if (((WildcardBinding) arguments[i]).kind == org.eclipse.jdt.internal.compiler.ast.Wildcard.UNBOUND)
				continue nextArg;
		if (arguments[i].isTypeVariable())
			if (((TypeVariableBinding) arguments[i]).firstBound == null)
				continue nextArg;
		return true;
	}
	return false;
}
boolean isReturnTypeSubstituable(MethodBinding one, MethodBinding two) {
	if (one.returnType == two.returnType) return true;

	return isTypeSubstituable(one.returnType.erasure(), two.returnType.erasure());
}
boolean isTypeSubstituable(TypeBinding one, TypeBinding two) {
	if (one == two) return true;
	if (one.isArrayType() || two.isArrayType()) {
		if (one.isArrayType() != two.isArrayType()) return false;
		ArrayBinding arrayOne = (ArrayBinding) one;
		ArrayBinding arrayTwo = (ArrayBinding) two;
		if (arrayOne.dimensions != arrayTwo.dimensions) return false;
		one = arrayOne.leafComponentType;
		two = arrayTwo.leafComponentType;
	}
	if (one.isBaseType() || two.isBaseType()) return false;

	ReferenceBinding subType = (ReferenceBinding) one;
	ReferenceBinding superType = (ReferenceBinding) two;
	if (CharOperation.equals(subType.compoundName, superType.compoundName)) return true;

	superType = BinaryTypeBinding.resolveType(superType, this.environment, true);
	subType = BinaryTypeBinding.resolveType(subType, this.environment, true);
	if (superType.isInterface())
		return subType.implementsInterface(superType, true);
	return isSameClassOrSubclassOf(subType, superType); // interfaces are subclasses of Object too
}
}
