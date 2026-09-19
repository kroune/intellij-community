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
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
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
import org.gradle.api.specs.Spec;
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier;
import org.gradle.util.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.model.AbstractExternalDependency;
import org.jetbrains.plugins.gradle.model.DefaultExternalLibraryDependency;
import org.jetbrains.plugins.gradle.model.DefaultExternalProjectDependency;
import org.jetbrains.plugins.gradle.model.DefaultFileCollectionDependency;
import org.jetbrains.plugins.gradle.model.DefaultUnresolvedExternalDependency;
import org.jetbrains.plugins.gradle.model.ExternalDependency;
import org.jetbrains.plugins.gradle.model.ExternalProjectDependency;
import org.jetbrains.plugins.gradle.model.FileCollectionDependency;
import org.jetbrains.plugins.gradle.tooling.ModelBuilderContext;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * @author Vladislav.Soroka
 */
public final class GradleDependencyResolver {

  private static final boolean IS_83_OR_BETTER = GradleVersionUtil.isCurrentGradleAtLeast("8.3");

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
    // configurationDependencies can be empty, for example, in the case of a composite build. We should continue resolution anyway.
    ArtifactCollection artifactCollection = resolveConfigurationDependencies(configuration, allowedDependencyGroups);
    Set<ResolvedArtifactResult> configurationDependencies =
      artifactCollection == null ? Collections.emptySet() : artifactCollection.getArtifacts();
    boolean hasFailedToTransformDependencies =
      artifactCollection != null && !artifactCollection.getFailures().isEmpty();

    ResolutionResult resolutionResult = configuration.getIncoming().getResolutionResult();
    Map<ComponentIdentifier, ModuleVersionIdentifier> moduleVersions = new HashMap<>();
    for (ResolvedComponentResult component : resolutionResult.getAllComponents()) {
      ModuleVersionIdentifier moduleVersion = component.getModuleVersion();
      if (moduleVersion != null) {
        moduleVersions.put(component.getId(), moduleVersion);
      }
    }

    // Here we collect java doc and source files for a given dependencies
    AuxiliaryConfigurationArtifacts auxiliaryArtifacts = getAuxiliaryArtifactResolver(configurationDependencies, allowedDependencyGroups)
      .resolve(configuration);
    auxiliaryArtifacts = resolveSupplementaryArtifacts(configuration, auxiliaryArtifacts);
    Set<String> resolvedFiles = new HashSet<>();
    Collection<ExternalDependency> artifactDependencies = resolveArtifactDependencies(
      resolvedFiles, sortArtifactsInLegacyOrder(configurationDependencies, resolutionResult), auxiliaryArtifacts, moduleVersions,
      resolutionResult, hasFailedToTransformDependencies
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

  private @NotNull Collection<ExternalDependency> resolveArtifactDependencies(
    @NotNull Set<String> resolvedFiles, // mutable
    @NotNull Set<ResolvedArtifactResult> configurationDependencies,
    @NotNull AuxiliaryConfigurationArtifacts auxiliaryArtifacts,
    @NotNull Map<ComponentIdentifier, ModuleVersionIdentifier> moduleVersions,
    @NotNull ResolutionResult resolutionResult,
    boolean hasFailedToTransformDependencies
  ) {
    Collection<ExternalDependency> artifactDependencies = new LinkedHashSet<>();
    Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies = new HashMap<>();
    Set<String> resolvedVariants = new HashSet<>();
    for (ResolvedArtifactResult artifact : configurationDependencies) {
      ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
      boolean isProjectArtifact = componentIdentifier instanceof ProjectComponentIdentifier;
      boolean isModuleArtifact = componentIdentifier instanceof ModuleComponentIdentifier;
      boolean isLibraryBinaryArtifact = componentIdentifier instanceof LibraryBinaryIdentifier;
      if (!isProjectArtifact && !isModuleArtifact && !isLibraryBinaryArtifact) {
        // file collection dependencies are handled by resolveOtherFileDependencies
        continue;
      }
      String configurationName = artifact.getVariant().getDisplayName();
      resolvedVariants.add(getVariantKey(componentIdentifier, configurationName));
      File artifactFile = resolveArtifactFile(resolvedFiles, artifact.getFile());
      if (artifactFile == null) {
        continue;
      }
      if (isProjectArtifact) {
        ExternalDependency dependency = resolveProjectDependency(
          resolvedProjectDependencies, artifact, artifactFile, (ProjectComponentIdentifier)componentIdentifier, moduleVersions
        );
        if (dependency != null) {
          artifactDependencies.add(dependency);
        }
      }
      else {
        ExternalDependency dependency = resolveLibraryDependency(artifact, artifactFile, auxiliaryArtifacts, moduleVersions);
        if (dependency != null) {
          artifactDependencies.add(dependency);
        }
      }
    }
    if (hasFailedToTransformDependencies) {
      artifactDependencies.addAll(
        resolveFailedToTransformProjectDependencies(resolvedProjectDependencies, resolutionResult, resolvedVariants)
      );
    }
    return artifactDependencies;
  }

  /**
   * Sorts the artifacts in the order of the legacy ResolvedDependency graph API.
   * The graph API orders the components breadth-first, starting from the first level dependencies in the declaration order.
   * It orders the artifacts of one component by name, classifier, extension, and type.
   * Some consumers of the dependency model rely on this order.
   */
  private @NotNull Set<ResolvedArtifactResult> sortArtifactsInLegacyOrder(
    @NotNull Set<ResolvedArtifactResult> artifacts,
    @NotNull ResolutionResult resolutionResult
  ) {
    if (artifacts.size() < 2) {
      return artifacts;
    }
    Map<ComponentIdentifier, Integer> componentArtifactCounts = new HashMap<>();
    for (ResolvedArtifactResult artifact : artifacts) {
      componentArtifactCounts.merge(artifact.getId().getComponentIdentifier(), 1, Integer::sum);
    }
    Map<ComponentIdentifier, Integer> componentOrder = getLegacyComponentOrder(resolutionResult);
    int unknownComponentOrder = componentOrder.size();
    Map<ResolvedArtifactResult, LegacyArtifactSortKey> sortKeys = new HashMap<>();
    for (ResolvedArtifactResult artifact : artifacts) {
      boolean hasSiblingArtifacts = componentArtifactCounts.get(artifact.getId().getComponentIdentifier()) > 1;
      sortKeys.put(artifact, getArtifactSortKey(artifact, hasSiblingArtifacts));
    }
    List<ResolvedArtifactResult> sortedArtifacts = new ArrayList<>(artifacts);
    sortedArtifacts.sort((artifact1, artifact2) -> {
      int order1 = componentOrder.getOrDefault(artifact1.getId().getComponentIdentifier(), unknownComponentOrder);
      int order2 = componentOrder.getOrDefault(artifact2.getId().getComponentIdentifier(), unknownComponentOrder);
      if (order1 != order2) {
        return Integer.compare(order1, order2);
      }
      LegacyArtifactSortKey key1 = sortKeys.get(artifact1);
      LegacyArtifactSortKey key2 = sortKeys.get(artifact2);
      int diff = key1.myName.compareTo(key2.myName);
      if (diff != 0) return diff;
      diff = compareNullsFirst(key1.myClassifier, key2.myClassifier);
      if (diff != 0) return diff;
      diff = compareNullsFirst(key1.myExtension, key2.myExtension);
      if (diff != 0) return diff;
      return compareNullsFirst(key1.myType, key2.myType);
    });
    return new LinkedHashSet<>(sortedArtifacts);
  }

  private static @NotNull Map<ComponentIdentifier, Integer> getLegacyComponentOrder(@NotNull ResolutionResult resolutionResult) {
    Map<ComponentIdentifier, Integer> componentOrder = new HashMap<>();
    Deque<ResolvedComponentResult> queue = new ArrayDeque<>();
    for (DependencyResult dependencyResult : resolutionResult.getRoot().getDependencies()) {
      if (dependencyResult instanceof ResolvedDependencyResult) {
        queue.add(((ResolvedDependencyResult)dependencyResult).getSelected());
      }
    }
    while (!queue.isEmpty()) {
      ResolvedComponentResult component = queue.removeFirst();
      if (componentOrder.putIfAbsent(component.getId(), componentOrder.size()) != null) {
        continue;
      }
      for (DependencyResult dependencyResult : component.getDependencies()) {
        if (dependencyResult instanceof ResolvedDependencyResult) {
          queue.add(((ResolvedDependencyResult)dependencyResult).getSelected());
        }
      }
    }
    return componentOrder;
  }

  private @NotNull LegacyArtifactSortKey getArtifactSortKey(@NotNull ResolvedArtifactResult artifact, boolean precise) {
    ComponentArtifactIdentifier artifactIdentifier = artifact.getId();
    if (artifactIdentifier instanceof DefaultModuleComponentArtifactIdentifier) {
      DefaultModuleComponentArtifactIdentifier moduleArtifactIdentifier = (DefaultModuleComponentArtifactIdentifier)artifactIdentifier;
      return new LegacyArtifactSortKey(moduleArtifactIdentifier.getName().getName(),
                                       moduleArtifactIdentifier.getName().getClassifier(),
                                       moduleArtifactIdentifier.getName().getExtension(),
                                       moduleArtifactIdentifier.getName().getType());
    }
    if (precise) {
      PublishArtifact publishArtifact = findPublishArtifact(artifact);
      if (publishArtifact != null) {
        return new LegacyArtifactSortKey(publishArtifact.getName(), publishArtifact.getClassifier(),
                                         publishArtifact.getExtension(), publishArtifact.getType());
      }
    }
    String fileName = artifact.getFile().getName();
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex < 0) {
      return new LegacyArtifactSortKey(fileName, null, null, null);
    }
    String extension = fileName.substring(dotIndex + 1);
    return new LegacyArtifactSortKey(fileName.substring(0, dotIndex), null, extension, extension);
  }

  private @Nullable PublishArtifact findPublishArtifact(@NotNull ResolvedArtifactResult artifact) {
    ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
    if (!(componentIdentifier instanceof ProjectComponentIdentifier)) {
      return null;
    }
    Project project = myProject.findProject(((ProjectComponentIdentifier)componentIdentifier).getProjectPath());
    if (project == null) {
      return null;
    }
    File artifactFile = artifact.getFile();
    for (Configuration configuration : project.getConfigurations()) {
      for (PublishArtifact publishArtifact : configuration.getAllArtifacts()) {
        if (artifactFile.equals(publishArtifact.getFile())) {
          return publishArtifact;
        }
      }
    }
    return null;
  }

  private static int compareNullsFirst(@Nullable String value1, @Nullable String value2) {
    if (value1 == null) {
      return value2 == null ? 0 : -1;
    }
    if (value2 == null) {
      return 1;
    }
    return value1.compareTo(value2);
  }

  private static final class LegacyArtifactSortKey {
    private final @NotNull String myName;
    private final @Nullable String myClassifier;
    private final @Nullable String myExtension;
    private final @Nullable String myType;

    private LegacyArtifactSortKey(@NotNull String name, @Nullable String classifier, @Nullable String extension, @Nullable String type) {
      myName = name;
      myClassifier = classifier;
      myExtension = extension;
      myType = type;
    }
  }

  private static @NotNull String getVariantKey(
    @NotNull ComponentIdentifier componentIdentifier,
    @NotNull String configurationName
  ) {
    String componentKey;
    if (componentIdentifier instanceof ProjectComponentIdentifier) {
      ProjectComponentIdentifier projectComponentIdentifier = (ProjectComponentIdentifier)componentIdentifier;
      componentKey = getBuildName(projectComponentIdentifier) + "_" + projectComponentIdentifier.getProjectPath();
    }
    else {
      componentKey = componentIdentifier.getDisplayName();
    }
    return componentKey + "|" + configurationName;
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
    @NotNull ProjectComponentIdentifier projectComponentIdentifier,
    @NotNull String configurationName
  ) {
    String buildName = getBuildName(projectComponentIdentifier);
    String projectPath = projectComponentIdentifier.getProjectPath();
    return buildName + "_" + projectPath + "_" + configurationName;
  }

  // Returns null if artifact was already resolved
  private @Nullable DefaultExternalProjectDependency resolveProjectDependency(
    @NotNull Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies, // mutable
    @NotNull ResolvedArtifactResult artifact,
    @NotNull File artifactFile,
    @NotNull ProjectComponentIdentifier projectComponentIdentifier,
    @NotNull Map<ComponentIdentifier, ModuleVersionIdentifier> moduleVersions
  ) {
    String configurationName = artifact.getVariant().getDisplayName();
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

    ModuleVersionIdentifier moduleVersion = moduleVersions.get(projectComponentIdentifier);
    projectDependency.setName(projectComponentIdentifier.getProjectName());
    projectDependency.setGroup(moduleVersion == null ? "unspecified" : moduleVersion.getGroup());
    projectDependency.setVersion(moduleVersion == null ? "unspecified" : moduleVersion.getVersion());
    projectDependency.setProjectPath(projectComponentIdentifier.getProjectPath());
    projectDependency.setConfigurationName(configurationName);
    projectDependency.setProjectDependencyArtifacts(Collections.singleton(artifactFile));
    projectDependency.setProjectDependencyArtifactsSources(mySourceSetArtifactIndex.findArtifactSources(artifactFile));

    return projectDependency;
  }

  private static @Nullable DefaultExternalLibraryDependency resolveLibraryDependency(
    @NotNull ResolvedArtifactResult artifact,
    @NotNull File artifactFile,
    @NotNull AuxiliaryConfigurationArtifacts auxiliaryArtifacts,
    @NotNull Map<ComponentIdentifier, ModuleVersionIdentifier> moduleVersions
  ) {
    ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
    String group;
    String name;
    String version;
    if (componentIdentifier instanceof ModuleComponentIdentifier) {
      ModuleComponentIdentifier moduleComponentIdentifier = (ModuleComponentIdentifier)componentIdentifier;
      group = moduleComponentIdentifier.getGroup();
      name = moduleComponentIdentifier.getModule();
      version = moduleComponentIdentifier.getVersion();
    }
    else {
      ModuleVersionIdentifier moduleVersionIdentifier = moduleVersions.get(artifact.getVariant().getOwner());
      if (moduleVersionIdentifier == null) {
        return null;
      }
      group = moduleVersionIdentifier.getGroup();
      name = moduleVersionIdentifier.getName();
      version = moduleVersionIdentifier.getVersion();
    }

    DefaultExternalLibraryDependency libraryDependency = new DefaultExternalLibraryDependency();
    libraryDependency.setName(name);
    libraryDependency.setGroup(group);
    libraryDependency.setVersion(version);
    libraryDependency.setFile(artifactFile);

    File sourcesFile = auxiliaryArtifacts.getSources(componentIdentifier, artifactFile);
    if (sourcesFile != null) {
      libraryDependency.setSource(sourcesFile);
    }
    File javadocFile = auxiliaryArtifacts.getJavadoc(componentIdentifier, artifactFile);
    if (javadocFile != null) {
      libraryDependency.setJavadoc(javadocFile);
    }
    String extension = getArtifactExtension(artifact);
    if (extension != null) {
      libraryDependency.setPackaging(extension);
    }
    libraryDependency.setClassifier(getArtifactClassifier(artifact));

    return libraryDependency;
  }

  private static @Nullable String getArtifactExtension(@NotNull ResolvedArtifactResult artifact) {
    ComponentArtifactIdentifier artifactIdentifier = artifact.getId();
    if (artifactIdentifier instanceof DefaultModuleComponentArtifactIdentifier) {
      return ((DefaultModuleComponentArtifactIdentifier)artifactIdentifier).getName().getExtension();
    }
    String fileName = artifact.getFile().getName();
    int dotIndex = fileName.lastIndexOf('.');
    return dotIndex < 0 ? null : fileName.substring(dotIndex + 1);
  }

  private static @Nullable String getArtifactClassifier(@NotNull ResolvedArtifactResult artifact) {
    ComponentArtifactIdentifier artifactIdentifier = artifact.getId();
    if (artifactIdentifier instanceof DefaultModuleComponentArtifactIdentifier) {
      return ((DefaultModuleComponentArtifactIdentifier)artifactIdentifier).getName().getClassifier();
    }
    return null;
  }

  // Returns dependencies which artifacts have failed to transform, with the artifacts taken from the target project
  private @NotNull Collection<ExternalProjectDependency> resolveFailedToTransformProjectDependencies(
    @NotNull Map<String, DefaultExternalProjectDependency> resolvedProjectDependencies, // mutable
    @NotNull ResolutionResult resolutionResult,
    @NotNull Set<String> resolvedVariants
  ) {
    Collection<ExternalProjectDependency> failedToTransformDependencies = new ArrayList<>();
    for (DependencyResult dependencyResult : resolutionResult.getAllDependencies()) {
      if (!(dependencyResult instanceof ResolvedDependencyResult)) continue;
      ResolvedDependencyResult resolvedDependencyResult = (ResolvedDependencyResult)dependencyResult;
      ComponentSelector requested = resolvedDependencyResult.getRequested();
      if (!(requested instanceof ProjectComponentSelector)) continue;
      ResolvedComponentResult selected = resolvedDependencyResult.getSelected();
      ModuleVersionIdentifier selectedVersion = selected.getModuleVersion();
      for (ResolvedVariantResult variant : selected.getVariants()) {
        String configurationName = variant.getDisplayName();
        // skip variants which contributed at least one artifact, and variants already processed for another dependency
        if (!resolvedVariants.add(getVariantKey(selected.getId(), configurationName))) continue;

        String projectPath = ((ProjectComponentSelector)requested).getProjectPath();
        String key = projectPath + "_" + configurationName;
        if (resolvedProjectDependencies.containsKey(key)) continue;

        DefaultExternalProjectDependency projectDependency = new DefaultExternalProjectDependency();
        resolvedProjectDependencies.put(key, projectDependency);
        String projectName = Path.path(projectPath).getName();
        projectDependency.setName(projectName);
        projectDependency.setGroup(selectedVersion == null ? "unspecified" : selectedVersion.getGroup());
        projectDependency.setVersion(selectedVersion == null ? "unspecified" : selectedVersion.getVersion());
        projectDependency.setProjectPath(projectPath);
        projectDependency.setConfigurationName(configurationName);

        Project project = myProject.findProject(projectPath);
        if (project == null) continue;
        Configuration configuration = project.getConfigurations().findByName(configurationName);
        if (configuration == null) continue;
        Set<File> projectArtifacts = configuration.getArtifacts().getFiles().getFiles();
        projectDependency.setProjectDependencyArtifacts(projectArtifacts);
        projectDependency.setProjectDependencyArtifactsSources(mySourceSetArtifactIndex.findArtifactSources(projectArtifacts));
        failedToTransformDependencies.add(projectDependency);
      }
    }
    return failedToTransformDependencies;
  }

  private @NotNull AuxiliaryArtifactResolver getAuxiliaryArtifactResolver(
    @NotNull Set<ResolvedArtifactResult> configurationDependencies,
    @NotNull Set<String> allowedDependencyGroups
  ) {
    String useLegacyResolverPropertyValue = System.getProperty("idea.gradle.daemon.legacy.dependency.resolver", "false");
    boolean useLegacyResolver = Boolean.parseBoolean(useLegacyResolverPropertyValue);
    if (useLegacyResolver || GradleVersionUtil.isCurrentGradleOlderThan("7.5") || isIvyRepositoryUsed(myProject)) {
      return new LegacyAuxiliaryArtifactResolver(myProject, myDownloadPolicy, getModuleComponents(configurationDependencies));
    }
    return new AuxiliaryArtifactResolverImpl(myProject, myDownloadPolicy, allowedDependencyGroups);
  }

  private static @NotNull Collection<ComponentIdentifier> getModuleComponents(
    @NotNull Set<ResolvedArtifactResult> configurationDependencies
  ) {
    Set<ComponentIdentifier> moduleComponents = new LinkedHashSet<>();
    for (ResolvedArtifactResult artifact : configurationDependencies) {
      ComponentIdentifier componentIdentifier = artifact.getId().getComponentIdentifier();
      if (componentIdentifier instanceof ModuleComponentIdentifier) {
        moduleComponents.add(componentIdentifier);
      }
    }
    return moduleComponents;
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
    Set<String> allowedDependencyGroups
  ) {
    Collection<ExternalDependency> result = new LinkedHashSet<>();
    for (DependencyResult dependencyResult : resolutionResult.getAllDependencies()) {
      if (!(dependencyResult instanceof UnresolvedDependencyResult)) continue;
      ComponentSelector attemptedSelector = ((UnresolvedDependencyResult)dependencyResult).getAttempted();
      if (!(attemptedSelector instanceof ModuleComponentSelector)) continue;
      ModuleComponentSelector selector = (ModuleComponentSelector)attemptedSelector;
      if (!allowedDependencyGroups.isEmpty() && !allowedDependencyGroups.contains(selector.getGroup())) {
        continue;
      }
      Throwable problem = ((UnresolvedDependencyResult)dependencyResult).getFailure();
      if (problem.getCause() != null) {
        problem = problem.getCause();
      }
      DefaultUnresolvedExternalDependency dependency = new DefaultUnresolvedExternalDependency();
      dependency.setName(selector.getModule());
      dependency.setGroup(selector.getGroup());
      dependency.setVersion(selector.getVersion());
      dependency.setFailureMessage(problem.getMessage());
      result.add(dependency);
    }
    return result;
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
