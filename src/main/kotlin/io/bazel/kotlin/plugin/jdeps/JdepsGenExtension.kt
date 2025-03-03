package io.bazel.kotlin.plugin.jdeps

import com.google.devtools.build.lib.view.proto.Deps
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.bazel.kotlin.builder.utils.jars.JarOwner
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.container.StorageComponentContainer
import org.jetbrains.kotlin.container.useInstance
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaPropertyDescriptor
import org.jetbrains.kotlin.load.java.sources.JavaSourceElement
import org.jetbrains.kotlin.load.java.structure.impl.classFiles.BinaryJavaClass
import org.jetbrains.kotlin.load.java.structure.impl.classFiles.BinaryJavaField
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.load.kotlin.VirtualFileKotlinClass
import org.jetbrains.kotlin.load.kotlin.getContainingKotlinJvmBinaryClass
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.FunctionImportedFromObject
import org.jetbrains.kotlin.resolve.PropertyImportedFromObject
import org.jetbrains.kotlin.resolve.calls.checkers.CallChecker
import org.jetbrains.kotlin.resolve.calls.checkers.CallCheckerContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.FakeCallableDescriptorForObject
import org.jetbrains.kotlin.resolve.checkers.DeclarationChecker
import org.jetbrains.kotlin.resolve.checkers.DeclarationCheckerContext
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.typeUtil.supertypes
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Paths

/**
 * Kotlin compiler extension that tracks classes (and corresponding classpath jars) needed to
 * compile current kotlin target. Tracked data should include all classes whose changes could
 * affect target's compilation out : direct class dependencies (i.e. external classes directly
 * used), but also their superclass, interfaces, etc.
 * The primary use of this extension is to improve Kotlin module compilation avoidance in build
 * systems (like Buck).
 *
 * Tracking of classes and their ancestors is done via modules and class
 * descriptors that got generated during analysis/resolve phase of Kotlin compilation.
 *
 * Note: annotation processors dependencies may need to be tracked separately (and may not need
 * per-class ABI change tracking)
 *
 * @param project the current compilation project
 * @param configuration the current compilation configuration
 */
class JdepsGenExtension(
  val configuration: CompilerConfiguration,
) :
  AnalysisHandlerExtension, StorageComponentContainerContributor {

  companion object {

    /**
     * Returns the path of the jar archive file corresponding to the provided descriptor.
     *
     * @descriptor the descriptor, typically obtained from compilation analyze phase
     * @return the path corresponding to the JAR where this class was loaded from, or null.
     */
    fun getClassCanonicalPath(descriptor: DeclarationDescriptorWithSource): String? {
      return when (val sourceElement: SourceElement = descriptor.source) {
        is JavaSourceElement ->
          if (sourceElement.javaElement is BinaryJavaClass) {
            (sourceElement.javaElement as BinaryJavaClass).virtualFile.canonicalPath
          } else if (sourceElement.javaElement is BinaryJavaField) {
            val containingClass = (sourceElement.javaElement as BinaryJavaField).containingClass
            if (containingClass is BinaryJavaClass) {
              containingClass.virtualFile.canonicalPath
            } else {
              null
            }
          } else {
            // Ignore Java source local to this module.
            null
          }
        is KotlinJvmBinarySourceElement ->
          (sourceElement.binaryClass as VirtualFileKotlinClass).file.canonicalPath
        else -> null
      }
    }

    fun getClassCanonicalPath(typeConstructor: TypeConstructor): String? {
      return (typeConstructor.declarationDescriptor as? DeclarationDescriptorWithSource)?.let {
        getClassCanonicalPath(
          it,
        )
      }
    }
  }

  private val explicitClassesCanonicalPaths = mutableSetOf<String>()
  private val implicitClassesCanonicalPaths = mutableSetOf<String>()

  override fun registerModuleComponents(
    container: StorageComponentContainer,
    platform: TargetPlatform,
    moduleDescriptor: ModuleDescriptor,
  ) {
    container.useInstance(
      ClasspathCollectingChecker(explicitClassesCanonicalPaths, implicitClassesCanonicalPaths),
    )
  }

  class ClasspathCollectingChecker(
    private val explicitClassesCanonicalPaths: MutableSet<String>,
    private val implicitClassesCanonicalPaths: MutableSet<String>,
  ) : CallChecker, DeclarationChecker {

    override fun check(
      resolvedCall: ResolvedCall<*>,
      reportOn: PsiElement,
      context: CallCheckerContext,
    ) {
      when (val resultingDescriptor = resolvedCall.resultingDescriptor) {
        is FunctionImportedFromObject -> {
          collectTypeReferences(resultingDescriptor.containingObject.defaultType)
        }
        is PropertyImportedFromObject -> {
          collectTypeReferences(resultingDescriptor.containingObject.defaultType)
        }
        is JavaMethodDescriptor -> {
          getClassCanonicalPath(
            (resultingDescriptor.containingDeclaration as ClassDescriptor).typeConstructor,
          )?.let { explicitClassesCanonicalPaths.add(it) }
        }
        is FunctionDescriptor -> {
          resultingDescriptor.returnType?.let {
            collectTypeReferences(it, isExplicit = false, collectTypeArguments = false)
          }
          resultingDescriptor.valueParameters.forEach { valueParameter ->
            collectTypeReferences(valueParameter.type, isExplicit = false)
          }
          val virtualFileClass =
            resultingDescriptor.getContainingKotlinJvmBinaryClass() as? VirtualFileKotlinClass
              ?: return
          explicitClassesCanonicalPaths.add(virtualFileClass.file.path)
        }
        is ParameterDescriptor -> {
          getClassCanonicalPath(resultingDescriptor)?.let { explicitClassesCanonicalPaths.add(it) }
        }
        is FakeCallableDescriptorForObject -> {
          collectTypeReferences(resultingDescriptor.type)
        }
        is JavaPropertyDescriptor -> {
          getClassCanonicalPath(resultingDescriptor)?.let { explicitClassesCanonicalPaths.add(it) }
        }
        is PropertyDescriptor -> {
          when (resultingDescriptor.containingDeclaration) {
            is ClassDescriptor -> collectTypeReferences(
              (resultingDescriptor.containingDeclaration as ClassDescriptor).defaultType,
            )
            else -> {
              val virtualFileClass =
                (resultingDescriptor).getContainingKotlinJvmBinaryClass() as? VirtualFileKotlinClass
                  ?: return
              explicitClassesCanonicalPaths.add(virtualFileClass.file.path)
            }
          }
          collectTypeReferences(resultingDescriptor.type, isExplicit = false)
        }
        else -> return
      }
    }

    override fun check(
      declaration: KtDeclaration,
      descriptor: DeclarationDescriptor,
      context: DeclarationCheckerContext,
    ) {
      when (descriptor) {
        is ClassDescriptor -> {
          descriptor.typeConstructor.supertypes.forEach {
            collectTypeReferences(it)
          }
        }
        is FunctionDescriptor -> {
          descriptor.returnType?.let { collectTypeReferences(it) }
          descriptor.valueParameters.forEach { valueParameter ->
            collectTypeReferences(valueParameter.type)
          }
          descriptor.annotations.forEach { annotation ->
            collectTypeReferences(annotation.type)
          }
          descriptor.extensionReceiverParameter?.value?.type?.let {
            collectTypeReferences(it)
          }
        }
        is PropertyDescriptor -> {
          collectTypeReferences(descriptor.type)
          descriptor.annotations.forEach { annotation ->
            collectTypeReferences(annotation.type)
          }
          descriptor.backingField?.annotations?.forEach { annotation ->
            collectTypeReferences(annotation.type)
          }
        }
        is LocalVariableDescriptor -> {
          collectTypeReferences(descriptor.type)
        }
      }
    }

    /**
     * Records direct and indirect references for a given type. Direct references are explicitly
     * used in the code, e.g: a type declaration or a generic type declaration. Indirect references
     * are other types required for compilation such as supertypes and interfaces of those explicit
     * types.
     */
    private fun collectTypeReferences(
      kotlinType: KotlinType,
      isExplicit: Boolean = true,
      collectTypeArguments: Boolean = true,
      visitedKotlinTypes: MutableSet<Pair<KotlinType, Boolean>> = mutableSetOf(),
    ) {
      val kotlintTypeAndIsExplicit = Pair(kotlinType, isExplicit)
      if (!visitedKotlinTypes.contains(kotlintTypeAndIsExplicit)) {
        visitedKotlinTypes.add(kotlintTypeAndIsExplicit)

        if (isExplicit) {
          getClassCanonicalPath(kotlinType.constructor)?.let {
            explicitClassesCanonicalPaths.add(it)
          }
        } else {
          getClassCanonicalPath(kotlinType.constructor)?.let {
            implicitClassesCanonicalPaths.add(it)
          }
        }

        kotlinType.supertypes().forEach { supertype ->
          collectTypeReferences(
            supertype,
            isExplicit = false,
            collectTypeArguments = collectTypeArguments,
            visitedKotlinTypes,
          )
        }

        if (collectTypeArguments) {
          kotlinType.arguments.map { it.type }.forEach { typeArgument ->
            collectTypeReferences(
              typeArgument,
              isExplicit = isExplicit,
              collectTypeArguments = true,
              visitedKotlinTypes = visitedKotlinTypes,
            )
          }
        }
      }
    }
  }

  override fun analysisCompleted(
    project: Project,
    module: ModuleDescriptor,
    bindingTrace: BindingTrace,
    files: Collection<KtFile>,
  ): AnalysisResult? {
    val directDeps = configuration.getList(JdepsGenConfigurationKeys.DIRECT_DEPENDENCIES)
    val targetLabel = configuration.getNotNull(JdepsGenConfigurationKeys.TARGET_LABEL)
    val explicitDeps = createDepsMap(explicitClassesCanonicalPaths)

    doWriteJdeps(directDeps, targetLabel, explicitDeps)

    doStrictDeps(configuration, targetLabel, directDeps, explicitDeps)

    return super.analysisCompleted(project, module, bindingTrace, files)
  }

  /**
   * Returns a map of jars to classes loaded from those jars.
   */
  private fun createDepsMap(classes: Set<String>): Map<String, List<String>> {
    val jarsToClasses = mutableMapOf<String, MutableList<String>>()
    classes.forEach {
      val parts = it.split("!/")
      val jarPath = parts[0]
      if (jarPath.endsWith(".jar")) {
        jarsToClasses.computeIfAbsent(jarPath) { ArrayList() }.add(parts[1])
      }
    }
    return jarsToClasses
  }

  private fun doWriteJdeps(
    directDeps: MutableList<String>,
    targetLabel: String,
    explicitDeps: Map<String, List<String>>,
  ) {
    val implicitDeps = createDepsMap(implicitClassesCanonicalPaths)

    // Build and write out deps.proto
    val jdepsOutput = configuration.getNotNull(JdepsGenConfigurationKeys.OUTPUT_JDEPS)

    val rootBuilder = Deps.Dependencies.newBuilder()
    rootBuilder.success = true
    rootBuilder.ruleLabel = targetLabel

    val unusedDeps = directDeps.subtract(explicitDeps.keys)
    unusedDeps.forEach { jarPath ->
      val dependency = Deps.Dependency.newBuilder()
      dependency.kind = Deps.Dependency.Kind.UNUSED
      dependency.path = jarPath
      rootBuilder.addDependency(dependency)
    }

    explicitDeps.forEach { (jarPath, _) ->
      val dependency = Deps.Dependency.newBuilder()
      dependency.kind = Deps.Dependency.Kind.EXPLICIT
      dependency.path = jarPath
      rootBuilder.addDependency(dependency)
    }

    implicitDeps.keys.subtract(explicitDeps.keys).forEach {
      val dependency = Deps.Dependency.newBuilder()
      dependency.kind = Deps.Dependency.Kind.IMPLICIT
      dependency.path = it
      rootBuilder.addDependency(dependency)
    }

    BufferedOutputStream(File(jdepsOutput).outputStream()).use {
      it.write(rootBuilder.build().toByteArray())
    }
  }

  private fun doStrictDeps(
    compilerConfiguration: CompilerConfiguration,
    targetLabel: String,
    directDeps: MutableList<String>,
    explicitDeps: Map<String, List<String>>,
  ) {
    when (compilerConfiguration.getNotNull(JdepsGenConfigurationKeys.STRICT_KOTLIN_DEPS)) {
      "warn" -> checkStrictDeps(explicitDeps, directDeps, targetLabel)
      "error" -> {
        if (checkStrictDeps(explicitDeps, directDeps, targetLabel)) {
          error(
            "Strict Deps Violations - please fix",
          )
        }
      }
    }
  }

  /**
   * Prints strict deps warnings and returns true if violations were found.
   */
  private fun checkStrictDeps(
    result: Map<String, List<String>>,
    directDeps: List<String>,
    targetLabel: String,
  ): Boolean {
    val missingStrictDeps = result.keys
      .filter { !directDeps.contains(it) }
      .map { JarOwner.readJarOwnerFromManifest(Paths.get(it)) }

    if (missingStrictDeps.isNotEmpty()) {
      val missingStrictLabels = missingStrictDeps.mapNotNull { it.label }

      val open = "\u001b[35m\u001b[1m"
      val close = "\u001b[0m"

      var command =
        """
        $open ** Please add the following dependencies:$close
        ${
          missingStrictDeps.map { it.label ?: it.jar }.joinToString(" ")
        } to $targetLabel
        """

      if (missingStrictLabels.isNotEmpty()) {
        command += """$open ** You can use the following buildozer command:$close
        buildozer 'add deps ${
          missingStrictLabels.joinToString(" ")
        }' $targetLabel
        """
      }

      println(command.trimIndent())
      return true
    }
    return false
  }
}
