/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.application.cluster;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IAtomicReference;
import com.hazelcast.core.ILock;
import com.hazelcast.core.MapEvent;
import com.hazelcast.core.ReplicatedMap;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.sonar.application.config.AppSettings;
import org.sonar.application.AppState;
import org.sonar.application.AppStateListener;
import org.sonar.process.ProcessId;

public class AppStateClusterImpl implements AppState, AutoCloseable {
  static final String OPERATIONAL_PROCESSES = "operational_processes";
  static final String LEADER = "leader";

  private final List<AppStateListener> listeners = new ArrayList<>();
  private final ReplicatedMap<ClusterProcess, Boolean> operationalProcesses;
  private final String listenerUuid;

  final HazelcastInstance hzInstance;

  public AppStateClusterImpl(AppSettings appSettings) {
    ClusterProperties clusterProperties = new ClusterProperties(appSettings);
    clusterProperties.validate();

    if (!clusterProperties.isEnabled()) {
      throw new IllegalStateException("Cluster is not enabled on this instance");
    }

    Config hzConfig = new Config();
    try {
      hzConfig.setInstanceName(InetAddress.getLocalHost().getHostName());
    } catch (UnknownHostException e) {
      // Ignore it
    }

    hzConfig.getGroupConfig().setName(clusterProperties.getName());

    // Configure the network instance
    NetworkConfig netConfig = hzConfig.getNetworkConfig();
    netConfig.setPort(clusterProperties.getPort());

    if (!clusterProperties.getInterfaces().isEmpty()) {
      netConfig.getInterfaces()
        .setEnabled(true)
        .setInterfaces(clusterProperties.getInterfaces());
    }

    // Only allowing TCP/IP configuration
    JoinConfig joinConfig = netConfig.getJoin();
    joinConfig.getAwsConfig().setEnabled(false);
    joinConfig.getMulticastConfig().setEnabled(false);
    joinConfig.getTcpIpConfig().setEnabled(true);
    joinConfig.getTcpIpConfig().setMembers(clusterProperties.getMembers());

    // Tweak HazelCast configuration
    hzConfig
      // Increase the number of tries
      .setProperty("hazelcast.tcp.join.port.try.count", "10")
      // Don't bind on all interfaces
      .setProperty("hazelcast.socket.bind.any", "false")
      // Don't phone home
      .setProperty("hazelcast.phone.home.enabled", "false")
      // Use slf4j for logging
      .setProperty("hazelcast.logging.type", "slf4j");

    // We are not using the partition group of Hazelcast, so disabling it
    hzConfig.getPartitionGroupConfig().setEnabled(false);

    hzInstance = Hazelcast.newHazelcastInstance(hzConfig);
    operationalProcesses = hzInstance.getReplicatedMap(OPERATIONAL_PROCESSES);
    listenerUuid = operationalProcesses.addEntryListener(new OperationalProcessListener());
  }

  @Override
  public void addListener(@Nonnull AppStateListener listener) {
    listeners.add(listener);
  }

  @Override
  public boolean isOperational(@Nonnull ProcessId processId) {
    for (Map.Entry<ClusterProcess, Boolean> entry : operationalProcesses.entrySet()) {
      if (entry.getKey().getProcessId().equals(processId) && entry.getValue()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void setOperational(@Nonnull ProcessId processId) {
    operationalProcesses.put(new ClusterProcess(getLocalUuid(), processId), Boolean.TRUE);
  }

  @Override
  public boolean tryToLockWebLeader() {
    IAtomicReference<String> leader = hzInstance.getAtomicReference(LEADER);
    if (leader.get() == null) {
      ILock lock = hzInstance.getLock(LEADER);
      lock.lock();
      try {
        if (leader.get() == null) {
          leader.set(getLocalUuid());
          return true;
        } else {
          return false;
        }
      } finally {
        lock.unlock();
      }
    } else {
      return false;
    }
  }

  @Override
  public void reset() {
    throw new IllegalStateException("state reset is not supported in cluster mode");
  }

  @Override
  public void close() {
    if (hzInstance != null) {
      operationalProcesses.removeEntryListener(listenerUuid);
      operationalProcesses.keySet().forEach(
        clusterNodeProcess -> {
          if (clusterNodeProcess.getNodeUuid().equals(getLocalUuid())) {
            operationalProcesses.remove(clusterNodeProcess);
          }
        });
      hzInstance.shutdown();
    }
  }

  private String getLocalUuid() {
    return hzInstance.getLocalEndpoint().getUuid();
  }

  private class OperationalProcessListener implements EntryListener<ClusterProcess, Boolean> {

    @Override
    public void entryAdded(EntryEvent<ClusterProcess, Boolean> event) {
      if (event.getValue()) {
        listeners.forEach(appStateListener -> appStateListener.onAppStateOperational(event.getKey().getProcessId()));
      }
    }

    @Override
    public void entryRemoved(EntryEvent<ClusterProcess, Boolean> event) {
      // Ignore it
    }

    @Override
    public void entryUpdated(EntryEvent<ClusterProcess, Boolean> event) {
      if (event.getValue()) {
        listeners.forEach(appStateListener -> appStateListener.onAppStateOperational(event.getKey().getProcessId()));
      }
    }

    @Override
    public void entryEvicted(EntryEvent<ClusterProcess, Boolean> event) {
      // Ignore it
    }

    @Override
    public void mapCleared(MapEvent event) {
      // Ignore it
    }

    @Override
    public void mapEvicted(MapEvent event) {
      // Ignore it
    }
  }
}
