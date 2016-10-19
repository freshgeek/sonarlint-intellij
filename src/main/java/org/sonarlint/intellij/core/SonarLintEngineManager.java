/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.core;

import com.google.common.base.Preconditions;
import com.intellij.openapi.components.ApplicationComponent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.http.annotation.ThreadSafe;
import org.jetbrains.annotations.NotNull;
import org.sonarlint.intellij.config.global.SonarLintGlobalSettings;
import org.sonarlint.intellij.config.global.SonarQubeServer;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine;
import org.sonarsource.sonarlint.core.client.api.connected.ModuleUpdateStatus;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneSonarLintEngine;

@ThreadSafe
public class SonarLintEngineManager implements ApplicationComponent {
  private final SonarLintGlobalSettings settings;
  private final SonarLintEngineFactory engineFactory;
  private Map<String, ConnectedSonarLintEngine> engines;
  private StandaloneSonarLintEngine standalone;
  private Set<String> configuredStorageIds;

  public SonarLintEngineManager(SonarLintGlobalSettings settings, SonarLintEngineFactory engineFactory) {
    this.settings = settings;
    this.engineFactory = engineFactory;
  }

  @Override
  public void initComponent() {
    configuredStorageIds = new HashSet<>();
    reloadServerNames();
    engines = new HashMap<>();
  }

  /**
   * Immediately removes and asynchronously stops all {@link ConnectedSonarLintEngine} corresponding to server IDs that were removed.
   */
  public synchronized void reloadServers() {
    reloadServerNames();
    Iterator<Map.Entry<String, ConnectedSonarLintEngine>> it = engines.entrySet().iterator();

    while (it.hasNext()) {
      Map.Entry<String, ConnectedSonarLintEngine> e = it.next();
      if (!configuredStorageIds.contains(e.getKey())) {
        stopInThread(e.getValue());
        it.remove();
      }
    }
  }

  public synchronized ConnectedSonarLintEngine getConnectedEngine(String serverId) {
    if (!engines.containsKey(serverId)) {
      ConnectedSonarLintEngine engine = engineFactory.createEngine(serverId);
      engines.put(serverId, engine);
    }

    return engines.get(serverId);
  }

  public synchronized StandaloneSonarLintEngine getStandaloneEngine() {
    if (standalone == null) {
      standalone = engineFactory.createEngine();
    }
    return standalone;
  }

  public synchronized ConnectedSonarLintEngine getConnectedEngine(SonarLintProjectNotifications notifications, String serverId, String projectKey) {
    Preconditions.checkNotNull(notifications, "notifications");
    Preconditions.checkNotNull(serverId, "serverId");
    Preconditions.checkNotNull(projectKey, "projectKey");

    if (!configuredStorageIds.contains(serverId)) {
      notifications.notifyServerIdInvalid();
      throw new IllegalStateException("Invalid server name: " + serverId);
    }

    ConnectedSonarLintEngine engine = getConnectedEngine(serverId);
    checkConnectedEngineStatus(engine, notifications, serverId, projectKey);
    return engine;
  }

  private static void stopInThread(final ConnectedSonarLintEngine engine) {
    new Thread("stop-sonarlint-engine") {
      @Override
      public void run() {
        engine.stop(false);
      }
    }.start();
  }

  private void checkConnectedEngineStatus(ConnectedSonarLintEngine engine, SonarLintProjectNotifications notifications, String serverId, String projectKey) {
    // Check if engine's global storage is OK
    ConnectedSonarLintEngine.State state = engine.getState();
    if (state != ConnectedSonarLintEngine.State.UPDATED) {
      if (state != ConnectedSonarLintEngine.State.NEED_UPDATE) {
        notifications.notifyServerNotUpdated();
      } else if (state != ConnectedSonarLintEngine.State.NEVER_UPDATED) {
        notifications.notifyServerNeedsUpdate(serverId);
      }
      throw new IllegalStateException("Server is not updated: " + serverId);
    }

    // Check if module's storage is OK. Global storage was updated and all project's binding that were open too,
    // but we might have now opened a new project with a different binding.
    ModuleUpdateStatus moduleUpdateStatus = engine.getModuleUpdateStatus(projectKey);

    if (moduleUpdateStatus == null) {
      notifications.notifyModuleInvalid();
      throw new IllegalStateException("Project is bound to a module that doesn't exist: " + projectKey);
    } else if (moduleUpdateStatus.isStale()) {
      notifications.notifyModuleStale();
      throw new IllegalStateException("Stale module's storage: " + projectKey);
    }
  }

  private void reloadServerNames() {
    configuredStorageIds = settings.getSonarQubeServers().stream()
      .map(SonarQubeServer::getName)
      .collect(Collectors.toSet());
  }

  @Override
  public void disposeComponent() {
    for (ConnectedSonarLintEngine e : engines.values()) {
      e.stop(false);
    }
    engines.clear();
    if (standalone != null) {
      standalone.stop();
      standalone = null;
    }
  }

  @NotNull
  @Override
  public String getComponentName() {
    return "SonarLintEngineManager";
  }
}
