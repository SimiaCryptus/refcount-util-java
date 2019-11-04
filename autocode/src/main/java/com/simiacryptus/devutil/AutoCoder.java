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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

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

  public void apply(BiFunction<CompilationUnit, File, ASTVisitor> visitor) {
    project.parse().forEach((File file, CompilationUnit compilationUnit) -> {
      logger.debug(String.format("Scanning %s", file));
      final String prevSrc = compilationUnit.toString();
      compilationUnit.accept(visitor.apply(compilationUnit, file));
      final String finalSrc = compilationUnit.toString();
      if (!prevSrc.equals(finalSrc)) {
        logger.info("Changed: " + file);
        try {
          FileUtils.write(file, format(finalSrc), "UTF-8");
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else {
        logger.debug("Not Touched: " + file);
      }
    });
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
      if(superclass != null) {
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

  protected boolean derives(ITypeBinding typeBinding, Class<ReferenceCountingBase> baseClass) {
    if (typeBinding.getBinaryName().equals(baseClass.getCanonicalName())) return true;
    if (typeBinding.getSuperclass() != null) return derives(typeBinding.getSuperclass(), baseClass);
    return false;
  }

  @NotNull
  public static String toString(IPackageBinding declaringClassPackage) {
    return Arrays.stream(declaringClassPackage.getNameComponents()).reduce((a, b)->a+"."+b).get();
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

  public int lastMention(SimpleName declarationName, Block body) {
    final List statements = body.statements();
    final IBinding variableBinding = declarationName.resolveBinding();
    int lastMention = -1;
    for (int j = 0; j < statements.size(); j++) {
      if (contains((ASTNode) statements.get(j), variableBinding)) {
        lastMention = j;
      }
    }
    return lastMention;
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

}
