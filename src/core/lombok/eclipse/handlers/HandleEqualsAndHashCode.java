/*
 * Copyright (C) 2009-2011 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.handlers;

import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.BinaryExpression;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConditionalExpression;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FalseLiteral;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.IfStatement;
import org.eclipse.jdt.internal.compiler.ast.InstanceOfExpression;
import org.eclipse.jdt.internal.compiler.ast.IntLiteral;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NameReference;
import org.eclipse.jdt.internal.compiler.ast.NullLiteral;
import org.eclipse.jdt.internal.compiler.ast.OperatorIds;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.SuperReference;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TrueLiteral;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.ast.UnaryExpression;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.mangosdk.spi.ProviderFor;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.core.AnnotationValues;
import lombok.core.AST.Kind;
import lombok.eclipse.Eclipse;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;

/**
 * Handles the {@code EqualsAndHashCode} annotation for eclipse.
 */
@ProviderFor(EclipseAnnotationHandler.class)
public class HandleEqualsAndHashCode extends EclipseAnnotationHandler<EqualsAndHashCode> {
	private static final Set<String> BUILT_IN_TYPES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
			"byte", "short", "int", "long", "char", "boolean", "double", "float")));
	
	private void checkForBogusFieldNames(EclipseNode type, AnnotationValues<EqualsAndHashCode> annotation) {
		if (annotation.isExplicit("exclude")) {
			for (int i : createListOfNonExistentFields(Arrays.asList(annotation.getInstance().exclude()), type, true, true)) {
				annotation.setWarning("exclude", "This field does not exist, or would have been excluded anyway.", i);
			}
		}
		if (annotation.isExplicit("of")) {
			for (int i : createListOfNonExistentFields(Arrays.asList(annotation.getInstance().of()), type, false, false)) {
				annotation.setWarning("of", "This field does not exist.", i);
			}
		}
	}
	
	public void generateEqualsAndHashCodeForType(EclipseNode typeNode, EclipseNode errorNode) {
		for (EclipseNode child : typeNode.down()) {
			if (child.getKind() == Kind.ANNOTATION) {
				if (annotationTypeMatches(EqualsAndHashCode.class, child)) {
					//The annotation will make it happen, so we can skip it.
					return;
				}
			}
		}
		
		generateMethods(typeNode, errorNode, null, null, null, false, FieldAccess.GETTER);
	}
	
	@Override public void handle(AnnotationValues<EqualsAndHashCode> annotation,
			Annotation ast, EclipseNode annotationNode) {
		EqualsAndHashCode ann = annotation.getInstance();
		List<String> excludes = Arrays.asList(ann.exclude());
		List<String> includes = Arrays.asList(ann.of());
		EclipseNode typeNode = annotationNode.up();
		
		checkForBogusFieldNames(typeNode, annotation);
		
		Boolean callSuper = ann.callSuper();
		if (!annotation.isExplicit("callSuper")) callSuper = null;
		if (!annotation.isExplicit("exclude")) excludes = null;
		if (!annotation.isExplicit("of")) includes = null;
		
		if (excludes != null && includes != null) {
			excludes = null;
			annotation.setWarning("exclude", "exclude and of are mutually exclusive; the 'exclude' parameter will be ignored.");
		}
		
		FieldAccess fieldAccess = ann.doNotUseGetters() ? FieldAccess.PREFER_FIELD : FieldAccess.GETTER;
		
		generateMethods(typeNode, annotationNode, excludes, includes, callSuper, true, fieldAccess);
	}
	
	public void generateMethods(EclipseNode typeNode, EclipseNode errorNode, List<String> excludes, List<String> includes,
			Boolean callSuper, boolean whineIfExists, FieldAccess fieldAccess) {
		assert excludes == null || includes == null;
		
		TypeDeclaration typeDecl = null;
		
		if (typeNode.get() instanceof TypeDeclaration) typeDecl = (TypeDeclaration) typeNode.get();
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers &
				(ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation | ClassFileConstants.AccEnum)) != 0;
		
		if (typeDecl == null || notAClass) {
			errorNode.addError("@EqualsAndHashCode is only supported on a class.");
			return;
		}
		
		boolean implicitCallSuper = callSuper == null;
		
		if (callSuper == null) {
			try {
				callSuper = ((Boolean)EqualsAndHashCode.class.getMethod("callSuper").getDefaultValue()).booleanValue();
			} catch (Exception ignore) {
				throw new InternalError("Lombok bug - this cannot happen - can't find callSuper field in EqualsAndHashCode annotation.");
			}
		}
		
		boolean isDirectDescendantOfObject = true;
		
		if (typeDecl.superclass != null) {
			String p = typeDecl.superclass.toString();
			isDirectDescendantOfObject = p.equals("Object") || p.equals("java.lang.Object");
		}
		
		if (isDirectDescendantOfObject && callSuper) {
			errorNode.addError("Generating equals/hashCode with a supercall to java.lang.Object is pointless.");
			return;
		}
		
		if (!isDirectDescendantOfObject && !callSuper && implicitCallSuper) {
			errorNode.addWarning("Generating equals/hashCode implementation but without a call to superclass, even though this class does not extend java.lang.Object. If this is intentional, add '@EqualsAndHashCode(callSuper=false)' to your type.");
		}
		
		List<EclipseNode> nodesForEquality = new ArrayList<EclipseNode>();
		if (includes != null) {
			for (EclipseNode child : typeNode.down()) {
				if (child.getKind() != Kind.FIELD) continue;
				FieldDeclaration fieldDecl = (FieldDeclaration) child.get();
				if (includes.contains(new String(fieldDecl.name))) nodesForEquality.add(child);
			}
		} else {
			for (EclipseNode child : typeNode.down()) {
				if (child.getKind() != Kind.FIELD) continue;
				FieldDeclaration fieldDecl = (FieldDeclaration) child.get();
				if (!filterField(fieldDecl)) continue;
				
				//Skip transient fields.
				if ((fieldDecl.modifiers & ClassFileConstants.AccTransient) != 0) continue;
				//Skip excluded fields.
				if (excludes != null && excludes.contains(new String(fieldDecl.name))) continue;
				nodesForEquality.add(child);
			}
		}
		
		boolean isFinal = (typeDecl.modifiers & ClassFileConstants.AccFinal) != 0;
		boolean needsCanEqual = !isDirectDescendantOfObject || !isFinal;
		java.util.List<MemberExistsResult> existsResults = new ArrayList<MemberExistsResult>();
		existsResults.add(methodExists("equals", typeNode));
		existsResults.add(methodExists("hashCode", typeNode));
		existsResults.add(methodExists("canEqual", typeNode));
		switch (Collections.max(existsResults)) {
		case EXISTS_BY_LOMBOK:
			return;
		case EXISTS_BY_USER:
			if (whineIfExists) {
				String msg = String.format("Not generating equals%s: A method with one of those names already exists. (Either all or none of these methods will be generated).", needsCanEqual ? ", hashCode and canEquals" : " and hashCode");
				errorNode.addWarning(msg);
			}
			return;
		case NOT_EXISTS:
		default:
			//fallthrough
		}
		
		MethodDeclaration equalsMethod = createEquals(typeNode, nodesForEquality, callSuper, errorNode.get(), fieldAccess, needsCanEqual);
		equalsMethod.traverse(new SetGeneratedByVisitor(errorNode.get()), ((TypeDeclaration)typeNode.get()).scope);
		injectMethod(typeNode, equalsMethod);
		
		if (needsCanEqual) {
			MethodDeclaration canEqualMethod = createCanEqual(typeNode, errorNode.get());
			canEqualMethod.traverse(new SetGeneratedByVisitor(errorNode.get()), ((TypeDeclaration)typeNode.get()).scope);
			injectMethod(typeNode, canEqualMethod);
		}
		
		MethodDeclaration hashCodeMethod = createHashCode(typeNode, nodesForEquality, callSuper, errorNode.get(), fieldAccess);
		hashCodeMethod.traverse(new SetGeneratedByVisitor(errorNode.get()), ((TypeDeclaration)typeNode.get()).scope);
		injectMethod(typeNode, hashCodeMethod);
	}
	
	private MethodDeclaration createHashCode(EclipseNode type, Collection<EclipseNode> fields, boolean callSuper, ASTNode source, FieldAccess fieldAccess) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		MethodDeclaration method = new MethodDeclaration(
				((CompilationUnitDeclaration) type.top().get()).compilationResult);
		setGeneratedBy(method, source);
		
		method.modifiers = toEclipseModifier(AccessLevel.PUBLIC);
		method.returnType = TypeReference.baseTypeReference(TypeIds.T_int, 0);
		setGeneratedBy(method.returnType, source);
		method.annotations = new Annotation[] {makeMarkerAnnotation(TypeConstants.JAVA_LANG_OVERRIDE, source)};
		method.selector = "hashCode".toCharArray();
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		method.arguments = null;
		
		List<Statement> statements = new ArrayList<Statement>();
		List<Expression> intoResult = new ArrayList<Expression>();
		
		final char[] PRIME = "PRIME".toCharArray();
		final char[] RESULT = "result".toCharArray();
		final boolean isEmpty = fields.isEmpty();
		
		/* final int PRIME = 31; */ {
			/* Without fields, PRIME isn't used, and that would trigger a 'local variable not used' warning. */
			if (!isEmpty || callSuper) {
				LocalDeclaration primeDecl = new LocalDeclaration(PRIME, pS, pE);
				setGeneratedBy(primeDecl, source);
				primeDecl.modifiers |= Modifier.FINAL;
				primeDecl.type = TypeReference.baseTypeReference(TypeIds.T_int, 0);
				primeDecl.type.sourceStart = pS; primeDecl.type.sourceEnd = pE;
				setGeneratedBy(primeDecl.type, source);
				primeDecl.initialization = makeIntLiteral("31".toCharArray(), source);
				statements.add(primeDecl);
			}
		}
		
		/* int result = 1; */ {
			LocalDeclaration resultDecl = new LocalDeclaration(RESULT, pS, pE);
			setGeneratedBy(resultDecl, source);
			resultDecl.initialization = makeIntLiteral("1".toCharArray(), source);
			resultDecl.type = TypeReference.baseTypeReference(TypeIds.T_int, 0);
			resultDecl.type.sourceStart = pS; resultDecl.type.sourceEnd = pE;
			setGeneratedBy(resultDecl.type, source);
			statements.add(resultDecl);
		}
		
		if (callSuper) {
			MessageSend callToSuper = new MessageSend();
			setGeneratedBy(callToSuper, source);
			callToSuper.sourceStart = pS; callToSuper.sourceEnd = pE;
			callToSuper.receiver = new SuperReference(pS, pE);
			setGeneratedBy(callToSuper.receiver, source);
			callToSuper.selector = "hashCode".toCharArray();
			intoResult.add(callToSuper);
		}
		
		int tempCounter = 0;
		for (EclipseNode field : fields) {
			TypeReference fType = getFieldType(field, fieldAccess);
			char[] token = fType.getLastToken();
			Expression fieldAccessor = createFieldAccessor(field, fieldAccess, source);
			if (fType.dimensions() == 0 && token != null) {
				if (Arrays.equals(TypeConstants.FLOAT, token)) {
					/* Float.floatToIntBits(fieldName) */
					MessageSend floatToIntBits = new MessageSend();
					floatToIntBits.sourceStart = pS; floatToIntBits.sourceEnd = pE;
					setGeneratedBy(floatToIntBits, source);
					floatToIntBits.receiver = generateQualifiedNameRef(source, TypeConstants.JAVA_LANG_FLOAT);
					floatToIntBits.selector = "floatToIntBits".toCharArray();
					floatToIntBits.arguments = new Expression[] { fieldAccessor };
					intoResult.add(floatToIntBits);
				} else if (Arrays.equals(TypeConstants.DOUBLE, token)) {
					/* longToIntForHashCode(Double.doubleToLongBits(fieldName)) */
					MessageSend doubleToLongBits = new MessageSend();
					doubleToLongBits.sourceStart = pS; doubleToLongBits.sourceEnd = pE;
					setGeneratedBy(doubleToLongBits, source);
					doubleToLongBits.receiver = generateQualifiedNameRef(source, TypeConstants.JAVA_LANG_DOUBLE);
					doubleToLongBits.selector = "doubleToLongBits".toCharArray();
					doubleToLongBits.arguments = new Expression[] { fieldAccessor };
					final char[] tempName = ("temp" + ++tempCounter).toCharArray();
					LocalDeclaration tempVar = new LocalDeclaration(tempName, pS, pE);
					setGeneratedBy(tempVar, source);
					tempVar.initialization = doubleToLongBits;
					tempVar.type = TypeReference.baseTypeReference(TypeIds.T_long, 0);
					tempVar.type.sourceStart = pS; tempVar.type.sourceEnd = pE;
					setGeneratedBy(tempVar.type, source);
					tempVar.modifiers = Modifier.FINAL;
					statements.add(tempVar);
					SingleNameReference copy1 = new SingleNameReference(tempName, p);
					setGeneratedBy(copy1, source);
					SingleNameReference copy2 = new SingleNameReference(tempName, p);
					setGeneratedBy(copy2, source);
					intoResult.add(longToIntForHashCode(copy1, copy2, source));
				} else if (Arrays.equals(TypeConstants.BOOLEAN, token)) {
					/* booleanField ? 1231 : 1237 */
					IntLiteral int1231 = makeIntLiteral("1231".toCharArray(), source);
					IntLiteral int1237 = makeIntLiteral("1237".toCharArray(), source);
					ConditionalExpression int1231or1237 = new ConditionalExpression(fieldAccessor, int1231, int1237);
					setGeneratedBy(int1231or1237, source);
					intoResult.add(int1231or1237);
				} else if (Arrays.equals(TypeConstants.LONG, token)) {
					intoResult.add(longToIntForHashCode(fieldAccessor, createFieldAccessor(field, fieldAccess, source), source));
				} else if (BUILT_IN_TYPES.contains(new String(token))) {
					intoResult.add(fieldAccessor);
				} else /* objects */ {
					/* this.fieldName == null ? 0 : this.fieldName.hashCode() */
					MessageSend hashCodeCall = new MessageSend();
					hashCodeCall.sourceStart = pS; hashCodeCall.sourceEnd = pE;
					setGeneratedBy(hashCodeCall, source);
					hashCodeCall.receiver = createFieldAccessor(field, fieldAccess, source);
					hashCodeCall.selector = "hashCode".toCharArray();
					NullLiteral nullLiteral = new NullLiteral(pS, pE);
					setGeneratedBy(nullLiteral, source);
					EqualExpression objIsNull = new EqualExpression(fieldAccessor, nullLiteral, OperatorIds.EQUAL_EQUAL);
					setGeneratedBy(objIsNull, source);
					IntLiteral int0 = makeIntLiteral("0".toCharArray(), source);
					ConditionalExpression nullOrHashCode = new ConditionalExpression(objIsNull, int0, hashCodeCall);
					nullOrHashCode.sourceStart = pS; nullOrHashCode.sourceEnd = pE;
					setGeneratedBy(nullOrHashCode, source);
					intoResult.add(nullOrHashCode);
				}
			} else if (fType.dimensions() > 0 && token != null) {
				/* Arrays.deepHashCode(array)  //just hashCode for simple arrays */
				MessageSend arraysHashCodeCall = new MessageSend();
				arraysHashCodeCall.sourceStart = pS; arraysHashCodeCall.sourceEnd = pE;
				setGeneratedBy(arraysHashCodeCall, source);
				arraysHashCodeCall.receiver = generateQualifiedNameRef(source, TypeConstants.JAVA, TypeConstants.UTIL, "Arrays".toCharArray());
				if (fType.dimensions() > 1 || !BUILT_IN_TYPES.contains(new String(token))) {
					arraysHashCodeCall.selector = "deepHashCode".toCharArray();
				} else {
					arraysHashCodeCall.selector = "hashCode".toCharArray();
				}
				arraysHashCodeCall.arguments = new Expression[] { fieldAccessor };
				intoResult.add(arraysHashCodeCall);
			}
		}
		
		/* fold each intoResult entry into:
		   result = result * PRIME + (item); */ {
			for (Expression ex : intoResult) {
				SingleNameReference resultRef = new SingleNameReference(RESULT, p);
				setGeneratedBy(resultRef, source);
				SingleNameReference primeRef = new SingleNameReference(PRIME, p);
				setGeneratedBy(primeRef, source);
				BinaryExpression multiplyByPrime = new BinaryExpression(resultRef, primeRef, OperatorIds.MULTIPLY);
				multiplyByPrime.sourceStart = pS; multiplyByPrime.sourceEnd = pE;
				setGeneratedBy(multiplyByPrime, source);
				BinaryExpression addItem = new BinaryExpression(multiplyByPrime, ex, OperatorIds.PLUS);
				addItem.sourceStart = pS; addItem.sourceEnd = pE;
				setGeneratedBy(addItem, source);
				resultRef = new SingleNameReference(RESULT, p);
				setGeneratedBy(resultRef, source);
				Assignment assignment = new Assignment(resultRef, addItem, pE);
				assignment.sourceStart = pS; assignment.sourceEnd = assignment.statementEnd = pE;
				setGeneratedBy(assignment, source);
				statements.add(assignment);
			}
		}
		
		/* return result; */ {
			SingleNameReference resultRef = new SingleNameReference(RESULT, p);
			setGeneratedBy(resultRef, source);
			ReturnStatement returnStatement = new ReturnStatement(resultRef, pS, pE);
			setGeneratedBy(returnStatement, source);
			statements.add(returnStatement);
		}
		method.statements = statements.toArray(new Statement[statements.size()]);
		return method;
	}
	
	private TypeReference createTypeReference(EclipseNode type, long p) {
		List<String> list = new ArrayList<String>();
		list.add(type.getName());
		EclipseNode tNode = type.up();
		while (tNode != null && tNode.getKind() == Kind.TYPE) {
			list.add(tNode.getName());
			tNode = tNode.up();
		}
		Collections.reverse(list);
		
		if (list.size() == 1) return new SingleTypeReference(list.get(0).toCharArray(), p);
		long[] ps = new long[list.size()];
		char[][] tokens = new char[list.size()][];
		for (int i = 0; i < list.size(); i++) {
			ps[i] = p;
			tokens[i] = list.get(i).toCharArray();
		}
		
		return new QualifiedTypeReference(tokens, ps);
	}
	
	private MethodDeclaration createEquals(EclipseNode type, Collection<EclipseNode> fields, boolean callSuper, ASTNode source, FieldAccess fieldAccess, boolean needsCanEqual) {
		int pS = source.sourceStart; int pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		TypeDeclaration typeDecl = (TypeDeclaration)type.get();
		
		MethodDeclaration method = new MethodDeclaration(
				((CompilationUnitDeclaration) type.top().get()).compilationResult);
		setGeneratedBy(method, source);
		method.modifiers = toEclipseModifier(AccessLevel.PUBLIC);
		method.returnType = TypeReference.baseTypeReference(TypeIds.T_boolean, 0);
		method.returnType.sourceStart = pS; method.returnType.sourceEnd = pE;
		setGeneratedBy(method.returnType, source);
		method.annotations = new Annotation[] {makeMarkerAnnotation(TypeConstants.JAVA_LANG_OVERRIDE, source)};
		method.selector = "equals".toCharArray();
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		TypeReference objectRef = new QualifiedTypeReference(TypeConstants.JAVA_LANG_OBJECT, new long[] { p, p, p });
		setGeneratedBy(objectRef, source);
		method.arguments = new Argument[] {new Argument(new char[] { 'o' }, 0, objectRef, Modifier.FINAL)};
		method.arguments[0].sourceStart = pS; method.arguments[0].sourceEnd = pE;
		setGeneratedBy(method.arguments[0], source);
		
		List<Statement> statements = new ArrayList<Statement>();
		
		/* if (o == this) return true; */ {
			SingleNameReference oRef = new SingleNameReference(new char[] { 'o' }, p);
			setGeneratedBy(oRef, source);
			ThisReference thisRef = new ThisReference(pS, pE);
			setGeneratedBy(thisRef, source);
			EqualExpression otherEqualsThis = new EqualExpression(oRef, thisRef, OperatorIds.EQUAL_EQUAL);
			setGeneratedBy(otherEqualsThis, source);
			
			TrueLiteral trueLiteral = new TrueLiteral(pS, pE);
			setGeneratedBy(trueLiteral, source);
			ReturnStatement returnTrue = new ReturnStatement(trueLiteral, pS, pE);
			setGeneratedBy(returnTrue, source);
			IfStatement ifOtherEqualsThis = new IfStatement(otherEqualsThis, returnTrue, pS, pE);
			setGeneratedBy(ifOtherEqualsThis, source);
			statements.add(ifOtherEqualsThis);
		}
		
		/* if (!(o instanceof Outer.Inner.MyType) return false; */ {
			SingleNameReference oRef = new SingleNameReference(new char[] { 'o' }, p);
			setGeneratedBy(oRef, source);
			
			TypeReference typeReference = createTypeReference(type, p);
			setGeneratedBy(typeReference, source);

			InstanceOfExpression instanceOf = new InstanceOfExpression(oRef, typeReference);
			instanceOf.sourceStart = pS; instanceOf.sourceEnd = pE;
			setGeneratedBy(instanceOf, source);
			
			Expression notInstanceOf = new UnaryExpression(instanceOf, OperatorIds.NOT);
			setGeneratedBy(notInstanceOf, source);
			
			FalseLiteral falseLiteral = new FalseLiteral(pS, pE);
			setGeneratedBy(falseLiteral, source);
			
			ReturnStatement returnFalse = new ReturnStatement(falseLiteral, pS, pE);
			setGeneratedBy(returnFalse, source);
			
			IfStatement ifNotInstanceOf = new IfStatement(notInstanceOf, returnFalse, pS, pE);
			setGeneratedBy(ifNotInstanceOf, source);
			statements.add(ifNotInstanceOf);
		}
		
		char[] otherName = "other".toCharArray();
		
		/* MyType<?> other = (MyType<?>) o; */ {
			if (!fields.isEmpty() || needsCanEqual) {
				LocalDeclaration other = new LocalDeclaration(otherName, pS, pE);
				other.modifiers |= ClassFileConstants.AccFinal;
				setGeneratedBy(other, source);
				char[] typeName = typeDecl.name;
				TypeReference targetType;
				if (typeDecl.typeParameters == null || typeDecl.typeParameters.length == 0) {
					targetType = new SingleTypeReference(typeName, p);
					setGeneratedBy(targetType, source);
					other.type = new SingleTypeReference(typeName, p);
					setGeneratedBy(other.type, source);
				} else {
					TypeReference[] typeArgs = new TypeReference[typeDecl.typeParameters.length];
					for (int i = 0; i < typeArgs.length; i++) {
						typeArgs[i] = new Wildcard(Wildcard.UNBOUND);
						typeArgs[i].sourceStart = pS; typeArgs[i].sourceEnd = pE;
						setGeneratedBy(typeArgs[i], source);
					}
					targetType = new ParameterizedSingleTypeReference(typeName, typeArgs, 0, p);
					setGeneratedBy(targetType, source);
					other.type = new ParameterizedSingleTypeReference(typeName, copyTypes(typeArgs, source), 0, p);
					setGeneratedBy(other.type, source);
				}
				NameReference oRef = new SingleNameReference(new char[] { 'o' }, p);
				setGeneratedBy(oRef, source);
				other.annotations = createSuppressWarningsAll(source, null);
				other.initialization = makeCastExpression(oRef, targetType, source);
				statements.add(other);
			}
		}
		
		/* if (!other.canEqual((java.lang.Object) this)) return false; */ {
			if (needsCanEqual) {
				MessageSend otherCanEqual = new MessageSend();
				otherCanEqual.sourceStart = pS; otherCanEqual.sourceEnd = pE;
				setGeneratedBy(otherCanEqual, source);
				otherCanEqual.receiver = new SingleNameReference(otherName, p);
				setGeneratedBy(otherCanEqual.receiver, source);
				otherCanEqual.selector = "canEqual".toCharArray();
				
				ThisReference thisReference = new ThisReference(pS, pE);
				setGeneratedBy(thisReference, source);
				CastExpression castThisRef = makeCastExpression(thisReference, generateQualifiedTypeRef(source, TypeConstants.JAVA_LANG_OBJECT), source);
				castThisRef.sourceStart = pS; castThisRef.sourceEnd = pE;
				
				otherCanEqual.arguments = new Expression[] {castThisRef};
				
				Expression notOtherCanEqual = new UnaryExpression(otherCanEqual, OperatorIds.NOT);
				setGeneratedBy(notOtherCanEqual, source);
				
				FalseLiteral falseLiteral = new FalseLiteral(pS, pE);
				setGeneratedBy(falseLiteral, source);
				
				ReturnStatement returnFalse = new ReturnStatement(falseLiteral, pS, pE);
				setGeneratedBy(returnFalse, source);
				
				IfStatement ifNotCanEqual = new IfStatement(notOtherCanEqual, returnFalse, pS, pE);
				setGeneratedBy(ifNotCanEqual, source);
				
				statements.add(ifNotCanEqual);
			}
		}
		
		/* if (!super.equals(o)) return false; */
		if (callSuper) {
			MessageSend callToSuper = new MessageSend();
			callToSuper.sourceStart = pS; callToSuper.sourceEnd = pE;
			setGeneratedBy(callToSuper, source);
			callToSuper.receiver = new SuperReference(pS, pE);
			setGeneratedBy(callToSuper.receiver, source);
			callToSuper.selector = "equals".toCharArray();
			SingleNameReference oRef = new SingleNameReference(new char[] { 'o' }, p);
			setGeneratedBy(oRef, source);
			callToSuper.arguments = new Expression[] {oRef};
			Expression superNotEqual = new UnaryExpression(callToSuper, OperatorIds.NOT);
			setGeneratedBy(superNotEqual, source);
			FalseLiteral falseLiteral = new FalseLiteral(pS, pE);
			setGeneratedBy(falseLiteral, source);
			ReturnStatement returnFalse = new ReturnStatement(falseLiteral, pS, pE);
			setGeneratedBy(returnFalse, source);
			IfStatement ifSuperEquals = new IfStatement(superNotEqual, returnFalse, pS, pE);
			setGeneratedBy(ifSuperEquals, source);
			statements.add(ifSuperEquals);
		}
		
		for (EclipseNode field : fields) {
			TypeReference fType = getFieldType(field, fieldAccess);
			char[] token = fType.getLastToken();
			Expression thisFieldAccessor = createFieldAccessor(field, fieldAccess, source);
			Expression otherFieldAccessor = createFieldAccessor(field, fieldAccess, source, otherName);
			
			if (fType.dimensions() == 0 && token != null) {
				if (Arrays.equals(TypeConstants.FLOAT, token)) {
					statements.add(generateCompareFloatOrDouble(thisFieldAccessor, otherFieldAccessor, "Float".toCharArray(), source));
				} else if (Arrays.equals(TypeConstants.DOUBLE, token)) {
					statements.add(generateCompareFloatOrDouble(thisFieldAccessor, otherFieldAccessor, "Double".toCharArray(), source));
				} else if (BUILT_IN_TYPES.contains(new String(token))) {
					EqualExpression fieldsNotEqual = new EqualExpression(thisFieldAccessor, otherFieldAccessor, OperatorIds.NOT_EQUAL);
					setGeneratedBy(fieldsNotEqual, source);
					FalseLiteral falseLiteral = new FalseLiteral(pS, pE);
					setGeneratedBy(falseLiteral, source);
					ReturnStatement returnStatement = new ReturnStatement(falseLiteral, pS, pE);
					setGeneratedBy(returnStatement, source);
					IfStatement ifStatement = new IfStatement(fieldsNotEqual, returnStatement, pS, pE);
					setGeneratedBy(ifStatement, source);
					statements.add(ifStatement);
				} else /* objects */ {
					NullLiteral nullLiteral = new NullLiteral(pS, pE);
					setGeneratedBy(nullLiteral, source);
					EqualExpression fieldIsNull = new EqualExpression(thisFieldAccessor, nullLiteral, OperatorIds.EQUAL_EQUAL);
					nullLiteral = new NullLiteral(pS, pE);
					setGeneratedBy(nullLiteral, source);
					EqualExpression otherFieldIsntNull = new EqualExpression(otherFieldAccessor, nullLiteral, OperatorIds.NOT_EQUAL);
					MessageSend equalsCall = new MessageSend();
					equalsCall.sourceStart = pS; equalsCall.sourceEnd = pE;
					setGeneratedBy(equalsCall, source);
					equalsCall.receiver = createFieldAccessor(field, fieldAccess, source);
					equalsCall.selector = "equals".toCharArray();
					Expression equalsArg = createFieldAccessor(field, fieldAccess, source, otherName);
					CastExpression castEqualsArg = makeCastExpression(equalsArg, generateQualifiedTypeRef(source, TypeConstants.JAVA_LANG_OBJECT), source);
					castEqualsArg.sourceStart = pS; castEqualsArg.sourceEnd = pE;
					equalsCall.arguments = new Expression[] { castEqualsArg };
					UnaryExpression fieldsNotEqual = new UnaryExpression(equalsCall, OperatorIds.NOT);
					fieldsNotEqual.sourceStart = pS; fieldsNotEqual.sourceEnd = pE;
					setGeneratedBy(fieldsNotEqual, source);
					ConditionalExpression fullEquals = new ConditionalExpression(fieldIsNull, otherFieldIsntNull, fieldsNotEqual);
					fullEquals.sourceStart = pS; fullEquals.sourceEnd = pE;
					setGeneratedBy(fullEquals, source);
					FalseLiteral falseLiteral = new FalseLiteral(pS, pE);
					setGeneratedBy(falseLiteral, source);
					ReturnStatement returnStatement = new ReturnStatement(falseLiteral, pS, pE);
					setGeneratedBy(returnStatement, source);
					IfStatement ifStatement = new IfStatement(fullEquals, returnStatement, pS, pE);
					setGeneratedBy(ifStatement, source);
					statements.add(ifStatement);
				}
			} else if (fType.dimensions() > 0 && token != null) {
				MessageSend arraysEqualCall = new MessageSend();
				arraysEqualCall.sourceStart = pS; arraysEqualCall.sourceEnd = pE;
				setGeneratedBy(arraysEqualCall, source);
				arraysEqualCall.receiver = generateQualifiedNameRef(source, TypeConstants.JAVA, TypeConstants.UTIL, "Arrays".toCharArray());
				if (fType.dimensions() > 1 || !BUILT_IN_TYPES.contains(new String(token))) {
					arraysEqualCall.selector = "deepEquals".toCharArray();
				} else {
					arraysEqualCall.selector = "equals".toCharArray();
				}
				arraysEqualCall.arguments = new Expression[] { thisFieldAccessor, otherFieldAccessor };
				UnaryExpression arraysNotEqual = new UnaryExpression(arraysEqualCall, OperatorIds.NOT);
				arraysNotEqual.sourceStart = pS; arraysNotEqual.sourceEnd = pE;
				setGeneratedBy(arraysNotEqual, source);
				FalseLiteral falseLiteral = new FalseLiteral(pS, pE);
				setGeneratedBy(falseLiteral, source);
				ReturnStatement returnStatement = new ReturnStatement(falseLiteral, pS, pE);
				setGeneratedBy(returnStatement, source);
				IfStatement ifStatement = new IfStatement(arraysNotEqual, returnStatement, pS, pE);
				setGeneratedBy(ifStatement, source);
				statements.add(ifStatement);
			}
		}
		
		/* return true; */ {
			TrueLiteral trueLiteral = new TrueLiteral(pS, pE);
			setGeneratedBy(trueLiteral, source);
			ReturnStatement returnStatement = new ReturnStatement(trueLiteral, pS, pE);
			setGeneratedBy(returnStatement, source);
			statements.add(returnStatement);
		}
		method.statements = statements.toArray(new Statement[statements.size()]);
		return method;
	}
	
	
	private MethodDeclaration createCanEqual(EclipseNode type, ASTNode source) {
		/* public boolean canEqual(final java.lang.Object other) {
		 *     return other instanceof Outer.Inner.MyType;
		 * }
		 */
		int pS = source.sourceStart; int pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		char[] otherName = "other".toCharArray();
		
		MethodDeclaration method = new MethodDeclaration(
				((CompilationUnitDeclaration) type.top().get()).compilationResult);
		setGeneratedBy(method, source);
		method.modifiers = toEclipseModifier(AccessLevel.PUBLIC);
		method.returnType = TypeReference.baseTypeReference(TypeIds.T_boolean, 0);
		method.returnType.sourceStart = pS; method.returnType.sourceEnd = pE;
		setGeneratedBy(method.returnType, source);
		method.selector = "canEqual".toCharArray();
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		TypeReference objectRef = new QualifiedTypeReference(TypeConstants.JAVA_LANG_OBJECT, new long[] { p, p, p });
		setGeneratedBy(objectRef, source);
		method.arguments = new Argument[] {new Argument(otherName, 0, objectRef, Modifier.FINAL)};
		method.arguments[0].sourceStart = pS; method.arguments[0].sourceEnd = pE;
		setGeneratedBy(method.arguments[0], source);
		
		SingleNameReference otherRef = new SingleNameReference(otherName, p);
		setGeneratedBy(otherRef, source);
		
		TypeReference typeReference = createTypeReference(type, p);
		setGeneratedBy(typeReference, source);
		
		InstanceOfExpression instanceOf = new InstanceOfExpression(otherRef, typeReference);
		instanceOf.sourceStart = pS; instanceOf.sourceEnd = pE;
		setGeneratedBy(instanceOf, source);
		
		ReturnStatement returnStatement = new ReturnStatement(instanceOf, pS, pE);
		setGeneratedBy(returnStatement, source);
		
		method.statements = new Statement[] {returnStatement};
		return method;
	}

	
	private IfStatement generateCompareFloatOrDouble(Expression thisRef, Expression otherRef, char[] floatOrDouble, ASTNode source) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		/* if (Float.compare(fieldName, other.fieldName) != 0) return false */
		MessageSend floatCompare = new MessageSend();
		floatCompare.sourceStart = pS; floatCompare.sourceEnd = pE;
		setGeneratedBy(floatCompare, source);
		floatCompare.receiver = generateQualifiedNameRef(source, TypeConstants.JAVA, TypeConstants.LANG, floatOrDouble);
		floatCompare.selector = "compare".toCharArray();
		floatCompare.arguments = new Expression[] {thisRef, otherRef};
		IntLiteral int0 = makeIntLiteral("0".toCharArray(), source);
		EqualExpression ifFloatCompareIsNot0 = new EqualExpression(floatCompare, int0, OperatorIds.NOT_EQUAL);
		ifFloatCompareIsNot0.sourceStart = pS; ifFloatCompareIsNot0.sourceEnd = pE;
		setGeneratedBy(ifFloatCompareIsNot0, source);
		FalseLiteral falseLiteral = new FalseLiteral(pS, pE);
		setGeneratedBy(falseLiteral, source);
		ReturnStatement returnFalse = new ReturnStatement(falseLiteral, pS, pE);
		setGeneratedBy(returnFalse, source);
		IfStatement ifStatement = new IfStatement(ifFloatCompareIsNot0, returnFalse, pS, pE);
		setGeneratedBy(ifStatement, source);
		return ifStatement;
	}
	
	/** Give 2 clones! */
	private Expression longToIntForHashCode(Expression ref1, Expression ref2, ASTNode source) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		/* (int)(ref >>> 32 ^ ref) */
		IntLiteral int32 = makeIntLiteral("32".toCharArray(), source);
		BinaryExpression higherBits = new BinaryExpression(ref1, int32, OperatorIds.UNSIGNED_RIGHT_SHIFT);
		setGeneratedBy(higherBits, source);
		BinaryExpression xorParts = new BinaryExpression(ref2, higherBits, OperatorIds.XOR);
		setGeneratedBy(xorParts, source);
		TypeReference intRef = TypeReference.baseTypeReference(TypeIds.T_int, 0);
		intRef.sourceStart = pS; intRef.sourceEnd = pE;
		setGeneratedBy(intRef, source);
		CastExpression expr = makeCastExpression(xorParts, intRef, source);
		expr.sourceStart = pS; expr.sourceEnd = pE;
		return expr;
	}
	
	private NameReference generateQualifiedNameRef(ASTNode source, char[]... varNames) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		NameReference ref;
		
		if (varNames.length > 1) ref = new QualifiedNameReference(varNames, new long[varNames.length], pS, pE);
		else ref = new SingleNameReference(varNames[0], p);
		setGeneratedBy(ref, source);
		return ref;
	}
	
	private TypeReference generateQualifiedTypeRef(ASTNode source, char[]... varNames) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		
		TypeReference ref;
		
		long[] poss = Eclipse.poss(source, varNames.length);
		if (varNames.length > 1) ref = new QualifiedTypeReference(varNames, poss);
		else ref = new SingleTypeReference(varNames[0], p);
		setGeneratedBy(ref, source);
		return ref;
	}
}
