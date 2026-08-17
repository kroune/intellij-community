// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.gradle.toolingExtension.impl.model.dependencyModel;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.model.ExternalDependency;
import org.jetbrains.plugins.gradle.model.ExternalLibraryDependency;
import org.jetbrains.plugins.gradle.model.ExternalMultiLibraryDependency;
import org.jetbrains.plugins.gradle.model.ExternalProjectDependency;
import org.jetbrains.plugins.gradle.model.FileCollectionDependency;
import org.jetbrains.plugins.gradle.model.UnresolvedExternalDependency;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Diagnostic dump of the resolved dependency models, enabled with
 * {@code -Didea.gradle.daemon.dependency.model.dump=<output dir>}.
 * Writes one text file per (project, configuration) into the given directory,
 * appending one line per dependency. Used to compare resolver implementations.
 */
final class GradleDependencyModelDumper {

  private static final String DUMP_DIR_PROPERTY = "idea.gradle.daemon.dependency.model.dump";

  private GradleDependencyModelDumper() {
  }

  static void dump(
    @NotNull Project project,
    @NotNull Configuration configuration,
    @NotNull Collection<ExternalDependency> dependencies
  ) {
    String dumpDir = System.getProperty(DUMP_DIR_PROPERTY);
    if (dumpDir == null || dumpDir.isEmpty()) {
      return;
    }
    try {
      Files.createDirectories(Paths.get(dumpDir));
      String fileName = sanitize(project.getPath()) + "__" + sanitize(configuration.getName()) + ".txt";
      Path dumpFile = Paths.get(dumpDir, fileName);
      List<String> lines = new ArrayList<>(dependencies.size());
      for (ExternalDependency dependency : dependencies) {
        lines.add(toLine(dependency));
      }
      Files.write(dumpFile, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static @NotNull String toLine(@NotNull ExternalDependency dependency) {
    StringBuilder line = new StringBuilder();
    if (dependency instanceof ExternalProjectDependency) {
      ExternalProjectDependency projectDependency = (ExternalProjectDependency)dependency;
      line.append("PROJECT|");
      appendCommon(line, dependency);
      line.append('|').append(nullable(projectDependency.getProjectPath()));
      line.append('|').append(nullable(projectDependency.getConfigurationName()));
      line.append('|').append(joinPaths(projectDependency.getProjectDependencyArtifacts()));
      line.append('|').append(joinPaths(projectDependency.getProjectDependencyArtifactsSources()));
    }
    else if (dependency instanceof ExternalLibraryDependency) {
      ExternalLibraryDependency libraryDependency = (ExternalLibraryDependency)dependency;
      line.append("LIBRARY|");
      appendCommon(line, dependency);
      line.append('|').append(nullable(libraryDependency.getFile()));
      line.append('|').append(nullable(libraryDependency.getSource()));
      line.append('|').append(nullable(libraryDependency.getJavadoc()));
    }
    else if (dependency instanceof ExternalMultiLibraryDependency) {
      ExternalMultiLibraryDependency multiLibraryDependency = (ExternalMultiLibraryDependency)dependency;
      line.append("MULTI_LIBRARY|");
      appendCommon(line, dependency);
      line.append('|').append(joinPaths(multiLibraryDependency.getFiles()));
      line.append('|').append(joinPaths(multiLibraryDependency.getSources()));
      line.append('|').append(joinPaths(multiLibraryDependency.getJavadoc()));
    }
    else if (dependency instanceof FileCollectionDependency) {
      line.append("FILE_COLLECTION|");
      appendCommon(line, dependency);
      line.append('|').append(joinPaths(((FileCollectionDependency)dependency).getFiles()));
    }
    else if (dependency instanceof UnresolvedExternalDependency) {
      line.append("UNRESOLVED|");
      appendCommon(line, dependency);
      line.append('|').append(nullable(((UnresolvedExternalDependency)dependency).getFailureMessage()));
    }
    else {
      line.append("UNKNOWN(").append(dependency.getClass().getName()).append(")|");
      appendCommon(line, dependency);
    }
    return line.toString();
  }

  private static void appendCommon(@NotNull StringBuilder line, @NotNull ExternalDependency dependency) {
    line.append(nullable(dependency.getGroup()));
    line.append('|').append(nullable(dependency.getName()));
    line.append('|').append(nullable(dependency.getVersion()));
    line.append('|').append(nullable(dependency.getScope()));
    line.append('|').append(dependency.getClasspathOrder());
  }

  private static @NotNull String joinPaths(@NotNull Collection<File> files) {
    List<String> paths = new ArrayList<>(files.size());
    for (File file : files) {
      paths.add(file.getPath());
    }
    Collections.sort(paths);
    return String.join(";", paths);
  }

  private static @NotNull String nullable(@Nullable Object value) {
    return value == null ? "<null>" : value.toString().replace('\n', ' ').replace("|", "!");
  }

  private static @NotNull String sanitize(@NotNull String name) {
    return name.replace(':', '_').replace('/', '_');
  }
}
