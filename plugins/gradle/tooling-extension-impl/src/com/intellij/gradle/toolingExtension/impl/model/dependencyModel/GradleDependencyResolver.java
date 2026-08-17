// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.gradle.toolingExtension.impl.model.dependencyModel;

import com.intellij.gradle.toolingExtension.impl.model.dependencyDownloadPolicyModel.GradleDependencyDownloadPolicy;
import com.intellij.gradle.toolingExtension.impl.model.dependencyDownloadPolicyModel.GradleDependencyDownloadPolicyCache;
import com.intellij.gradle.toolingExtension.impl.model.dependencyModel.auxiliary.AuxiliaryArtifactProvider;
import com.intellij.gradle.toolingExtension.impl.model.dependencyModel.auxiliary.AuxiliaryArtifactResolver;
import com.intellij.gradle.toolingExtension.impl.model.dependencyModel.auxiliary.AuxiliaryArtifactResolverImpl;
import com.intellij.gradle.toolingExtension.impl.model.dependencyModel.auxiliary.AuxiliaryConfigurationArtifacts;
import com.intellij.gradle.toolingExtension.impl.model.dependencyModel.auxiliary.LegacyAuxiliaryArtifactResolver;
import com.intellij.gradle.toolingExtension.impl.model.sourceSetArtifactIndex.GradleSourceSetArtifactIndex;
import com.intellij.gradle.toolingExtension.util.GradleReflectionUtil;
import com.intellij.gradle.toolingExtension.util.GradleVersionUtil;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.LenientConfiguration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.UnresolvedDependency;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.IvyArtifactRepository;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.specs.Spec;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.util.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.model.AbstractExternalDependency;
import org.jetbrains.plugins.gradle.model.DefaultExternalLibraryDependency;
import org.jetbrains.plugins.gradle.model.DefaultExternalProjectDependency;
import org.jetbrains.plugins.gradle.model.DefaultFileCollectionDependency;
import org.jetbrains.plugins.gradle.model.DefaultUnresolvedExternalDependency;
import org.jetbrains.plugins.gradle.model.ExternalDependency;
import org.jetbrains.plugins.gradle.model.FileCollectionDependency;
import org.jetbrains.plugins.gradle.tooling.ModelBuilderContext;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * @author Vladislav.Soroka
 */
public final class GradleDependencyResolver {

  private static final boolean IS_83_OR_BETTER = GradleVersionUtil.isCurrentGradleAtLeast("8.3");
  private static final boolean IS_60_OR_BETTER = GradleVersionUtil.isCurrentGradleAtLeast("6.0");

  // Gradle 6.4+
  private static final Predicate<String> UNRESOLVED_DEPENDENCY_JVM_PREDICATE = Pattern.compile(
    // Gradle 8.8+
    "(Dependency resolution is looking for a library compatible with JVM runtime version (\\d+), " +
    "but '(.+?)' is only compatible with JVM runtime version (\\d+) or newer\\.)" +
    "|" +
    // Gradle 6.4-8.7
    "(No matching variant of (.+?) was found\\. " +
    "The consumer was configured to find (?:a library for use during (?:compile-time|runtime),|an API of a library) compatible with Java)"
  ).asPredicate();

  // Gradle 6.0-6.3
  private static final Predicate<String> UNRESOLVED_DEPENDENCY_JVM_6_0_PREDICATE = Pattern.compile(
    "Required org.gradle.jvm.version '(\\d+)' (?:but no value provided|and found incompatible value '(\\d+)')\\."
  ).asPredicate();

  private final @NotNull Project myProject;
  private final @NotNull GradleDependencyDownloadPolicy myDownloadPolicy;

  private final @NotNull GradleSourceSetArtifactIndex mySourceSetArtifactIndex;

  public GradleDependencyResolver(
    @NotNull ModelBuilderContext context,
    @NotNull Project project,
    @NotNull GradleDependencyDownloadPolicy downloadPolicy
  ) {
    myProject = project;
    myDownloadPolicy = downloadPolicy;

    mySourceSetArtifactIndex = GradleSourceSetArtifactIndex.getInstance(context);
  }

  public GradleDependencyResolver(@NotNull ModelBuilderContext context, @NotNull Project project) {
    this(context, project, GradleDependencyDownloadPolicyCache.getInstance(context).getDependencyDownloadPolicy(project));
  }

  // Returns null if the artifact view resolution failed
  private static @Nullable ArtifactCollection resolveConfigurationDependencies(@NotNull Configuration configuration,
                                                                               Set<String> allowedDependencyGroups) {
    // The following statement should trigger parallel resolution of configuration artifacts
    // All subsequent iterations are expected to use cached results.
    try {
      ArtifactView artifactView = configuration.getIncoming().artifactView(new Action<ArtifactView.ViewConfiguration>() {
        @Override
        public void execute(@NotNull ArtifactView.ViewConfiguration configuration) {
          configuration.setLenient(true);

          if (!allowedDependencyGroups.isEmpty()) {
            configuration.componentFilter(new Spec<ComponentIdentifier>() {
              @Override
              public boolean isSatisfiedBy(ComponentIdentifier componentIdentifier) {
                if (componentIdentifier instanceof ModuleComponentIdentifier) {
                  return allowedDependencyGroups.contains(((ModuleComponentIdentifier)componentIdentifier).getGroup());
                }
                return false;
              }
            });
          }
        }
      });
      return artifactView.getArtifacts();
    }
    catch (Exception ignore) {
    }
    return null;
  }

  public @NotNull Collection<ExternalDependency> resolveDependencies(@Nullable Configuration configuration) {
    return resolveDependencies(configuration, Collections.emptySet());
  }

  /**
   * @param configuration           resolvable configuration
   * @param allowedDependencyGroups this filter forces to use artifactView for getting docs and sources,
   *                                which is not working well with ivy repositories (see IDEA-275594).
   *                                So, be careful to use this parameter.
   * @return both resolved and unresolved with the reason dependencies from the given configuration
   */
  public @NotNull Collection<ExternalDependency> resolveDependencies(@Nullable Configuration configuration,
                                                                     Set<String> allowedDependencyGroups) {
    if (configuration == null) {
      return Collections.emptySet();
    }
    ArtifactCollection artifactCollection = resolveConfigurationDependencies(configuration, allowedDependencyGroups);
    // configurationDependencies can be empty, for example, in the case of a composite build. We should continue resolution anyway.
    Set<ResolvedArtifactResult> configurationDependencies =
      artifactCollection == null ? Collections.emptySet() : artifactCollection.getArtifacts();

    Collection<ExternalDependency> result;
    if (artifactCollection != null && canUseModernResolver(configurationDependencies, configuration)) {
      ResolutionResult resolutionResult = configuration.getIncoming().getResolutionResult();
      result = resolveDependenciesModern(configuration, artifactCollection, resolutionResult, allowedDependencyGroups);
    }
    else {
      result = resolveDependenciesLegacy(configuration, configurationDependencies, allowedDependencyGroups);
    }
    GradleDependencyModelDumper.dump(myProject, configuration, result);
    return result;
  }

  /**
   * The modern resolution is based on {@link ResolutionResult} and artifact views only.
   * It avoids {@link LenientConfiguration#getAllModuleDependencies()}, which forces Gradle
   * to build the heavyweight legacy ResolvedDependency graph.
   */
  private static boolean canUseModernResolver(@NotNull Set<ResolvedArtifactResult> configurationDependencies,
                                              @NotNull Configuration configuration) {
    if (!IS_60_OR_BETTER || isLegacyDependencyResolverForced()) {
      return false;
    }
    // An empty artifact view for a non-empty graph means the view resolution went wrong
    // (e.g. some composite build setups); fall back to the legacy resolution then.
    return !configurationDependencies.isEmpty() ||
           configuration.getIncoming().getResolutionResult().getAllComponents().size() <= 1;
  }

  private @NotNull Collection<ExternalDependency> resolveDependenciesModern(
    @NotNull Configuration configuration,
    @NotNull ArtifactCollection artifactCollection,
    @NotNull ResolutionResult resolutionResult,
    @NotNull Set<String> allowedDependencyGroups
  ) {
    Set<ResolvedArtifactResult> configurationDependencies = artifactCollection.getArtifacts();

    // Here we collect java doc and source files for a given dependencies
    AuxiliaryConfigurationArtifacts auxiliaryArtifacts = getAuxiliaryArtifactResolver(
      extractModuleComponents(configurationDependencies), allowedDependencyGroups
    ).resolve(configuration);
    auxiliaryArtifacts = resolveSupplementaryArtifacts(configuration, auxiliaryArtifacts);

    Set<String> resolvedFiles = new HashSet<>();
    Collection<ExternalDependency> artifactDependencies = resolveModernArtifactDependencies(
      resolvedFiles, configurationDependencies, resolutionResult, auxiliaryArtifacts, artifactCollection.getFailures()
    );
    Collection<FileCollectionDependency> otherFileDependencies = resolveOtherFileDependencies(resolvedFiles, configurationDependencies);
    Collection<ExternalDependency> unresolvedDependencies = collectUnresolvedDependencies(resolutionResult, allowedDependencyGroups);

    Collection<ExternalDependency> result = new LinkedHashSet<>();
    result.addAll(otherFileDependencies);
    result.addAll(artifactDependencies);
    result.addAll(unresolvedDependencies);

    int order = 0;
    for (ExternalDependency dependency : result) {
      ((AbstractExternalDependency)dependency).setClasspathOrder(++order);
    }
    return result;
  }

  private @NotNull Collection<ExternalDependency> resolveModernArtifactDependencies(
    @NotNull Set<String> resolvedFiles, // mutable
    @NotNull Set<ResolvedArtifactResult> resolvedArtifacts,
    @NotNull ResolutionResult resolutionResult,
    @NotNull AuxiliaryConfigurationArtifacts auxiliaryArtifacts,
    @NotNull Collection<Throwable> artifactFailures
  ) {
    Collection<ExternalDependency> artifactDependencies = new LinkedHashSet<>();
    Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies = new HashMap<>();
    Map<ComponentIdentifier, ResolvedComponentResult> componentsById = null; // lazy
    for (ResolvedArtifactResult artifact : resolvedArtifacts) {
      ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
      if (componentIdentifier instanceof ProjectComponentIdentifier) {
        if (componentsById == null) {
          componentsById = indexComponents(resolutionResult);
        }
        ExternalDependency dependency = resolveModernProjectDependency(
          resolvedProjectDependencies, resolvedFiles, artifact, (ProjectComponentIdentifier)componentIdentifier, componentsById
        );
        if (dependency != null) {
          artifactDependencies.add(dependency);
        }
      }
      else if (componentIdentifier instanceof ModuleComponentIdentifier) {
        File artifactFile = resolveArtifactFile(resolvedFiles, artifact.getFile());
        if (artifactFile == null) {
          continue;
        }
        artifactDependencies.add(
          resolveModernLibraryDependency((ModuleComponentIdentifier)componentIdentifier, artifactFile, auxiliaryArtifacts)
        );
      }
    }
    if (!artifactFailures.isEmpty()) {
      if (componentsById == null) {
        componentsById = indexComponents(resolutionResult);
      }
      artifactDependencies.addAll(
        recoverFailedTransformProjectDependencies(resolvedProjectDependencies, resolvedArtifacts, componentsById)
      );
    }
    return artifactDependencies;
  }

  private static @NotNull Map<ComponentIdentifier, ResolvedComponentResult> indexComponents(
    @NotNull ResolutionResult resolutionResult
  ) {
    Set<? extends ResolvedComponentResult> allComponents = resolutionResult.getAllComponents();
    Map<ComponentIdentifier, ResolvedComponentResult> componentsById = new HashMap<>(allComponents.size());
    for (ResolvedComponentResult component : allComponents) {
      componentsById.put(component.getId(), component);
    }
    return componentsById;
  }

  // Returns null if artifact was already resolved
  private @Nullable DefaultExternalProjectDependency resolveModernProjectDependency(
    @NotNull Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies, // mutable
    @NotNull Set<String> resolvedFiles, // mutable
    @NotNull ResolvedArtifactResult artifact,
    @NotNull ProjectComponentIdentifier projectComponentIdentifier,
    @NotNull Map<ComponentIdentifier, ResolvedComponentResult> componentsById
  ) {
    File artifactFile = resolveArtifactFile(resolvedFiles, artifact.getFile());
    if (artifactFile == null) {
      return null;
    }
    ResolvedComponentResult component = componentsById.get(projectComponentIdentifier);
    String configurationName = selectConfigurationName(artifact, component);
    String key = getProjectDependencyKey(projectComponentIdentifier, configurationName);
    DefaultExternalProjectDependency cachedProjectDependency = resolvedProjectDependencies.get(key);

    if (cachedProjectDependency != null) {
      Set<File> projectDependencyArtifacts = new LinkedHashSet<>(cachedProjectDependency.getProjectDependencyArtifacts());
      projectDependencyArtifacts.add(artifactFile);
      cachedProjectDependency.setProjectDependencyArtifacts(projectDependencyArtifacts);
      Set<File> artifactSources = new LinkedHashSet<>(cachedProjectDependency.getProjectDependencyArtifactsSources());
      artifactSources.addAll(mySourceSetArtifactIndex.findArtifactSources(artifactFile));
      cachedProjectDependency.setProjectDependencyArtifactsSources(artifactSources);
      return null;
    }

    DefaultExternalProjectDependency projectDependency = new DefaultExternalProjectDependency();
    resolvedProjectDependencies.put(key, projectDependency);

    ModuleVersionIdentifier moduleVersion = component == null ? null : component.getModuleVersion();
    projectDependency.setName(projectComponentIdentifier.getProjectName());
    projectDependency.setGroup(moduleVersion == null ? "" : moduleVersion.getGroup());
    projectDependency.setVersion(moduleVersion == null ? "" : moduleVersion.getVersion());
    projectDependency.setProjectPath(projectComponentIdentifier.getProjectPath());
    projectDependency.setConfigurationName(configurationName);
    projectDependency.setProjectDependencyArtifacts(Collections.singleton(artifactFile));
    projectDependency.setProjectDependencyArtifactsSources(mySourceSetArtifactIndex.findArtifactSources(artifactFile));

    return projectDependency;
  }

  // The selected variant name is the modern equivalent of the legacy ResolvedDependency.getConfiguration()
  private static @NotNull String selectConfigurationName(
    @NotNull ResolvedArtifactResult artifact,
    @Nullable ResolvedComponentResult component
  ) {
    if (component == null) {
      return Dependency.DEFAULT_CONFIGURATION;
    }
    List<ResolvedVariantResult> variants = component.getVariants();
    if (variants.isEmpty()) {
      return Dependency.DEFAULT_CONFIGURATION;
    }
    if (variants.size() == 1) {
      return variants.get(0).getDisplayName();
    }
    // The component was selected with several variants (e.g. test fixtures);
    // match the artifact to its variant by the variant attributes.
    AttributeContainer artifactAttributes = artifact.getVariant().getAttributes();
    for (ResolvedVariantResult variant : variants) {
      if (containsAll(artifactAttributes, variant.getAttributes())) {
        return variant.getDisplayName();
      }
    }
    return variants.get(0).getDisplayName();
  }

  private static boolean containsAll(@NotNull AttributeContainer container, @NotNull AttributeContainer expected) {
    for (Attribute<?> attribute : expected.keySet()) {
      if (!Objects.equals(container.getAttribute(attribute), expected.getAttribute(attribute))) {
        return false;
      }
    }
    return true;
  }

  private static @NotNull String getProjectDependencyKey(
    @NotNull ProjectComponentIdentifier projectComponentIdentifier,
    @NotNull String configurationName
  ) {
    String buildName = getBuildName(projectComponentIdentifier);
    String projectPath = projectComponentIdentifier.getProjectPath();
    return buildName + "_" + projectPath + "_" + configurationName;
  }

  private static @NotNull DefaultExternalLibraryDependency resolveModernLibraryDependency(
    @NotNull ModuleComponentIdentifier componentIdentifier,
    @NotNull File artifactFile,
    @NotNull AuxiliaryConfigurationArtifacts auxiliaryArtifacts
  ) {
    DefaultExternalLibraryDependency libraryDependency = new DefaultExternalLibraryDependency();

    libraryDependency.setName(componentIdentifier.getModule());
    libraryDependency.setGroup(componentIdentifier.getGroup());
    libraryDependency.setVersion(componentIdentifier.getVersion());
    libraryDependency.setFile(artifactFile);

    File sourcesFile = auxiliaryArtifacts.getSources(componentIdentifier, artifactFile);
    if (sourcesFile != null) {
      libraryDependency.setSource(sourcesFile);
    }
    File javadocFile = auxiliaryArtifacts.getJavadoc(componentIdentifier, artifactFile);
    if (javadocFile != null) {
      libraryDependency.setJavadoc(javadocFile);
    }

    return libraryDependency;
  }

  // Recovers project dependencies whose artifacts failed to transform and are therefore missing from the artifact view
  private @NotNull Collection<ExternalDependency> recoverFailedTransformProjectDependencies(
    @NotNull Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies, // mutable
    @NotNull Set<ResolvedArtifactResult> resolvedArtifacts,
    @NotNull Map<ComponentIdentifier, ResolvedComponentResult> componentsById
  ) {
    Set<ComponentIdentifier> componentsWithArtifacts = new HashSet<>();
    for (ResolvedArtifactResult artifact : resolvedArtifacts) {
      componentsWithArtifacts.add(artifact.getId().getComponentIdentifier());
    }
    Collection<ExternalDependency> result = new LinkedHashSet<>();
    for (Map.Entry<ComponentIdentifier, ResolvedComponentResult> entry : componentsById.entrySet()) {
      ComponentIdentifier componentIdentifier = entry.getKey();
      if (!(componentIdentifier instanceof ProjectComponentIdentifier) || componentsWithArtifacts.contains(componentIdentifier)) {
        continue;
      }
      ProjectComponentIdentifier projectComponentIdentifier = (ProjectComponentIdentifier)componentIdentifier;
      Project project = myProject.findProject(projectComponentIdentifier.getProjectPath());
      if (project == null) continue;

      ModuleVersionIdentifier moduleVersion = entry.getValue().getModuleVersion();
      for (ResolvedVariantResult variant : entry.getValue().getVariants()) {
        String configurationName = variant.getDisplayName();
        Configuration targetConfiguration = project.getConfigurations().findByName(configurationName);
        if (targetConfiguration == null) continue;

        String key = getProjectDependencyKey(projectComponentIdentifier, configurationName);
        if (resolvedProjectDependencies.containsKey(key)) continue;

        DefaultExternalProjectDependency projectDependency = new DefaultExternalProjectDependency();
        resolvedProjectDependencies.put(key, projectDependency);

        projectDependency.setName(projectComponentIdentifier.getProjectName());
        projectDependency.setGroup(moduleVersion == null ? "" : moduleVersion.getGroup());
        projectDependency.setVersion(moduleVersion == null ? "" : moduleVersion.getVersion());
        projectDependency.setProjectPath(projectComponentIdentifier.getProjectPath());
        projectDependency.setConfigurationName(configurationName);

        Set<File> projectArtifacts = targetConfiguration.getArtifacts().getFiles().getFiles();
        projectDependency.setProjectDependencyArtifacts(projectArtifacts);
        projectDependency.setProjectDependencyArtifactsSources(mySourceSetArtifactIndex.findArtifactSources(projectArtifacts));
        result.add(projectDependency);
      }
    }
    return result;
  }

  private static @NotNull Set<ComponentIdentifier> extractModuleComponents(
    @NotNull Set<ResolvedArtifactResult> artifacts
  ) {
    Set<ComponentIdentifier> components = new LinkedHashSet<>();
    for (ResolvedArtifactResult artifact : artifacts) {
      ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
      if (componentIdentifier instanceof ModuleComponentIdentifier) {
        components.add(componentIdentifier);
      }
    }
    return components;
  }

  private @NotNull Collection<ExternalDependency> resolveDependenciesLegacy(
    @NotNull Configuration configuration,
    @NotNull Set<ResolvedArtifactResult> configurationDependencies,
    @NotNull Set<String> allowedDependencyGroups
  ) {
    LenientConfiguration lenientConfiguration = configuration.getResolvedConfiguration().getLenientConfiguration();
    Map<ResolvedDependency, Set<ResolvedArtifact>> resolvedArtifacts = new LinkedHashMap<>();
    boolean hasFailedToTransformDependencies = false;
    for (ResolvedDependency dependency : lenientConfiguration.getAllModuleDependencies()) {
      try {
        if (allowedDependencyGroups.isEmpty() || allowedDependencyGroups.contains(dependency.getModuleGroup())) {
          resolvedArtifacts.put(dependency, dependency.getModuleArtifacts());
        }
      }
      catch (GradleException e) {
        hasFailedToTransformDependencies = true;
        resolvedArtifacts.put(dependency, Collections.emptySet());
      }
      catch (Exception ignore) {
        // ignore other artifact resolution exceptions
      }
    }
    Map<ModuleVersionIdentifier, ResolvedDependencyResult> transformedProjectDependenciesResultMap = new HashMap<>();
    if (hasFailedToTransformDependencies) {
      for (DependencyResult dependencyResult : configuration.getIncoming().getResolutionResult().getAllDependencies()) {
        ComponentSelector resultRequested = dependencyResult.getRequested();
        //Note: we don't use here alloweded dependency groups filter, because it has sense only for ModuleComponentSelector
        if (dependencyResult instanceof ResolvedDependencyResult && resultRequested instanceof ProjectComponentSelector) {
          ResolvedComponentResult resolvedComponentResult = ((ResolvedDependencyResult)dependencyResult).getSelected();
          ModuleVersionIdentifier selectedResultVersion = resolvedComponentResult.getModuleVersion();
          transformedProjectDependenciesResultMap.put(selectedResultVersion, (ResolvedDependencyResult)dependencyResult);
        }
      }
    }
    // Here we collect java doc and source files for a given dependencies
    AuxiliaryConfigurationArtifacts auxiliaryArtifacts = getAuxiliaryArtifactResolver(
      extractModuleComponents(resolvedArtifacts), allowedDependencyGroups
    ).resolve(configuration);
    auxiliaryArtifacts = resolveSupplementaryArtifacts(configuration, auxiliaryArtifacts);
    Set<String> resolvedFiles = new HashSet<>();
    Collection<ExternalDependency> artifactDependencies = resolveArtifactDependencies(
      resolvedFiles, resolvedArtifacts, auxiliaryArtifacts, transformedProjectDependenciesResultMap
    );
    Collection<FileCollectionDependency> otherFileDependencies = resolveOtherFileDependencies(resolvedFiles, configurationDependencies);
    Collection<ExternalDependency> unresolvedDependencies = collectUnresolvedDependencies(lenientConfiguration, allowedDependencyGroups);

    Collection<ExternalDependency> result = new LinkedHashSet<>();
    result.addAll(otherFileDependencies);
    result.addAll(artifactDependencies);
    result.addAll(unresolvedDependencies);

    int order = 0;
    for (ExternalDependency dependency : result) {
      ((AbstractExternalDependency)dependency).setClasspathOrder(++order);
    }
    return result;
  }

  private static @NotNull List<ComponentIdentifier> extractModuleComponents(
    @NotNull Map<ResolvedDependency, Set<ResolvedArtifact>> resolvedArtifacts
  ) {
    List<ComponentIdentifier> components = new ArrayList<>();
    for (Collection<ResolvedArtifact> artifacts : resolvedArtifacts.values()) {
      for (ResolvedArtifact artifact : artifacts) {
        if (artifact.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier) continue;
        components.add(DefaultModuleComponentIdentifier.create(artifact.getModuleVersion().getId()));
      }
    }
    return components;
  }

  private @NotNull Collection<ExternalDependency> resolveArtifactDependencies(
    @NotNull Set<String> resolvedFiles, // mutable
    @NotNull Map<ResolvedDependency, Set<ResolvedArtifact>> resolvedArtifacts,
    @NotNull AuxiliaryConfigurationArtifacts auxiliaryArtifacts,
    @NotNull Map<ModuleVersionIdentifier, ResolvedDependencyResult> transformedProjectDependenciesResultMap
  ) {
    Collection<ExternalDependency> artifactDependencies = new LinkedHashSet<>();
    Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies = new HashMap<>();
    for (Map.Entry<ResolvedDependency, Set<ResolvedArtifact>> resolvedDependencySetEntry : resolvedArtifacts.entrySet()) {
      ResolvedDependency resolvedDependency = resolvedDependencySetEntry.getKey();
      Set<ResolvedArtifact> artifacts = resolvedDependencySetEntry.getValue();
      for (ResolvedArtifact artifact : artifacts) {
        File artifactFile = resolveArtifactFile(resolvedFiles, artifact.getFile());
        if (artifactFile == null) {
          continue;
        }
        ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
        if (componentIdentifier instanceof ProjectComponentIdentifier) {
          ExternalDependency dependency = resolveProjectDependency(
            resolvedProjectDependencies, resolvedDependency, artifactFile, (ProjectComponentIdentifier)componentIdentifier
          );
          if (dependency != null) {
            artifactDependencies.add(dependency);
          }
        }
        else {
          ExternalDependency dependency = resolveLibraryDependency(artifact, artifactFile, auxiliaryArtifacts);
          artifactDependencies.add(dependency);
        }
      }
      if (artifacts.isEmpty()) {
        ExternalDependency dependency = resolveFailedToTransformProjectDependency(
          resolvedProjectDependencies, resolvedDependency, transformedProjectDependenciesResultMap
        );
        if (dependency != null) {
          artifactDependencies.add(dependency);
        }
      }
    }
    return artifactDependencies;
  }

  // Returns null if artifact was already resolved
  private @Nullable File resolveArtifactFile(
    @NotNull Set<String> resolvedFiles, // mutable
    @NotNull File artifactFile
  ) {
    if (resolvedFiles.contains(artifactFile.getPath())) {
      return null;
    }
    resolvedFiles.add(artifactFile.getPath());
    String artifactPath = mySourceSetArtifactIndex.findArtifactBySourceSetOutputDir(artifactFile.getPath());
    if (artifactPath != null) {
      artifactFile = new File(artifactPath);
      if (resolvedFiles.contains(artifactFile.getPath())) {
        return null;
      }
      resolvedFiles.add(artifactFile.getPath());
    }
    return artifactFile;
  }

  private static @NotNull String getProjectDependencyKey(
    @NotNull ResolvedDependency resolvedDependency,
    @NotNull ProjectComponentIdentifier projectComponentIdentifier
  ) {
    String buildName = getBuildName(projectComponentIdentifier);
    String projectPath = projectComponentIdentifier.getProjectPath();
    return buildName + "_" + projectPath + "_" + resolvedDependency.getConfiguration();
  }

  private static @NotNull String getProjectDependencyKey(
    @NotNull ResolvedDependency resolvedDependency,
    @NotNull ResolvedDependencyResult resolvedDependencyResult
  ) {
    ProjectComponentSelector dependencyResultRequested = (ProjectComponentSelector)resolvedDependencyResult.getRequested();
    String projectPath = dependencyResultRequested.getProjectPath();
    return projectPath + "_" + resolvedDependency.getConfiguration();
  }

  // Returns null if artifact was already resolved
  private @Nullable DefaultExternalProjectDependency resolveProjectDependency(
    @NotNull Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies, // mutable
    @NotNull ResolvedDependency resolvedDependency,
    @NotNull File artifactFile,
    @NotNull ProjectComponentIdentifier projectComponentIdentifier
  ) {
    String key = getProjectDependencyKey(resolvedDependency, projectComponentIdentifier);
    DefaultExternalProjectDependency cachedProjectDependency = resolvedProjectDependencies.get(key);

    if (cachedProjectDependency != null) {
      Set<File> projectDependencyArtifacts = new LinkedHashSet<>(cachedProjectDependency.getProjectDependencyArtifacts());
      projectDependencyArtifacts.add(artifactFile);
      cachedProjectDependency.setProjectDependencyArtifacts(projectDependencyArtifacts);
      Set<File> artifactSources = new LinkedHashSet<>(cachedProjectDependency.getProjectDependencyArtifactsSources());
      artifactSources.addAll(mySourceSetArtifactIndex.findArtifactSources(artifactFile));
      cachedProjectDependency.setProjectDependencyArtifactsSources(artifactSources);
      return null;
    }

    DefaultExternalProjectDependency projectDependency = new DefaultExternalProjectDependency();
    resolvedProjectDependencies.put(key, projectDependency);

    projectDependency.setName(projectComponentIdentifier.getProjectName());
    projectDependency.setGroup(resolvedDependency.getModuleGroup());
    projectDependency.setVersion(resolvedDependency.getModuleVersion());
    projectDependency.setProjectPath(projectComponentIdentifier.getProjectPath());
    projectDependency.setConfigurationName(resolvedDependency.getConfiguration());
    projectDependency.setProjectDependencyArtifacts(Collections.singleton(artifactFile));
    projectDependency.setProjectDependencyArtifactsSources(mySourceSetArtifactIndex.findArtifactSources(artifactFile));

    return projectDependency;
  }

  private static @NotNull DefaultExternalLibraryDependency resolveLibraryDependency(
    @NotNull ResolvedArtifact artifact,
    @NotNull File artifactFile,
    @NotNull AuxiliaryConfigurationArtifacts auxiliaryArtifacts
  ) {
    DefaultExternalLibraryDependency libraryDependency = new DefaultExternalLibraryDependency();

    ModuleVersionIdentifier moduleVersionIdentifier = artifact.getModuleVersion().getId();
    libraryDependency.setName(moduleVersionIdentifier.getName());
    libraryDependency.setGroup(moduleVersionIdentifier.getGroup());
    libraryDependency.setVersion(moduleVersionIdentifier.getVersion());
    libraryDependency.setFile(artifactFile);

    ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
    File sourcesFile = auxiliaryArtifacts.getSources(componentIdentifier, artifactFile);
    if (sourcesFile != null) {
      libraryDependency.setSource(sourcesFile);
    }
    File javadocFile = auxiliaryArtifacts.getJavadoc(componentIdentifier, artifactFile);
    if (javadocFile != null) {
      libraryDependency.setJavadoc(javadocFile);
    }
    if (artifact.getExtension() != null) {
      libraryDependency.setPackaging(artifact.getExtension());
    }
    libraryDependency.setClassifier(artifact.getClassifier());

    return libraryDependency;
  }

  // Returns null if dependency was already resolved or cannot be resolved
  private @Nullable ExternalDependency resolveFailedToTransformProjectDependency(
    @NotNull Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies, // mutable
    @NotNull ResolvedDependency resolvedDependency,
    @NotNull Map<ModuleVersionIdentifier, ResolvedDependencyResult> transformedProjectDependenciesResultMap
  ) {
    ModuleVersionIdentifier moduleVersionIdentifier = resolvedDependency.getModule().getId();
    ResolvedDependencyResult resolvedDependencyResult = transformedProjectDependenciesResultMap.get(moduleVersionIdentifier);
    if (resolvedDependencyResult == null) return null;

    String key = getProjectDependencyKey(resolvedDependency, resolvedDependencyResult);
    DefaultExternalProjectDependency cachedProjectDependency = resolvedProjectDependencies.get(key);
    if (cachedProjectDependency != null) return null;

    DefaultExternalProjectDependency projectDependency = new DefaultExternalProjectDependency();
    resolvedProjectDependencies.put(key, projectDependency);
    ProjectComponentSelector dependencyResultRequested = (ProjectComponentSelector)resolvedDependencyResult.getRequested();
    String projectPath = dependencyResultRequested.getProjectPath();
    String projectName = Path.path(projectPath).getName();
    projectDependency.setName(projectName);
    projectDependency.setGroup(resolvedDependency.getModuleGroup());
    projectDependency.setVersion(resolvedDependency.getModuleVersion());
    projectDependency.setProjectPath(projectPath);
    projectDependency.setConfigurationName(resolvedDependency.getConfiguration());

    Project project = myProject.findProject(projectPath);
    if (project == null) return null;
    Configuration configuration = project.getConfigurations().findByName(resolvedDependency.getConfiguration());
    if (configuration == null) return null;
    Set<File> projectArtifacts = configuration.getArtifacts().getFiles().getFiles();
    projectDependency.setProjectDependencyArtifacts(projectArtifacts);
    projectDependency.setProjectDependencyArtifactsSources(mySourceSetArtifactIndex.findArtifactSources(projectArtifacts));

    return projectDependency;
  }

  private @NotNull AuxiliaryArtifactResolver getAuxiliaryArtifactResolver(
    @NotNull Collection<ComponentIdentifier> moduleComponents,
    @NotNull Set<String> allowedDependencyGroups
  ) {
    if (isLegacyDependencyResolverForced() || GradleVersionUtil.isCurrentGradleOlderThan("7.5") || isIvyRepositoryUsed(myProject)) {
      return new LegacyAuxiliaryArtifactResolver(myProject, myDownloadPolicy, moduleComponents);
    }
    return new AuxiliaryArtifactResolverImpl(myProject, myDownloadPolicy, allowedDependencyGroups);
  }

  private static boolean isLegacyDependencyResolverForced() {
    String useLegacyResolverPropertyValue = System.getProperty("idea.gradle.daemon.legacy.dependency.resolver", "false");
    return Boolean.parseBoolean(useLegacyResolverPropertyValue);
  }

  // resolve generated dependencies such as annotation processing build roots and compilation result
  private static @NotNull Collection<FileCollectionDependency> resolveOtherFileDependencies(
    @NotNull Set<String> resolvedFiles, // mutable
    @NotNull Set<ResolvedArtifactResult> configurationDependencies
  ) {
    Collection<FileCollectionDependency> result = new LinkedHashSet<>();
    for (ResolvedArtifactResult dependency : configurationDependencies) {
      ComponentIdentifier identifier = dependency.getId().getComponentIdentifier();
      // libraries, modules and subprojects are already well known
      if (identifier instanceof LibraryBinaryIdentifier
          || identifier instanceof ModuleComponentIdentifier
          || identifier instanceof ProjectComponentIdentifier) {
        continue;
      }
      File file = dependency.getFile();
      String path = file.getPath();
      if (resolvedFiles.add(path)) {
        result.add(new DefaultFileCollectionDependency(Collections.singleton(file)));
      }
    }
    return result;
  }

  private static @NotNull Collection<ExternalDependency> collectUnresolvedDependencies(
    @NotNull ResolutionResult resolutionResult,
    @NotNull Set<String> allowedDependencyGroups
  ) {
    Collection<ExternalDependency> result = new LinkedHashSet<>();
    for (DependencyResult dependencyResult : resolutionResult.getAllDependencies()) {
      if (!(dependencyResult instanceof UnresolvedDependencyResult)) continue;
      ComponentSelector attempted = ((UnresolvedDependencyResult)dependencyResult).getAttempted();
      if (!(attempted instanceof ModuleComponentSelector)) continue;
      ModuleComponentSelector selector = (ModuleComponentSelector)attempted;
      if (!allowedDependencyGroups.isEmpty() && !allowedDependencyGroups.contains(selector.getGroup())) {
        continue;
      }
      Throwable problem = ((UnresolvedDependencyResult)dependencyResult).getFailure();
      if (problem.getCause() != null) {
        problem = problem.getCause();
      }
      MyModuleVersionSelector moduleVersionSelector = extractModuleVersionSelector(selector, problem);
      if (moduleVersionSelector == null) {
        problem = ((UnresolvedDependencyResult)dependencyResult).getFailure();
        moduleVersionSelector = new MyModuleVersionSelector(selector.getModule(), selector.getGroup(), selector.getVersion());
      }
      DefaultUnresolvedExternalDependency dependency = new DefaultUnresolvedExternalDependency();
      dependency.setName(moduleVersionSelector.name);
      dependency.setGroup(moduleVersionSelector.group);
      dependency.setVersion(moduleVersionSelector.version);
      dependency.setFailureMessage(problem.getMessage());
      result.add(dependency);
    }
    return result;
  }

  private static @NotNull Collection<ExternalDependency> collectUnresolvedDependencies(
    @NotNull LenientConfiguration lenientConfiguration,
    @NotNull Set<String> allowedDependencyGroups
  ) {
    Collection<ExternalDependency> result = new LinkedHashSet<>();
    Set<UnresolvedDependency> unresolvedModuleDependencies = lenientConfiguration.getUnresolvedModuleDependencies();
    for (UnresolvedDependency unresolvedDependency : unresolvedModuleDependencies) {
      if (!allowedDependencyGroups.isEmpty() && !allowedDependencyGroups.contains(unresolvedDependency.getSelector().getGroup())) {
        continue;
      }
      Throwable problem = unresolvedDependency.getProblem();
      if (problem.getCause() != null) {
        problem = problem.getCause();
      }
      MyModuleVersionSelector moduleVersionSelector = extractModuleVersionSelector(unresolvedDependency, problem);
      if (moduleVersionSelector == null) {
        problem = unresolvedDependency.getProblem();
        ModuleVersionSelector selector = unresolvedDependency.getSelector();
        moduleVersionSelector = new MyModuleVersionSelector(selector.getName(), selector.getGroup(), selector.getVersion());
      }
      DefaultUnresolvedExternalDependency dependency = new DefaultUnresolvedExternalDependency();
      dependency.setName(moduleVersionSelector.name);
      dependency.setGroup(moduleVersionSelector.group);
      dependency.setVersion(moduleVersionSelector.version);
      dependency.setFailureMessage(problem.getMessage());
      result.add(dependency);
    }
    return result;
  }

  private static @Nullable MyModuleVersionSelector extractModuleVersionSelector(
    @NotNull ModuleComponentSelector selector,
    @NotNull Throwable problem
  ) {
    try {
      // instanceof may throw an exception if the class is no longer available in some new Gradle version
      if (problem instanceof ModuleVersionResolveException) {
        ComponentSelector componentSelector = ((ModuleVersionResolveException)problem).getSelector();
        if (componentSelector instanceof ModuleComponentSelector) {
          ModuleComponentSelector moduleComponentSelector = (ModuleComponentSelector)componentSelector;
          return new MyModuleVersionSelector(
            moduleComponentSelector.getModule(),
            moduleComponentSelector.getGroup(),
            moduleComponentSelector.getVersion()
          );
        }
      }
    }
    catch (Throwable ignore) {
    }
    String problemMessage = problem.getMessage();
    if (problemMessage != null && UNRESOLVED_DEPENDENCY_JVM_PREDICATE.test(problemMessage)) {
      return new MyModuleVersionSelector(selector.getModule(), selector.getGroup(), selector.getVersion());
    }
    else if (problemMessage != null &&
             (problemMessage.startsWith("Cannot choose between the following variants of") ||
              problemMessage.startsWith("Unable to find a matching variant of")) &&
             UNRESOLVED_DEPENDENCY_JVM_6_0_PREDICATE.test(problemMessage)) {
      return new MyModuleVersionSelector(selector.getModule(), selector.getGroup(), selector.getVersion());
    }
    return null;
  }

  private static @Nullable MyModuleVersionSelector extractModuleVersionSelector(
    @NotNull UnresolvedDependency unresolvedDependency,
    @NotNull Throwable problem
  ) {
    try {
      // instanceof may throw an exception if the class is no longer available in some new Gradle version
      if (problem instanceof ModuleVersionResolveException) {
        ComponentSelector componentSelector = ((ModuleVersionResolveException)problem).getSelector();
        if (componentSelector instanceof ModuleComponentSelector) {
          ModuleComponentSelector moduleComponentSelector = (ModuleComponentSelector)componentSelector;
          return new MyModuleVersionSelector(
            moduleComponentSelector.getModule(),
            moduleComponentSelector.getGroup(),
            moduleComponentSelector.getVersion()
          );
        }
      }
    }
    catch (Throwable ignore) {
    }
    if (UNRESOLVED_DEPENDENCY_JVM_PREDICATE.test(problem.getMessage())) {
      ModuleVersionSelector selector = unresolvedDependency.getSelector();
      return new MyModuleVersionSelector(selector.getName(), selector.getGroup(), selector.getVersion());
    }
    else if ((problem.getMessage().startsWith("Cannot choose between the following variants of") ||
              problem.getMessage().startsWith("Unable to find a matching variant of")) &&
             UNRESOLVED_DEPENDENCY_JVM_6_0_PREDICATE.test(problem.getMessage())) {
      ModuleVersionSelector selector = unresolvedDependency.getSelector();
      return new MyModuleVersionSelector(selector.getName(), selector.getGroup(), selector.getVersion());
    }
    return null;
  }

  private static @NotNull String getBuildName(@NotNull ProjectComponentIdentifier projectComponentIdentifier) {
    BuildIdentifier buildIdentifier = projectComponentIdentifier.getBuild();
    if (IS_83_OR_BETTER) {
      return buildIdentifier.getBuildPath();
    } else {
      // The getName method was removed in Gradle 9.0
      return GradleReflectionUtil.getValue(buildIdentifier, "getName", String.class);
    }
  }

  private static final class MyModuleVersionSelector {
    private final String name;
    private final String group;
    private final String version;

    private MyModuleVersionSelector(String name, String group, String version) {
      this.name = name;
      this.group = group;
      this.version = version;
    }
  }

  private @NotNull AuxiliaryConfigurationArtifacts resolveSupplementaryArtifacts(
    @NotNull Configuration configuration,
    @NotNull AuxiliaryConfigurationArtifacts primaryArtifacts
  ) {
    if (!myDownloadPolicy.isDownloadSources() && !myDownloadPolicy.isDownloadJavadoc()) {
      return primaryArtifacts;
    }
    ServiceLoader<AuxiliaryArtifactProvider> providers = ServiceLoader.load(
      AuxiliaryArtifactProvider.class, AuxiliaryArtifactProvider.class.getClassLoader());
    for (AuxiliaryArtifactProvider provider : providers) {
      try {
        AuxiliaryConfigurationArtifacts additional = provider.resolve(myProject, configuration, myDownloadPolicy);
        primaryArtifacts = primaryArtifacts.mergeWith(additional);
      }
      catch (Exception ignore) {
        // supplementary providers should not break the main resolution
      }
    }
    return primaryArtifacts;
  }

  private static boolean isIvyRepositoryUsed(@NotNull Project project) {
    for (ArtifactRepository repository : project.getRepositories()) {
      if (repository instanceof IvyArtifactRepository) {
        return true;
      }
    }
    return false;
  }
}
