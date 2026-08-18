// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface GradleSourceSetModel extends Serializable {

  @Nullable Integer getToolchainVersion();

  @Nullable String getSourceCompatibility();

  @Nullable String getTargetCompatibility();

  @NotNull List<File> getTaskArtifacts();

  /**
   * @deprecated Collected for every configuration of the project, but consumed only for the
   * {@code "default"} configuration. Always empty in newer implementations.
   * Use {@link #getDefaultConfigurationArtifacts()} instead.
   */
  @Deprecated
  @NotNull Map<String, Set<File>> getConfigurationArtifacts();

  /**
   * The artifact files of the project's {@code "default"} configuration
   * (the main publication artifacts of the module).
   */
  @NotNull Set<File> getDefaultConfigurationArtifacts();

  @NotNull Map<String, ? extends ExternalSourceSet> getSourceSets();

  @NotNull List<File> getAdditionalArtifacts();
}
