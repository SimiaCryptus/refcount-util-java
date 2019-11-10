package com.simiacryptus.devutil;

import com.simiacryptus.lang.ref.ReferenceCountingBase;
import org.apache.commons.io.FileUtils;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.ProjectBuildingException;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatter;
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatterOptions;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public abstract class AutoCoder extends ASTVisitor {
  protected static final Logger logger = LoggerFactory.getLogger(AutoCoder.class);
  protected final SimpleMavenProject project;

  public AutoCoder(String pathname) {
    try {
      this.project = SimpleMavenProject.load(new File(pathname).getCanonicalPath());
    } catch (IOException | PlexusContainerException | DependencyResolutionException | ProjectBuildingException | ComponentLookupException e) {
      throw new RuntimeException(e);
    }

  }

  @Nonnull
  public abstract void apply();

  public int apply(BiFunction<CompilationUnit, File, ASTVisitor> visitor) {
    return project.parse().entrySet().stream().mapToInt(entry -> {
      File file = entry.getKey();
      CompilationUnit compilationUnit = entry.getValue();
      logger.debug(String.format("Scanning %s", file));
      final String prevSrc = compilationUnit.toString();
      compilationUnit.accept(visitor.apply(compilationUnit, file));
      final String finalSrc = compilationUnit.toString();
      if (!prevSrc.equals(finalSrc)) {
        logger.info("Changed: " + file);
        try {
          FileUtils.write(file, format(finalSrc), "UTF-8");
          return 1;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else {
        logger.debug("Not Touched: " + file);
        return 0;
      }
    }).sum();
  }

  public String format(String finalSrc) {
    final Document document = new Document();
    document.set(finalSrc);
    try {
      new DefaultCodeFormatter(formattingSettings())
          .format(
              CodeFormatter.K_COMPILATION_UNIT,
              finalSrc,
              0,
              finalSrc.length(),
              0,
              "\n")
          .apply(document);
    } catch (BadLocationException e) {
      throw new RuntimeException();
    }
    return document.get();
  }

  protected void removeMethods(TypeDeclaration node, String methodName) {
    for (final Iterator iterator = node.bodyDeclarations().iterator(); iterator.hasNext(); ) {
      final Object next = iterator.next();
      if (next instanceof MethodDeclaration) {
        final SimpleName name = ((MethodDeclaration) next).getName();
        if (name.toString().equals(methodName)) {
          iterator.remove();
        }
      }
    }
  }

  public <T> T setField(T astNode, String name, Object value) {
    try {
      getField(astNode.getClass(), name).set(astNode, value);
      return astNode;
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public Field getField(Class<?> nodeClass, String name) {
    final Field[] fields = nodeClass.getDeclaredFields();
    final Optional<Field> parent = Arrays.stream(fields).filter(x -> x.getName().equals(name)).findFirst();
    if (!parent.isPresent()) {
      final Class<?> superclass = nodeClass.getSuperclass();
      if (superclass != null) {
        return getField(superclass, name);
      } else {
        throw new AssertionError(String.format("Cannot find field %s", name));
      }
    }
    final Field field = parent.get();
    field.setAccessible(true);
    return field;
  }

  @NotNull
  protected DefaultCodeFormatterOptions formattingSettings() {
    final DefaultCodeFormatterOptions javaConventionsSettings = DefaultCodeFormatterOptions.getJavaConventionsSettings();
    javaConventionsSettings.align_with_spaces = true;
    javaConventionsSettings.tab_char = DefaultCodeFormatterOptions.SPACE;
    javaConventionsSettings.indentation_size = 2;
    return javaConventionsSettings;
  }

  protected boolean derives(@Nonnull ITypeBinding typeBinding, @Nonnull Class<ReferenceCountingBase> baseClass) {
    if (typeBinding.getBinaryName().equals(baseClass.getCanonicalName())) return true;
    if (typeBinding.getSuperclass() != null) return derives(typeBinding.getSuperclass(), baseClass);
    return false;
  }

  @NotNull
  public static String toString(IPackageBinding declaringClassPackage) {
    return Arrays.stream(declaringClassPackage.getNameComponents()).reduce((a, b) -> a + "." + b).get();
  }

  @NotNull
  public Name newQualifiedName(AST ast, String... path) {
    final SimpleName simpleName = ast.newSimpleName(path[path.length - 1]);
    if (path.length == 1) return simpleName;
    return ast.newQualifiedName(newQualifiedName(ast, Arrays.stream(path).limit(path.length - 1).toArray(i -> new String[i])), simpleName);
  }

  public ArrayType arrayType(AST ast, String fqTypeName) {
    return ast.newArrayType(ast.newSimpleType(ast.newSimpleName(fqTypeName)));
  }

  @NotNull
  public MarkerAnnotation annotation_override(AST ast) {
    final MarkerAnnotation annotation = ast.newMarkerAnnotation();
    annotation.setTypeName(ast.newSimpleName("Override"));
    return annotation;
  }

  public ExpressionStatement newLocalVariable(String identifier, Expression expression) {
    return newLocalVariable(identifier, expression, getType(expression));
  }

  public ExpressionStatement newLocalVariable(String identifier, Expression expression, Type simpleType) {
    AST ast = expression.getAST();
    final VariableDeclarationFragment variableDeclarationFragment = ast.newVariableDeclarationFragment();
    variableDeclarationFragment.setName(ast.newSimpleName(identifier));
    final VariableDeclarationExpression variableDeclarationExpression = ast.newVariableDeclarationExpression(variableDeclarationFragment);
    variableDeclarationExpression.setType(simpleType);
    final Assignment assignment = ast.newAssignment();
    assignment.setLeftHandSide(variableDeclarationExpression);
    assignment.setOperator(Assignment.Operator.ASSIGN);
    assignment.setRightHandSide((Expression) ASTNode.copySubtree(ast, expression));
    return ast.newExpressionStatement(assignment);
  }

  public Type getType(Expression expression) {
    return getType(expression.getAST(), expression.resolveTypeBinding().getName());
  }

  public Optional<MethodDeclaration> findMethod(TypeDeclaration typeDeclaration, String name) {
    return Arrays.stream(typeDeclaration.getMethods()).filter(methodDeclaration -> methodDeclaration.getName().toString().equals(name)).findFirst();
  }

  public static class Mention {
    public final Block block;
    public final int line;
    public final Statement statement;

    public Mention(Block block, int line, Statement statement) {
      this.block = block;
      this.line = line;
      this.statement = statement;
    }

    public boolean isReturn() {
      return statement instanceof ReturnStatement;
    }

    public boolean isComplexReturn() {
      if(!isReturn()) return false;
      return !(((ReturnStatement)statement).getExpression() instanceof Name);
    }

  }

  public List<Mention> lastMentions(Block block, IBinding variable) {
    final List statements = block.statements();
    final ArrayList<Mention> mentions = new ArrayList<>();
    Mention lastMention = null;
    for (int j = 0; j < statements.size(); j++) {
      final Statement statement = (Statement) statements.get(j);
      if (statement instanceof IfStatement) {
        final IfStatement ifStatement = (IfStatement) statement;
        final Statement thenStatement = ifStatement.getThenStatement();
        if (thenStatement instanceof Block) {
          mentions.addAll(lastMentions((Block) thenStatement, variable)
              .stream().filter(x -> x.isReturn()).collect(Collectors.toList()));
        } else if (thenStatement instanceof ReturnStatement && contains(thenStatement, variable)) {
          new Mention(block, j, thenStatement);
        }
        final Statement elseStatement = ifStatement.getElseStatement();
        if (elseStatement instanceof Block) {
          mentions.addAll(lastMentions((Block) elseStatement, variable)
              .stream().filter(x -> x.isReturn()).collect(Collectors.toList()));
        } else if (elseStatement instanceof ReturnStatement && contains(elseStatement, variable)) {
          new Mention(block, j, elseStatement);
        }
        if (contains(ifStatement.getExpression(), variable)) {
          lastMention = new Mention(block, j, ifStatement);
        }
      } else if (contains(statement, variable)) {
        lastMention = new Mention(block, j, statement);
      }
    }
    mentions.add(lastMention);
    return mentions;
  }

  private boolean contains(ASTNode expression, IBinding variableBinding) {
    final AtomicBoolean found = new AtomicBoolean(false);
    expression.accept(new ASTVisitor() {
      @Override
      public void endVisit(SimpleName node) {
        final IBinding binding = node.resolveBinding();
        if (null != binding && binding.equals(variableBinding)) found.set(true);
      }
    });
    return found.get();
  }

  public Type getType(@Nonnull AST ast, String name) {
    if(name.endsWith("[]")) {
      return ast.newArrayType(getType(ast, name.substring(0,name.length()-2)));
    } else if(name.contains("\\.")) {
      return ast.newSimpleType(newQualifiedName(ast, name.split("\\.")));
    } else {
      return ast.newSimpleType(ast.newSimpleName(name));
    }
  }

  public void delete(Statement parent) {
    final ASTNode parent1 = parent.getParent();
    if (parent1 instanceof Block) {
      final Block block = (Block) parent1;
      if (block.statements().size() == 1) {
        final ASTNode blockParent = block.getParent();
        if (blockParent instanceof Statement) {
          delete(parent);
          return;
        }
      }
    } else if (parent1 instanceof Statement) {
      delete((Statement) parent1);
      return;
    }
    parent.delete();
  }

  public class FileAstVisitor extends ASTVisitor {
    protected final CompilationUnit compilationUnit;
    protected final File file;

    public FileAstVisitor(CompilationUnit compilationUnit, File file) {
      this.compilationUnit = compilationUnit;
      this.file = file;
    }

    public String location(ASTNode node) {
      return String.format("(%s:%s)", file.getName(), compilationUnit.getLineNumber(node.getStartPosition()));
    }

  }

}
