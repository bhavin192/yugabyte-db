/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.SubTaskGroup;
import com.yugabyte.yw.commissioner.SubTaskGroupQueue;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.commissioner.tasks.subtasks.KubernetesCommandExecutor;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

public class DestroyKubernetesUniverse extends DestroyUniverse {
  public static final Logger LOG = LoggerFactory.getLogger(DestroyKubernetesUniverse.class);

  @Override
  public void run() {
    try {
      // Create the task list sequence.
      subTaskGroupQueue = new SubTaskGroupQueue(userTaskUUID);

      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening.
      Universe universe = null;
      if (params().isForceDelete) {
        universe = forceLockUniverseForUpdate(-1);
      } else {
        universe = lockUniverseForUpdate(-1 /* expectedUniverseVersion */);
      }
      UniverseDefinitionTaskParams.UserIntent userIntent =
          universe.getUniverseDetails().getPrimaryCluster().userIntent;
      UUID providerUUID = UUID.fromString(userIntent.provider);

      Map<String, String> universeConfig = universe.getConfig();
      boolean runHelmDelete = universeConfig.containsKey(Universe.HELM2_LEGACY);

      PlacementInfo pi = universe.getUniverseDetails().getPrimaryCluster().placementInfo;

      Provider provider = Provider.get(UUID.fromString(userIntent.provider));

      Map<UUID, Map<String, String>> azToConfig = PlacementInfoUtil.getConfigPerAZ(pi);
      boolean isMultiAz = PlacementInfoUtil.isMultiAZ(provider);

      // Cleanup the kms_history table
      createDestroyEncryptionAtRestTask()
              .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.RemovingUnusedServers);

      // Try to unify this with the edit remove pods/deployments flow. Currently delete is
      // tied down to a different base class which makes params porting not straight-forward.
      SubTaskGroup helmDeletes = new SubTaskGroup(
          KubernetesCommandExecutor.CommandType.HELM_DELETE.getSubTaskGroupName(), executor);
      helmDeletes.setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.RemovingUnusedServers);

      // Might be empty if namespace in being deleted.
      SubTaskGroup volumeDeletes = new SubTaskGroup(
          KubernetesCommandExecutor.CommandType.VOLUME_DELETE.getSubTaskGroupName(), executor);
      volumeDeletes.setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.RemovingUnusedServers);

      SubTaskGroup namespaceDeletes = new SubTaskGroup(
          KubernetesCommandExecutor.CommandType.NAMESPACE_DELETE.getSubTaskGroupName(), executor);
      namespaceDeletes.setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.RemovingUnusedServers);
      
      for (Entry<UUID, Map<String, String>> entry : azToConfig.entrySet()) {
        UUID azUUID = entry.getKey();
        String azName = isMultiAz ? AvailabilityZone.get(azUUID).code : null;

        Map<String, String> config = entry.getValue();

        String namespace = config.get("KUBENAMESPACE");

        if (runHelmDelete || namespace != null) {
          // Delete the helm deployments.
          helmDeletes.addTask(createDestroyKubernetesTask(
              universe.getUniverseDetails().nodePrefix, azName, config,
              KubernetesCommandExecutor.CommandType.HELM_DELETE,
              providerUUID));
        }

        // We depend on the fact that, deleting the namespace will
        // delete the pull secret as well as the volumes. That won't
        // be the case with providers which have KUBENAMESPACE
        // paramter at az level in them.
        if (namespace != null) {
          // Delete the PVCs created for this az.
	  volumeDeletes.addTask(createDestroyKubernetesTask(
              universe.getUniverseDetails().nodePrefix, azName, config,
              KubernetesCommandExecutor.CommandType.VOLUME_DELETE,
              providerUUID));

          // TODO(bhavin192): delete the pull secret as well? How to
          // find the pull secret name? Should we delete it when we
          // have multiple releases in one namespace?. It is possible
          // that same provider is creating multiple releases in one
          // namespace.
        } else {
          // Delete the namespaces of the deployments.
          namespaceDeletes.addTask(createDestroyKubernetesTask(
              universe.getUniverseDetails().nodePrefix, azName, config,
              KubernetesCommandExecutor.CommandType.NAMESPACE_DELETE,
              providerUUID));
        }
      }

      subTaskGroupQueue.add(helmDeletes);
      subTaskGroupQueue.add(volumeDeletes);
      subTaskGroupQueue.add(namespaceDeletes);

      // Create tasks to remove the universe entry from the Universe table.
      createRemoveUniverseEntryTask()
          .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.RemovingUnusedServers);

      // Update the swamper target file.
      createSwamperTargetUpdateTask(true /* removeFile */);

      // Run all the tasks.
      subTaskGroupQueue.run();
    } catch (Throwable t) {
      // If for any reason destroy fails we would just unlock the universe for update
      try {
        unlockUniverseForUpdate();
      } catch (Throwable t1) {
        // Ignore the error
      }
      LOG.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
      throw t;
    }
    LOG.info("Finished {} task.", getName());
  }

  protected KubernetesCommandExecutor
      createDestroyKubernetesTask(String nodePrefix, String az, Map<String, String> config,
                                  KubernetesCommandExecutor.CommandType commandType,
                                  UUID providerUUID) {
    KubernetesCommandExecutor.Params params = new KubernetesCommandExecutor.Params();
    params.commandType = commandType;
    params.nodePrefix = nodePrefix;
    params.providerUUID = providerUUID;
    // TODO(bhavin192): should not be here, as it is not necessary
    // that the config we get here is always going to be an az config,
    // though it is true in this particular case.
    params.namespace = config.get("KUBENAMESPACE");

    // TODO(bhavin192): can we just assume that when we have given az,
    // the config is an az config? In this case all the callers just
    // pass the az config only.
    if (az != null) {
      params.nodePrefix = String.format("%s-%s", nodePrefix, az);  
    }
    if (params.namespace == null) {
      params.namespace = params.nodePrefix;
    }
    if (config != null) {
      params.config = config;
    }
    params.universeUUID = taskParams().universeUUID;
    KubernetesCommandExecutor task = new KubernetesCommandExecutor();
    task.initialize(params);
    return task;
  }
}
