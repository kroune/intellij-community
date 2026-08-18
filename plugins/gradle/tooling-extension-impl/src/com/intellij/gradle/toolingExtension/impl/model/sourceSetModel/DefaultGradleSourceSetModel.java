// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.gradle.toolingExtension.impl.model.sourceSetModel;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceSet;
import org.jetbrains.plugins.gradle.model.GradleSourceSetModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApiStatus.Internal
public final class DefaultGradleSourceSetModel implements GradleSourceSetModel {

  private @Nullable Integer toolchainVersion;
  private @Nullable String sourceCompatibility;
  private @Nullable String targetCompatibility;
  private @NotNull List<File> taskArtifacts;
  private @NotNull Map<String, Set<File>> configurationArtifacts;
  private @NotNull Set<File> defaultConfigurationArtifacts;
  private @NotNull Map<String, DefaultExternalSourceSet> sourceSets;

  private @NotNull List<File> additionalArtifacts;

  public DefaultGradleSourceSetModel() {
    toolchainVersion = null;
    sourceCompatibility = null;
    targetCompatibility = null;
    taskArtifacts = new ArrayList<>();
    configurationArtifacts = new LinkedHashMap<>();
    defaultConfigurationArtifacts = new LinkedHashSet<>();
    sourceSets = new LinkedHashMap<>();
    additionalArtifacts = new ArrayList<>(0);
  }

  @Override
  public @Nullable Integer getToolchainVersion() {
    return toolchainVersion;
  }

  public void setToolchainVersion(@Nullable Integer javaToolchainVersion) {
    this.toolchainVersion = javaToolchainVersion;
  }

  @Override
  public @Nullable String getSourceCompatibility() {
    return sourceCompatibility;
  }

  public void setSourceCompatibility(@Nullable String sourceCompatibility) {
    this.sourceCompatibility = sourceCompatibility;
  }

  @Override
  public @Nullable String getTargetCompatibility() {
    return targetCompatibility;
  }

  public void setTargetCompatibility(@Nullable String targetCompatibility) {
    this.targetCompatibility = targetCompatibility;
  }

  @Override
  public @NotNull List<File> getTaskArtifacts() {
    return taskArtifacts;
  }

  public void setTaskArtifacts(@NotNull List<File> taskArtifacts) {
    this.taskArtifacts = taskArtifacts;
  }

  private static final Logger LOG = LoggerFactory.getLogger(DefaultGradleSourceSetModel.class);
  private static final Set<String> REPORTED_LEGACY_ACCESS = ConcurrentHashMap.newKeySet();

  /**
   * @deprecated Always empty: per-configuration artifacts are no longer collected.
   * Every access is logged (once per call site) to find remaining consumers.
   */
  @Deprecated
  @Override
  public @NotNull Map<String, Set<File>> getConfigurationArtifacts() {
    logLegacyConfigurationArtifactsAccess();
    return configurationArtifacts;
  }

  private static void logLegacyConfigurationArtifactsAccess() {
    StackTraceElement[] stack = new Throwable().getStackTrace();
    for (StackTraceElement frame : stack) {
      String className = frame.getClassName();
      if (className.equals(DefaultGradleSourceSetModel.class.getName()) ||
          className.equals("org.jetbrains.plugins.gradle.model.DefaultExternalProject")) {
        continue;
      }
      String caller = className + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
      if (REPORTED_LEGACY_ACCESS.add(caller)) {
        LOG.warn("Legacy configuration artifacts accessed by " + caller +
                 " (the map is no longer collected and is always empty)", new Throwable("access stack trace"));
      }
      return;
    }
  }

  public void setConfigurationArtifacts(@NotNull Map<String, Set<File>> configurationArtifacts) {
    this.configurationArtifacts = configurationArtifacts;
  }

  @Override
  public @NotNull Set<File> getDefaultConfigurationArtifacts() {
    return defaultConfigurationArtifacts;
  }

  public void setDefaultConfigurationArtifacts(@NotNull Set<File> defaultConfigurationArtifacts) {
    this.defaultConfigurationArtifacts = defaultConfigurationArtifacts;
  }

  @Override
  public @NotNull Map<String, DefaultExternalSourceSet> getSourceSets() {
    return sourceSets;
  }

  public void setSourceSets(@NotNull Map<String, DefaultExternalSourceSet> sourceSets) {
    this.sourceSets = sourceSets;
  }


  public void setAdditionalArtifacts(@NotNull List<File> additionalArtifacts) {
    this.additionalArtifacts = additionalArtifacts;
  }

  @Override
  public  @NotNull List<File> getAdditionalArtifacts() {
    return additionalArtifacts;
  }

}
