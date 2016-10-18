/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.data.management.copy;

import gobblin.commit.CommitStep;
import gobblin.data.management.copy.entities.PrePublishStep;
import gobblin.data.management.dataset.DatasetUtils;
import gobblin.dataset.FileSystemDataset;
import gobblin.util.PathUtils;
import gobblin.util.FileListUtils;
import gobblin.util.commit.DeleteFileCommitStep;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;


/**
 * Implementation of {@link CopyableDataset} that creates a {@link CopyableFile} for every file that is a descendant if
 * the root directory.
 */
public class RecursiveCopyableDataset implements CopyableDataset, FileSystemDataset {

  private static final String CONFIG_PREFIX = CopyConfiguration.COPY_PREFIX + ".recursive";
  /** Like -update in distcp. Will update files that are different between source and target, and skip files already in target. */
  public static final String UPDATE_KEY = CONFIG_PREFIX + ".update";
  /** Like -delete in distcp. Will delete files in target that don't exist in source. */
  public static final String DELETE_KEY = CONFIG_PREFIX + ".delete";
  /** If true, will delete newly empty directories up to the dataset root. */
  public static final String DELETE_EMPTY_DIRECTORIES_KEY = CONFIG_PREFIX + ".deleteEmptyDirectories";

  private final Path rootPath;
  private final FileSystem fs;
  private final PathFilter pathFilter;
  // Glob used to find this dataset
  private final Path glob;
  private final CopyableFileFilter copyableFileFilter;
  private final boolean update;
  private final boolean delete;
  private final boolean deleteEmptyDirectories;
  private final Properties properties;

  public RecursiveCopyableDataset(final FileSystem fs, Path rootPath, Properties properties, Path glob) {

    this.rootPath = PathUtils.getPathWithoutSchemeAndAuthority(rootPath);
    this.fs = fs;

    this.pathFilter = DatasetUtils.instantiatePathFilter(properties);
    this.copyableFileFilter = DatasetUtils.instantiateCopyableFileFilter(properties);
    this.glob = glob;

    this.update = Boolean.parseBoolean(properties.getProperty(UPDATE_KEY));
    this.delete = Boolean.parseBoolean(properties.getProperty(DELETE_KEY));
    this.deleteEmptyDirectories = Boolean.parseBoolean(properties.getProperty(DELETE_EMPTY_DIRECTORIES_KEY));
    this.properties = properties;
  }

  @Override
  public Collection<? extends CopyEntity> getCopyableFiles(FileSystem targetFs, CopyConfiguration configuration)
      throws IOException {

    Path nonGlobSearchPath = PathUtils.deepestNonGlobPath(this.glob);
    Path targetPath = new Path(configuration.getPublishDir(), PathUtils.relativizePath(this.rootPath, nonGlobSearchPath));

    Map<Path, FileStatus> filesInSource = createPathMap(getFilesAtPath(this.fs, this.rootPath, this.pathFilter), this.rootPath);
    Map<Path, FileStatus> filesInTarget = createPathMap(getFilesAtPath(targetFs, targetPath, this.pathFilter), targetPath);

    Set<Path> inSourceNotInTarget = Sets.difference(filesInSource.keySet(), filesInTarget.keySet());
    Set<Path> inTargetNotInSource = Sets.difference(filesInTarget.keySet(), filesInSource.keySet());
    Set<Path> inBoth = Sets.intersection(filesInSource.keySet(), filesInTarget.keySet());

    List<CopyEntity> copyEntities = Lists.newArrayList();

    List<Path> toCopy = Lists.newArrayList();
    List<Path> toDelete = Lists.newArrayList();

    for (Path path : inBoth) {
      if (!sameFile(filesInSource.get(path), filesInTarget.get(path))) {
        toCopy.add(path);
        toDelete.add(path);
      }
    }
    if (!this.update && !toCopy.isEmpty()) {
      throw new IOException("Some files need to be copied but they already exist in the destination. "
          + "Aborting because not running in update mode.");
    }

    for (Path path : inSourceNotInTarget) {
      toCopy.add(path);
    }

    if (this.delete) {
      for (Path path : inTargetNotInSource) {
        toDelete.add(path);
      }
    }

    List<CopyableFile> copyableFiles = Lists.newArrayList();
    for (Path path : toCopy) {
      FileStatus file = filesInSource.get(path);
      Path filePathRelativeToSearchPath = PathUtils.relativizePath(file.getPath(), nonGlobSearchPath);
      Path thisTargetPath = new Path(configuration.getPublishDir(), filePathRelativeToSearchPath);

      copyableFiles.add(CopyableFile.fromOriginAndDestination(this.fs, file, thisTargetPath, configuration)
          .fileSet(datasetURN())
          .ancestorsOwnerAndPermission(CopyableFile.resolveReplicatedOwnerAndPermissionsRecursively(this.fs,
              file.getPath().getParent(), nonGlobSearchPath, configuration))
          .build());
    }
    copyEntities.addAll(this.copyableFileFilter.filter(this.fs, targetFs, copyableFiles));

    if (!toDelete.isEmpty()) {
      List<FileStatus> statusesToDelete = Lists.newArrayList();
      for (Path path : toDelete) {
        statusesToDelete.add(filesInTarget.get(path));
      }

      CommitStep step =
          new DeleteFileCommitStep(targetFs, statusesToDelete, this.properties,
              this.deleteEmptyDirectories ? Optional.of(targetPath) : Optional.<Path>absent());

      copyEntities.add(new PrePublishStep(datasetURN(), Maps.<String, String>newHashMap(), step, 1));
    }

    return copyEntities;
  }

  @VisibleForTesting
  protected List<FileStatus> getFilesAtPath(FileSystem fs, Path path, PathFilter fileFilter) throws IOException {
    try {
      return FileListUtils.listFilesRecursively(fs, path, fileFilter);
    } catch (FileNotFoundException fnfe) {
      return Lists.newArrayList();
    }
  }

  @Override
  public Path datasetRoot() {
    return this.rootPath;
  }

  @Override
  public String datasetURN() {
    return datasetRoot().toString();
  }

  private Map<Path, FileStatus> createPathMap(List<FileStatus> files, Path prefix) {
    Map<Path, FileStatus> map = Maps.newHashMap();
    for (FileStatus status : files) {
      map.put(PathUtils.relativizePath(status.getPath(), prefix), status);
    }
    return map;
  }

  private static boolean sameFile(FileStatus fileInSource, FileStatus fileInTarget) {
    return fileInTarget.getLen() == fileInSource.getLen()
        && fileInSource.getModificationTime() <= fileInTarget.getModificationTime();
  }
}
