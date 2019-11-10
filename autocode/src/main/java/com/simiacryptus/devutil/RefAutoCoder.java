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
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

public class RefAutoCoder extends AutoCoder {

  private boolean verbose = true;
  private boolean addRefcounting = true;
  private Random random = new Random();

  public RefAutoCoder(String pathname) {
    super(pathname);
  }

  @Override
  @Nonnull
  public void apply() {
    if (isVerbose()) apply((cu, file) -> new LogNodes(cu, file));
    apply((cu, file) -> new RemoveRefs(cu, file));
    while(apply((cu, file) -> new InlineRefs(cu, file))>0) {};
    if (isAddRefcounting()) {
      apply((cu, file) -> new InsertMethods(cu, file));
      apply((cu, file) -> new InsertAddRefs(cu, file));
      apply((cu, file) -> new ModifyFieldSets(cu, file));
      apply((cu, file) -> new InsertFreeRefs(cu, file));
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

  public boolean isRefCounted(ITypeBinding resolveTypeBinding) {
    final ITypeBinding type;
    if (resolveTypeBinding.isArray()) {
      type = resolveTypeBinding.getElementType();
    } else {
      type = resolveTypeBinding;
    }
    return derives(type, ReferenceCountingBase.class);
  }

  protected class LogNodes extends FileAstVisitor {

    private LogNodes(CompilationUnit compilationUnit, File file) {
      super(compilationUnit,file);
    }

    @Override
    public void preVisit(ASTNode node) {
      logger.info(String.format("Previsit: %s at (%s:%s)", node.getClass(), file.getName(), compilationUnit.getLineNumber(node.getStartPosition())));
    }
  }

  protected class ModifyFieldSets extends FileAstVisitor {

    private ModifyFieldSets(CompilationUnit compilationUnit, File file) {
      super(compilationUnit,file);
    }
    @Override
    public void endVisit(Assignment node) {
      if(node.getLeftHandSide() instanceof FieldAccess) {
        final FieldAccess fieldAccess = (FieldAccess) node.getLeftHandSide();
        final ASTNode parent = node.getParent();
        if(parent instanceof ExpressionStatement) {
          final ASTNode parent2 = parent.getParent();
          if(parent2 instanceof Block) {
            final Block block = (Block) parent2;
            final int lineNumber = block.statements().indexOf(parent);
            final Expression rightHandSide = node.getRightHandSide();
            final AST ast = node.getAST();
            if(rightHandSide instanceof Name) {
              block.statements().add(lineNumber, freeRefStatement(ast, fieldAccess));
              node.setRightHandSide(wrapAddRef(rightHandSide));
              logger.info("Simple field-set statement at line " + lineNumber);
            } else {
              final Block exchangeBlock = ast.newBlock();

              final String identifier = randomIdentifier();
              exchangeBlock.statements().add(newLocalVariable(identifier, rightHandSide, getType(ast, fieldAccess.resolveFieldBinding().getType().getName())));
              exchangeBlock.statements().add(freeRefStatement(ast, fieldAccess));

              final Assignment assignment = ast.newAssignment();
              assignment.setLeftHandSide((Expression) ASTNode.copySubtree(ast, fieldAccess));
              assignment.setOperator(Assignment.Operator.ASSIGN);
              assignment.setRightHandSide(wrapAddRef(ast.newSimpleName(identifier)));
              exchangeBlock.statements().add(ast.newExpressionStatement(assignment));

              block.statements().set(lineNumber, exchangeBlock);
              logger.info("Complex field-set statement at line " + lineNumber);
            }
          } else {
            logger.info(String.format("Non-block field-set statement: %s (%s)", parent.getClass(), parent));
          }
        } else {
          logger.info(String.format("Non-ExpressionStatement field-set statement: %s (%s)", parent.getClass(), parent));
        }
      }
      super.endVisit(node);
    }

    @NotNull
    public IfStatement freeRefStatement(AST ast, FieldAccess fieldAccess) {
      final IfStatement ifStatement = ast.newIfStatement();
      final InfixExpression infixExpression = ast.newInfixExpression();
      infixExpression.setLeftOperand(ast.newNullLiteral());
      infixExpression.setRightOperand((Expression) ASTNode.copySubtree(ast, fieldAccess));
      infixExpression.setOperator(InfixExpression.Operator.NOT_EQUALS);
      ifStatement.setExpression(infixExpression);
      ifStatement.setThenStatement(ast.newExpressionStatement(getFreeRefInvocation(fieldAccess, ast)));
      return ifStatement;
    }

    @NotNull
    public MethodInvocation getFreeRefInvocation(Expression fieldAccess, AST ast) {
      final ITypeBinding type = fieldAccess.resolveTypeBinding();
      if(type.isArray()) {
        final String qualifiedName = type.getElementType().getQualifiedName();
        final MethodInvocation methodInvocation = ast.newMethodInvocation();
        methodInvocation.setName(ast.newSimpleName("freeRefs"));
        methodInvocation.setExpression(newQualifiedName(ast, qualifiedName.split("\\.")));
        methodInvocation.arguments().add(ASTNode.copySubtree(ast, fieldAccess));
        return methodInvocation;
      } else {
        final MethodInvocation invocation = ast.newMethodInvocation();
        invocation.setExpression((Expression) ASTNode.copySubtree(ast, fieldAccess));
        invocation.setName(ast.newSimpleName("freeRef"));
        return invocation;
      }
    }
    @NotNull
    public Expression wrapAddRef(Expression expression) {
      final ITypeBinding type = expression.resolveTypeBinding();
      AST ast = expression.getAST();
      if(null == type) {
        logger.warn(String.format("%s - Cannot wrap with addRef (Unresolvable): %s",
            location(expression),
            expression.getClass(), expression.toString().trim()
        ));
        return expression;
      } else if(type.isArray()) {
        final String qualifiedName = type.getElementType().getQualifiedName();
        final MethodInvocation methodInvocation = ast.newMethodInvocation();
        methodInvocation.setName(ast.newSimpleName("addRefs"));
        methodInvocation.setExpression(newQualifiedName(ast, qualifiedName.split("\\.")));
        methodInvocation.arguments().add(ASTNode.copySubtree(ast, expression));
        return methodInvocation;
      } else {
        final MethodInvocation methodInvocation = ast.newMethodInvocation();
        methodInvocation.setName(ast.newSimpleName("addRef"));
        methodInvocation.setExpression((Expression) ASTNode.copySubtree(ast, expression));
        return methodInvocation;
      }
    }
  }

  protected class RemoveRefs extends FileAstVisitor {

    private RemoveRefs(CompilationUnit compilationUnit, File file) {
      super(compilationUnit,file);
    }
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
        final AST ast = node.getAST();
        final Expression subject;
        if(Arrays.asList("addRefs", "freeRefs").contains(methodName)) {
          subject = (Expression) ASTNode.copySubtree(ast, (ASTNode) node.arguments().get(0));
        } else {
          subject = (Expression) ASTNode.copySubtree(ast, node.getExpression());
        }
        if (parent instanceof MethodInvocation) {
          final List arguments = ((MethodInvocation) parent).arguments();
          final int index = arguments.indexOf(node);
          arguments.set(index, subject);
          logger.debug(String.format("%s removed as argument %s of %s", methodName, index, parent));
        } else if (parent instanceof ExpressionStatement) {
          delete((ExpressionStatement)parent);
          logger.debug(String.format("%s removed from %s", methodName, parent));
        } else if (parent instanceof ClassInstanceCreation) {
          final List arguments = ((ClassInstanceCreation) parent).arguments();
          final int index = arguments.indexOf(node);
          arguments.set(index, subject);
          logger.debug(String.format("%s removed as argument %s of %s", methodName, index, parent));
        } else if (parent instanceof VariableDeclarationFragment) {
          ((VariableDeclarationFragment) parent).setInitializer(subject);
          logger.debug(String.format("%s removed at %s", methodName, location(parent)));
        } else if (parent instanceof Assignment) {
          ((Assignment) parent).setRightHandSide(subject);
          logger.debug(String.format("%s removed at %s", methodName, location(parent)));
        } else {
          logger.warn(String.format("%s - Cannot remove %s called in %s: %s", location(parent), methodName, parent.getClass(), parent));
        }
      }
    }

  }

  protected class InlineRefs extends FileAstVisitor {

    private InlineRefs(CompilationUnit compilationUnit, File file) {
      super(compilationUnit,file);
    }

    @Override
    public void endVisit(Block node) {
      if(node.statements().size() == 1 && node.getParent() instanceof Block) {
        final Block parent = (Block) node.getParent();
        parent.statements().set(parent.statements().indexOf(node),
            ASTNode.copySubtree(node.getAST(), (ASTNode) node.statements().get(0)));
      }
    }

    @Override
    public void endVisit(Assignment node) {
      Statement previousStatement = previousStatement(node);
      if(previousStatement != null) {
        if(previousStatement instanceof VariableDeclarationStatement) {
          final List fragments = ((VariableDeclarationStatement) previousStatement).fragments();
          if(1 == fragments.size()) {
            final VariableDeclarationFragment fragment = (VariableDeclarationFragment) fragments.get(0);
            if(fragment.getName().toString().equals(node.getRightHandSide().toString())) {
              logger.debug(String.format("Inlining %s at %s", fragment.getName(), location(node)));
              node.setRightHandSide((Expression) ASTNode.copySubtree(node.getAST(), fragment.getInitializer()));
              previousStatement.delete();
            } else {
              logger.warn(String.format("%s previous variable %s is not used in %s", location(node), fragment.getName(), node.getRightHandSide()));
            }
          } else {
            logger.warn(String.format("%s previous variable has multiple fragments", location(node)));
          }
        } else {
          logger.warn(String.format("%s previous statement is %s", location(node), previousStatement.getClass().getSimpleName()));
        }
      }
    }

    @Nullable
    public Statement previousStatement(@Nonnull ASTNode node) {
      if(node instanceof Statement) {
        final ASTNode statementParent = node.getParent();
        if(statementParent instanceof Block) {
          final List statements = ((Block) statementParent).statements();
          final int statementNumber = statements.indexOf(node);
          if(statementNumber > 0) {
            return  (Statement)statements.get(statementNumber - 1);
          } else {
            logger.warn(String.format("No previous statement for %s at %s", node.getClass().getSimpleName(), location(node)));
            return null;
          }
        } else {
          logger.warn(String.format("No previous statement for %s at %s", node.getClass().getSimpleName(), location(node)));
          return null;
        }
      } else {
        final ASTNode parent = node.getParent();
        if (null == parent) {
          logger.warn("No previous statement for %s at %s", node.getClass().getSimpleName(), location(node));
          return null;
        } else {
          return previousStatement(parent);
        }
      }
    }

    @Override
    public void endVisit(ReturnStatement node) {
      if(node.getExpression() instanceof Name) {
        Statement previousStatement = previousStatement(node);
        if(previousStatement != null) {
          if(previousStatement instanceof VariableDeclarationStatement) {
            final List fragments = ((VariableDeclarationStatement) previousStatement).fragments();
            if(1 == fragments.size()) {
              final VariableDeclarationFragment fragment = (VariableDeclarationFragment) fragments.get(0);
              if(fragment.getName().toString().equals(node.getExpression().toString())) {
                logger.debug(String.format("Inlining %s at %s", fragment.getName(), location(node)));
                node.setExpression((Expression) ASTNode.copySubtree(node.getAST(), fragment.getInitializer()));
                previousStatement.delete();
              }
            }
          } else {
            logger.debug(String.format("Cannot inline - Previous statement is %s at %s", previousStatement.getClass().getSimpleName(), location(node)));
          }
        } else {
          logger.debug(String.format("Cannot inline - No previous statement at %s", location(node)));
        }
      }
    }
  }

  protected class InsertFreeRefs extends FileAstVisitor {

    private InsertFreeRefs(CompilationUnit compilationUnit, File file) {
      super(compilationUnit,file);
    }

    @Override
    public void endVisit(VariableDeclarationFragment declaration) {
      final ASTNode parent = declaration.getParent();
      if (parent instanceof VariableDeclarationStatement) {
        final ITypeBinding typeBinding = ((VariableDeclarationStatement) parent).getType().resolveBinding();
        if(null != typeBinding) {
          addFreeRef(declaration, typeBinding);
        } else {
          logger.warn(String.format("%s - Cannot resolve type of %s", location(parent), parent));
        }
      } else if (parent instanceof VariableDeclarationExpression) {
        final ITypeBinding typeBinding = ((VariableDeclarationExpression) parent).getType().resolveBinding();
        if(null != typeBinding) {
          addFreeRef(declaration, typeBinding);
        } else {
          logger.warn(String.format("%s - Cannot resolve type of %s", location(parent), parent));
        }
      } else if (parent instanceof FieldDeclaration) {
        final ITypeBinding typeBinding = ((FieldDeclaration) parent).getType().resolveBinding();
        if(null != typeBinding) {
          addFreeRef(declaration, typeBinding);
        } else {
          logger.warn(String.format("%s - Cannot resolve type of %s", location(parent), parent));
        }
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

    public void addFreeRef(@Nonnull VariableDeclaration declaration, @Nonnull ITypeBinding typeBinding) {
      final AST ast = declaration.getAST();
      if (derives(typeBinding, ReferenceCountingBase.class)) {
        final SimpleName name = declaration.getName();
        ASTNode parent = declaration.getParent();
        if (parent instanceof MethodDeclaration) {
          final MethodDeclaration node = (MethodDeclaration) parent;
          addFreeRef(declaration, typeBinding, name, node.getBody(), node.getAST());
        } else if (parent instanceof LambdaExpression) {
          final LambdaExpression node = (LambdaExpression) parent;
          final ASTNode lambdaParent = node.getParent();
          if(!isStream(lambdaParent)) {
            final ASTNode body = node.getBody();
            if (body instanceof Block) {
              final Block block = (Block) body;
              List<Mention> lastMentions = lastMentions(block, name.resolveBinding());
              lastMentions.stream().filter(x->!x.isReturn())
                  .forEach(insertAddRef(declaration, typeBinding, name, block, ast));
              lastMentions.stream().filter(x->x.isComplexReturn())
                  .forEach(insertAddRef_ComplexReturn(name, ast));
            } else {
              logger.warn(String.format("%s - Cannot add freeRef for %s in %s : %s",
                  location(declaration),
                  name,
                  body.getClass(), body.toString().trim()
              ));
            }
          }
        } else if (parent instanceof VariableDeclarationStatement) {
          parent = parent.getParent();
          if (parent instanceof Block) {
            addFreeRef(declaration, typeBinding, name, (Block) parent, parent.getAST());
          } else {
            logger.warn(String.format("%s - Cannot add freeRef for %s (VariableDeclarationStatement) in %s : %s",
                location(declaration),
                name,
                parent.getClass(), parent.toString().trim()
            ));
          }
        } else if (parent instanceof FieldDeclaration) {
          final ASTNode fieldParent = parent.getParent();
          if (fieldParent instanceof TypeDeclaration) {
            final TypeDeclaration typeDeclaration = (TypeDeclaration) fieldParent;
            final Optional<MethodDeclaration> freeMethodOpt = findMethod(typeDeclaration, "_free");
            if (freeMethodOpt.isPresent()) {
              final MethodDeclaration freeMethod = freeMethodOpt.get();
              final ExpressionStatement expressionStatement = freeRefStatement(ast, name);
              final Block body = freeMethod.getBody();
              logger.info(String.format("%s - Adding freeRef for %s::%s to %s - %s ++ %s",
                  location(declaration),
                  typeDeclaration.getName(),
                  declaration.getName(),
                  location(freeMethod),
                  body,
                  expressionStatement
              ));
              body.statements().add(0,expressionStatement);
            } else {
              logger.warn(String.format("%s - Cannot add freeRef for %s::%s - no _free method",
                  location(declaration),
                  typeDeclaration.getName(),
                  declaration.getName()
              ));
            }
          } else {
            logger.warn(String.format("%s - Cannot add freeRef for %s (FieldDeclaration) in %s : %s",
                location(declaration),
                name,
                fieldParent.getClass(), fieldParent.toString().trim()
            ));
          }
        } else {
          logger.warn(String.format("%s - Cannot add freeRef for %s in %s : %s",
              location(declaration),
              name,
              parent.getClass(), parent.toString().trim()
          ));
        }
      }
    }

    public boolean isStream(ASTNode lambdaParent) {
      final boolean isStream;
      if(lambdaParent instanceof MethodInvocation) {
        final ITypeBinding targetClass = ((MethodInvocation) lambdaParent).getExpression().resolveTypeBinding();
        isStream = targetClass.getQualifiedName().startsWith("java.util.stream.Stream");
      } else {
        isStream = false;
      }
      return isStream;
    }

    public void addFreeRef(VariableDeclaration declaration, ITypeBinding typeBinding, SimpleName name, Block body, AST ast) {
      final List<Mention> lastMentions = lastMentions(body, name.resolveBinding());
      lastMentions.stream().filter(x->!x.isReturn())
          .forEach(insertAddRef(declaration, typeBinding, name, body, ast));
      lastMentions.stream().filter(x->x.isComplexReturn())
          .forEach(insertAddRef_ComplexReturn(name, ast));
    }

    @NotNull
    public Consumer<Mention> insertAddRef_ComplexReturn(SimpleName name, AST ast) {
      return mention->{
        final ReturnStatement returnStatement = (ReturnStatement) mention.statement;
        final String identifier = randomIdentifier();
        final List statements = mention.block.statements();
        statements.add(mention.line, newLocalVariable(identifier, returnStatement.getExpression()));
        statements.add(mention.line+1, freeRefStatement(ast, ast.newSimpleName(name.getIdentifier())));
        final ReturnStatement newReturnStatement = ast.newReturnStatement();
        newReturnStatement.setExpression(ast.newSimpleName(identifier));
        statements.set(mention.line+2, newReturnStatement);
      };
    }

    @NotNull
    public Consumer<Mention> insertAddRef(VariableDeclaration declaration, ITypeBinding typeBinding, SimpleName declarationName, Block body, AST ast) {
      return lastMention->{
        body.statements().add(lastMention.line + 1, newFreeRef(ast, declarationName, declaration.resolveBinding().getType()));
        logger.debug(String.format("Add freeRef for input parameter %s: %s to line %s",
            declarationName,
            typeBinding.getQualifiedName(),
            lastMention.line + 1
        ));
      };
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
                logger.info(String.format("Argument addRef for %s: %s (%s) defined by %s", node.getName(), typeBinding.getQualifiedName(), name, methodBinding.getDeclaringClass().getQualifiedName()));
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

  @NotNull
  public String randomIdentifier() {
    return "temp" + Long.toString(Math.abs(random.nextLong())).substring(0,4);
  }

  @NotNull
  public ExpressionStatement freeRefStatement(AST ast, SimpleName declarationName) {
    final MethodInvocation methodInvocation = ast.newMethodInvocation();
    methodInvocation.setName(ast.newSimpleName("freeRef"));
    methodInvocation.setExpression(ast.newSimpleName(declarationName.toString()));
    return ast.newExpressionStatement(methodInvocation);
  }

  protected class InsertAddRefs extends FileAstVisitor {

    private InsertAddRefs(CompilationUnit compilationUnit, File file) {
      super(compilationUnit,file);
    }
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
              logger.info(String.format("Argument addRef for %s: %s (%s) defined by %s", methodName, resolveTypeBinding.getQualifiedName(), name, declaringClass.getQualifiedName()));
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

  protected class InsertMethods extends FileAstVisitor {

    public InsertMethods(CompilationUnit cu, File file) {
      super(cu, file);
    }

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
