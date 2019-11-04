/*
 * Copyright (c) 2019 by Andrew Charneski.
 *
 * The author licenses this file to you under the
 * Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.simiacryptus.devutil;

import com.simiacryptus.lang.ref.ReferenceCountingBase;
import org.eclipse.jdt.core.dom.*;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class RefAutoCoder extends AutoCoder {

  private boolean verbose = true;
  private boolean addRefcounting = true;

  public RefAutoCoder(String pathname) {
    super(pathname);
  }

  @Override
  @Nonnull
  public void apply() {
    if (isVerbose()) apply((cu, file) -> new LogNodes(cu, file));
    apply((cu, file) -> new RemoveRefs());
    if (isAddRefcounting()) {
      apply((cu, file) -> new InsertMethods());
      apply((cu, file) -> new InsertAddRefs());
      apply((cu, file) -> new InsertFreeRefs());
    }
  }

  public boolean isVerbose() {
    return verbose;
  }

  public RefAutoCoder setVerbose(boolean verbose) {
    this.verbose = verbose;
    return this;
  }

  public boolean isAddRefcounting() {
    return addRefcounting;
  }

  public RefAutoCoder setAddRefcounting(boolean addRefcounting) {
    this.addRefcounting = addRefcounting;
    return this;
  }

  protected class LogNodes extends ASTVisitor {
    private final File file;
    private CompilationUnit compilationUnit;

    private LogNodes(CompilationUnit compilationUnit, File file) {
      this.compilationUnit = compilationUnit;
      this.file = file;
    }

    @Override
    public void preVisit(ASTNode node) {
      logger.info(String.format("Previsit: %s at (%s:%s)", node.getClass(), file.getName(), compilationUnit.getLineNumber(node.getStartPosition())));
    }
  }

  protected class RemoveRefs extends ASTVisitor {

    @Override
    public void endVisit(TypeDeclaration node) {
      final ITypeBinding typeBinding = node.resolveBinding();
      if (derives(typeBinding, ReferenceCountingBase.class)) {
        removeMethods(node, "addRef");
        removeMethods(node, "freeRef");
        removeMethods(node, "_free");
        removeMethods(node, "addRefs");
        removeMethods(node, "freeRefs");
      }
      super.endVisit(node);
    }

    @Override
    public void endVisit(MethodInvocation node) {
      final String methodName = node.getName().toString();

      if (Arrays.asList("addRef", "freeRef", "addRefs", "freeRefs").contains(methodName)) {
        final ASTNode parent = node.getParent();
        if (parent instanceof MethodInvocation) {
          final int index = ((MethodInvocation) parent).arguments().indexOf(node);
          ((MethodInvocation) parent).arguments().set(index, setField(node.getExpression(), "parent", null));
          logger.debug(String.format("%s removed as argument %s of %s", methodName, index, parent));
        } else if (parent instanceof ExpressionStatement) {
          parent.delete();
          logger.debug(String.format("%s removed from %s", methodName, parent));
        } else {
          logger.warn(String.format("Cannot remove %s called in %s: %s", methodName, parent.getClass(), parent));
        }
      }
    }

  }

  protected class InsertFreeRefs extends ASTVisitor {

    @Override
    public void endVisit(TypeDeclaration node) {
      final ITypeBinding typeBinding = node.resolveBinding();
      if (derives(typeBinding, ReferenceCountingBase.class)) {
        //addMethod_addRef(node);
      }
      super.endVisit(node);
    }


    @Override
    public void endVisit(VariableDeclarationFragment declaration) {
      final ASTNode parent = declaration.getParent();
      if (parent instanceof VariableDeclarationStatement) {
        addFreeRef(declaration, ((VariableDeclarationStatement) parent).getType().resolveBinding());
      } else if (parent instanceof VariableDeclarationExpression) {
        addFreeRef(declaration, ((VariableDeclarationExpression) parent).getType().resolveBinding());
      } else if (parent instanceof FieldDeclaration) {
        addFreeRef(declaration, ((FieldDeclaration) parent).getType().resolveBinding());
      } else if (parent instanceof LambdaExpression) {
        final LambdaExpression lambdaExpression = (LambdaExpression) parent;
        final IMethodBinding methodBinding = lambdaExpression.resolveMethodBinding();
        final int paramNumber = lambdaExpression.parameters().indexOf(declaration);
        if (methodBinding != null) {
          final ITypeBinding typeBinding = methodBinding.getParameterTypes()[paramNumber];
          addFreeRef(declaration, typeBinding);
        }
      } else {
        logger.warn("Cannot handle " + parent);
      }
    }

    @Override
    public void endVisit(SingleVariableDeclaration declaration) {
      addFreeRef(declaration, declaration.getType().resolveBinding());
    }

    public void addFreeRef(VariableDeclaration declaration, ITypeBinding typeBinding) {
      if (derives(typeBinding, ReferenceCountingBase.class)) {
        final SimpleName declarationName = declaration.getName();
        ASTNode parent = declaration.getParent();
        if (parent instanceof MethodDeclaration) {
          final MethodDeclaration node = (MethodDeclaration) parent;
          int lastMention = lastMention(declarationName, node.getBody());
          node.getBody().statements().add(lastMention + 1, newFreeRef(node.getAST(), declarationName, declaration.resolveBinding().getType()));
          logger.debug(String.format("Add freeRef for input parameter %s: %s to line %s",
              declarationName,
              typeBinding.getQualifiedName(),
              lastMention + 1
          ));
        } else if (parent instanceof LambdaExpression) {
          final LambdaExpression node = (LambdaExpression) parent;
          final ASTNode body = node.getBody();
          if (body instanceof Block) {
            int lastMention = lastMention(declarationName, (Block) body);
            ((Block) body).statements().add(lastMention + 1, newFreeRef(node.getAST(), declarationName, declaration.resolveBinding().getType()));
            logger.debug(String.format("Add freeRef for input parameter %s: %s to line %s",
                declarationName,
                typeBinding.getQualifiedName(),
                lastMention + 1
            ));
          } else {
            logger.warn(String.format("Cannot add freeRef for %s in %s : %s",
                declarationName,
                body.getClass(), body.toString().trim()
            ));
          }
        } else if (parent instanceof VariableDeclarationStatement) {
          parent = parent.getParent();
          if (parent instanceof Block) {
            final Block node = (Block) parent;
            int lastMention = lastMention(declarationName, node);
            node.statements().add(lastMention + 1, newFreeRef(node.getAST(), declarationName, declaration.resolveBinding().getType()));
            logger.debug(String.format("Add freeRef for input parameter %s: %s to line %s",
                declarationName,
                typeBinding.getQualifiedName(),
                lastMention + 1
            ));
          } else {
            logger.warn(String.format("Cannot add freeRef for %s (VariableDeclarationStatement) in %s : %s",
                declarationName,
                parent.getClass(), parent.toString().trim()
            ));
          }
        } else if (parent instanceof VariableDeclarationStatement) {
          parent = parent.getParent();
          if (parent instanceof Block) {
            final Block node = (Block) parent;
            int lastMention = lastMention(declarationName, node);
            node.statements().add(lastMention + 1, newFreeRef(node.getAST(), declarationName, declaration.resolveBinding().getType()));
            logger.debug(String.format("Add freeRef for input parameter %s: %s to line %s",
                declarationName,
                typeBinding.getQualifiedName(),
                lastMention + 1
            ));
          } else {
            logger.warn(String.format("Cannot add freeRef for %s (VariableDeclarationStatement) in %s : %s",
                declarationName,
                parent.getClass(), parent.toString().trim()
            ));
          }
        } else if (parent instanceof FieldDeclaration) {
          final ASTNode parent2 = parent.getParent();
          if (parent2 instanceof TypeDeclaration) {
            final TypeDeclaration typeDeclaration = (TypeDeclaration) parent2;
            final Optional<MethodDeclaration> freeMethod = Arrays.stream(typeDeclaration.getMethods()).filter(methodDeclaration -> methodDeclaration.getName().toString().equals("_free")).findFirst();
            if (freeMethod.isPresent()) {
              final AST ast = parent2.getAST();
              final MethodInvocation methodInvocation = ast.newMethodInvocation();
              methodInvocation.setName(ast.newSimpleName("freeRef"));
              methodInvocation.setExpression(ast.newSimpleName(declarationName.toString()));
              freeMethod.get().getBody().statements().add(ast.newExpressionStatement(methodInvocation));
            } else {
              logger.warn(String.format("Cannot add freeRef for %s::%s - no _free method",
                  typeDeclaration.getName().toString(),
                  declaration.getName().toString()
              ));
            }
          } else {
            logger.warn(String.format("Cannot add freeRef for %s (FieldDeclaration) in %s : %s",
                declarationName,
                parent2.getClass(), parent2.toString().trim()
            ));
          }
        } else {
          logger.warn(String.format("Cannot add freeRef for %s in %s : %s",
              declarationName,
              parent.getClass(), parent.toString().trim()
          ));
        }
      }
    }

    public ExpressionStatement newFreeRef(AST ast, SimpleName declarationName, ITypeBinding type) {
      if (type.isArray()) {
        final String qualifiedName = type.getElementType().getQualifiedName();
        final MethodInvocation methodInvocation = ast.newMethodInvocation();
        methodInvocation.setName(ast.newSimpleName("freeRefs"));
        methodInvocation.setExpression(newQualifiedName(ast, qualifiedName.split("\\.")));
        methodInvocation.arguments().add(ast.newSimpleName(declarationName.toString()));
        return ast.newExpressionStatement(methodInvocation);
      } else {
        final MethodInvocation methodInvocation = ast.newMethodInvocation();
        methodInvocation.setName(ast.newSimpleName("freeRef"));
        methodInvocation.setExpression(ast.newSimpleName(declarationName.toString()));
        return ast.newExpressionStatement(methodInvocation);
      }
    }

    @Override
    public void endVisit(MethodInvocation node) {
      final IMethodBinding methodBinding = node.resolveMethodBinding();
      if (null != methodBinding && modifyArg(methodBinding.getDeclaringClass())) {
        final List arguments = node.arguments();
        for (int i = 0; i < arguments.size(); i++) {
          Object next = arguments.get(i);
          if (next instanceof Expression) {
            if (next instanceof SimpleName) {
              final SimpleName name = (SimpleName) next;
              final ITypeBinding typeBinding = name.resolveTypeBinding();
              if (derives(typeBinding, ReferenceCountingBase.class)) {
                final AST ast = node.getAST();
                final MethodInvocation methodInvocation = ast.newMethodInvocation();
                methodInvocation.setName(ast.newSimpleName("addRef"));
                methodInvocation.setExpression(ast.newSimpleName(name.toString()));
                arguments.set(i, methodInvocation);
                logger.info(String.format("Argument addRef for %s: %s (%s) defined by %s", node.getName(), typeBinding.getQualifiedName(), name.toString(), methodBinding.getDeclaringClass().getQualifiedName()));
              }
            }
          }
        }
      }
    }

    public boolean modifyArg(ITypeBinding declaringClass) {
      return AutoCoder.toString(declaringClass.getPackage()).startsWith("com.simiacryptus");
    }

  }

  protected class InsertAddRefs extends ASTVisitor {

    @Override
    public void endVisit(ConstructorInvocation node) {
      final IMethodBinding methodBinding = node.resolveConstructorBinding();
      if (null != methodBinding) {
        final AST ast = node.getAST();
        ITypeBinding declaringClass = methodBinding.getDeclaringClass();
        if (methodsConsumeRefs(declaringClass) && node.arguments().size() > 0) {
          apply(ast, declaringClass, node.arguments(), methodBinding.getDeclaringClass().getName());
        }
      } else {
        logger.warn("Cannot resolve " + node);
      }
    }

    @Override
    public void endVisit(ClassInstanceCreation node) {
      final IMethodBinding methodBinding = node.resolveConstructorBinding();
      if (null != methodBinding) {
        final AST ast = node.getAST();
        ITypeBinding declaringClass = methodBinding.getDeclaringClass();
        if (methodsConsumeRefs(declaringClass) && node.arguments().size() > 0) {
          apply(ast, declaringClass, node.arguments(), methodBinding.getDeclaringClass().getName());
        }
      } else {
        logger.warn("Cannot resolve " + node);
      }
    }

    @Override
    public void endVisit(MethodInvocation node) {
      final IMethodBinding methodBinding = node.resolveMethodBinding();
      if (null != methodBinding) {
        ITypeBinding declaringClass = methodBinding.getDeclaringClass();
        if (methodsConsumeRefs(declaringClass)) {
          apply(node.getAST(), declaringClass, node.arguments(), node.getName().toString());
        }
      }
    }

    public void apply(AST ast, ITypeBinding declaringClass, List arguments, String methodName) {
      for (int i = 0; i < arguments.size(); i++) {
        Object next = arguments.get(i);
        if (next instanceof Expression) {
          if (next instanceof SimpleName) {
            final SimpleName name = (SimpleName) next;
            final ITypeBinding resolveTypeBinding = name.resolveTypeBinding();
            if (isRefCounted(resolveTypeBinding)) {
              arguments.set(i, addAddRef(name, resolveTypeBinding, ast));
              logger.info(String.format("Argument addRef for %s: %s (%s) defined by %s", methodName, resolveTypeBinding.getQualifiedName(), name.toString(), declaringClass.getQualifiedName()));
            }
          }
        }
      }
    }

    @NotNull
    public MethodInvocation addAddRef(SimpleName name, ITypeBinding type, AST ast) {
      if (type.isArray()) {
        final String qualifiedName = type.getElementType().getQualifiedName();
        final MethodInvocation methodInvocation = ast.newMethodInvocation();
        methodInvocation.setName(ast.newSimpleName("addRefs"));
        methodInvocation.setExpression(newQualifiedName(ast, qualifiedName.split("\\.")));
        methodInvocation.arguments().add(ast.newSimpleName(name.toString()));
        return methodInvocation;
      } else {
        final MethodInvocation methodInvocation = ast.newMethodInvocation();
        methodInvocation.setName(ast.newSimpleName("addRef"));
        methodInvocation.setExpression(ast.newSimpleName(name.toString()));
        return methodInvocation;
      }
    }

    public boolean methodsConsumeRefs(ITypeBinding declaringClass) {
      return AutoCoder.toString(declaringClass.getPackage()).startsWith("com.simiacryptus");
    }

  }

  public boolean isRefCounted(ITypeBinding resolveTypeBinding) {
    final ITypeBinding type;
    if (resolveTypeBinding.isArray()) {
      type = resolveTypeBinding.getElementType();
    } else {
      type = resolveTypeBinding;
    }
    return derives(type, ReferenceCountingBase.class);
  }

  protected class InsertMethods extends ASTVisitor {

    @Override
    public void endVisit(TypeDeclaration node) {
      if (derives(node.resolveBinding(), ReferenceCountingBase.class)) {
        final AST ast = node.getAST();
        final List declarations = node.bodyDeclarations();
        declarations.add(method_free(ast));
        declarations.add(method_addRef(ast, node.getName()));
        declarations.add(method_addRefs(ast, node.getName()));
        declarations.add(method_freeRefs(ast, node.getName()));
      }
      super.endVisit(node);
    }

    @NotNull
    public MethodDeclaration method_free(AST ast) {
      final MethodDeclaration methodDeclaration = ast.newMethodDeclaration();
      methodDeclaration.setName(ast.newSimpleName("_free"));
      methodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
      methodDeclaration.modifiers().add(annotation_override(ast));
      methodDeclaration.setBody(ast.newBlock());
      return methodDeclaration;
    }

    @NotNull
    public MethodDeclaration method_addRef(AST ast, SimpleName name) {
      final String fqTypeName = name.getFullyQualifiedName();
      final MethodDeclaration methodDeclaration = ast.newMethodDeclaration();
      methodDeclaration.setName(ast.newSimpleName("addRef"));
      methodDeclaration.setReturnType2(ast.newSimpleType(ast.newSimpleName(fqTypeName)));
      methodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
      methodDeclaration.modifiers().add(annotation_override(ast));
      final Block block = ast.newBlock();
      final CastExpression castExpression = ast.newCastExpression();
      castExpression.setType(ast.newSimpleType(ast.newSimpleName(fqTypeName)));
      final SuperMethodInvocation superMethodInvocation = ast.newSuperMethodInvocation();
      superMethodInvocation.setName(ast.newSimpleName("addRef"));
      castExpression.setExpression(superMethodInvocation);
      final ReturnStatement returnStatement = ast.newReturnStatement();
      returnStatement.setExpression(castExpression);
      block.statements().add(returnStatement);
      methodDeclaration.setBody(block);
      return methodDeclaration;
    }

    @NotNull
    public MethodDeclaration method_freeRefs(AST ast, SimpleName name) {
      final String fqTypeName = name.getFullyQualifiedName();
      final MethodDeclaration methodDeclaration = ast.newMethodDeclaration();
      methodDeclaration.setName(ast.newSimpleName("freeRefs"));

      methodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
      methodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));

      final SingleVariableDeclaration arg = ast.newSingleVariableDeclaration();
      arg.setType(arrayType(ast, fqTypeName));
      arg.setName(ast.newSimpleName("array"));
      methodDeclaration.parameters().add(arg);

      final MethodInvocation stream_invoke = ast.newMethodInvocation();
      stream_invoke.setExpression(newQualifiedName(ast, "java.util.Arrays".split("\\.")));
      stream_invoke.setName(ast.newSimpleName("stream"));
      stream_invoke.arguments().add(ast.newSimpleName("array"));

      final MethodInvocation filter_invoke = ast.newMethodInvocation();
      {
        filter_invoke.setExpression(stream_invoke);
        filter_invoke.setName(ast.newSimpleName("filter"));
        final LambdaExpression filter_lambda = ast.newLambdaExpression();
        final VariableDeclarationFragment variableDeclarationFragment = ast.newVariableDeclarationFragment();
        variableDeclarationFragment.setName(ast.newSimpleName("x"));
        filter_lambda.parameters().add(variableDeclarationFragment);
        final InfixExpression infixExpression = ast.newInfixExpression();
        infixExpression.setLeftOperand(ast.newSimpleName("x"));
        infixExpression.setOperator(InfixExpression.Operator.EQUALS);
        infixExpression.setRightOperand(ast.newNullLiteral());
        filter_lambda.setBody(infixExpression);
        filter_invoke.arguments().add(filter_lambda);
      }

      final MethodInvocation addref_invoke = ast.newMethodInvocation();
      {
        addref_invoke.setExpression(filter_invoke);
        addref_invoke.setName(ast.newSimpleName("forEach"));
        final ExpressionMethodReference body = ast.newExpressionMethodReference();
        body.setExpression(ast.newSimpleName(fqTypeName));
        body.setName(ast.newSimpleName("freeRef"));
        addref_invoke.arguments().add(body);
      }

      final Block block = ast.newBlock();
      block.statements().add(ast.newExpressionStatement(addref_invoke));
      methodDeclaration.setBody(block);
      return methodDeclaration;
    }

    @NotNull
    public MethodDeclaration method_addRefs(AST ast, SimpleName name) {
      final String fqTypeName = name.getFullyQualifiedName();
      final MethodDeclaration methodDeclaration = ast.newMethodDeclaration();
      methodDeclaration.setName(ast.newSimpleName("addRefs"));

      methodDeclaration.setReturnType2(arrayType(ast, fqTypeName));
      methodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
      methodDeclaration.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));

      final SingleVariableDeclaration arg = ast.newSingleVariableDeclaration();
      arg.setType(arrayType(ast, fqTypeName));
      arg.setName(ast.newSimpleName("array"));
      methodDeclaration.parameters().add(arg);

      final MethodInvocation stream_invoke = ast.newMethodInvocation();
      stream_invoke.setExpression(newQualifiedName(ast, "java.util.Arrays".split("\\.")));
      stream_invoke.setName(ast.newSimpleName("stream"));
      stream_invoke.arguments().add(ast.newSimpleName("array"));

      final MethodInvocation filter_invoke = ast.newMethodInvocation();
      {
        filter_invoke.setExpression(stream_invoke);
        filter_invoke.setName(ast.newSimpleName("filter"));
        final LambdaExpression filter_lambda = ast.newLambdaExpression();
        final VariableDeclarationFragment variableDeclarationFragment = ast.newVariableDeclarationFragment();
        variableDeclarationFragment.setName(ast.newSimpleName("x"));
        filter_lambda.parameters().add(variableDeclarationFragment);
        final InfixExpression infixExpression = ast.newInfixExpression();
        infixExpression.setLeftOperand(ast.newSimpleName("x"));
        infixExpression.setOperator(InfixExpression.Operator.EQUALS);
        infixExpression.setRightOperand(ast.newNullLiteral());
        filter_lambda.setBody(infixExpression);
        filter_invoke.arguments().add(filter_lambda);
      }

      final MethodInvocation addref_invoke = ast.newMethodInvocation();
      {
        addref_invoke.setExpression(filter_invoke);
        addref_invoke.setName(ast.newSimpleName("map"));
        final ExpressionMethodReference body = ast.newExpressionMethodReference();
        body.setExpression(ast.newSimpleName(fqTypeName));
        body.setName(ast.newSimpleName("addRef"));
        addref_invoke.arguments().add(body);
      }

      final MethodInvocation toArray_invoke = ast.newMethodInvocation();
      {
        toArray_invoke.setExpression(addref_invoke);
        toArray_invoke.setName(ast.newSimpleName("toArray"));
        final LambdaExpression filter_lambda = ast.newLambdaExpression();
        final VariableDeclarationFragment variableDeclarationFragment = ast.newVariableDeclarationFragment();
        variableDeclarationFragment.setName(ast.newSimpleName("x"));
        filter_lambda.parameters().add(variableDeclarationFragment);

        final ArrayCreation arrayCreation = ast.newArrayCreation();
        arrayCreation.setType(arrayType(ast, fqTypeName));
        arrayCreation.dimensions().add(ast.newSimpleName("x"));

        filter_lambda.setBody(arrayCreation);
        toArray_invoke.arguments().add(filter_lambda);
      }

      final Block block = ast.newBlock();
      final ReturnStatement returnStatement = ast.newReturnStatement();
      returnStatement.setExpression(toArray_invoke);
      block.statements().add(returnStatement);
      methodDeclaration.setBody(block);
      return methodDeclaration;
    }

  }
}
