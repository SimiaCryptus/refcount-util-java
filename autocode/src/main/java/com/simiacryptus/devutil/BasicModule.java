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

import com.google.inject.AbstractModule;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.repository.ArtifactTransferListener;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.eclipse.aether.RepositorySystemSession;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

class BasicModule extends AbstractModule {
  private final ArtifactRepository repository;

  BasicModule(final ArtifactRepository repository) {
    this.repository = repository;
  }

  protected void configure() {
    this.bind(ILoggerFactory.class).toInstance(LoggerFactory.getILoggerFactory());
    this.bind(RepositorySystem.class).toInstance(new RepositorySystem() {
      @Override
      public Artifact createArtifact(final String groupId, final String artifactId, final String version, final String packaging) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public Artifact createArtifact(final String groupId, final String artifactId, final String version, final String scope, final String type) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public Artifact createProjectArtifact(final String groupId, final String artifactId, final String version) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public Artifact createArtifactWithClassifier(final String groupId, final String artifactId, final String version, final String type, final String classifier) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public Artifact createPluginArtifact(final Plugin plugin) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public Artifact createDependencyArtifact(final Dependency dependency) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public ArtifactRepository buildArtifactRepository(final Repository r) {
        return repository;
      }

      @Override
      public ArtifactRepository createDefaultRemoteRepository() {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public ArtifactRepository createDefaultLocalRepository() {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public ArtifactRepository createLocalRepository(final File localRepository) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public ArtifactRepository createArtifactRepository(final String id, final String url1, final ArtifactRepositoryLayout repositoryLayout, final ArtifactRepositoryPolicy snapshots, final ArtifactRepositoryPolicy releases) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public List<ArtifactRepository> getEffectiveRepositories(final List<ArtifactRepository> repositories) {
        return Arrays.asList(repository);
      }

      @Override
      public Mirror getMirror(final ArtifactRepository repository1, final List<Mirror> mirrors) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public void injectMirror(final List<ArtifactRepository> repositories, final List<Mirror> mirrors) {
      }

      @Override
      public void injectProxy(final List<ArtifactRepository> repositories, final List<Proxy> proxies) {

      }

      @Override
      public void injectAuthentication(final List<ArtifactRepository> repositories, final List<Server> servers) {

      }

      @Override
      public void injectMirror(final RepositorySystemSession session, final List<ArtifactRepository> repositories) {

      }

      @Override
      public void injectProxy(final RepositorySystemSession session, final List<ArtifactRepository> repositories) {

      }

      @Override
      public void injectAuthentication(final RepositorySystemSession session, final List<ArtifactRepository> repositories) {

      }

      @Override
      public ArtifactResolutionResult resolve(final ArtifactResolutionRequest request) {
        if (0 < 1) throw new RuntimeException("Not Implemented");
        return null;
      }

      @Override
      public void publish(final ArtifactRepository repository1, final File source, final String remotePath, final ArtifactTransferListener transferListener) {

      }

      @Override
      public void retrieve(final ArtifactRepository repository1, final File destination, final String remotePath, final ArtifactTransferListener transferListener) {

      }
    });
  }
}
